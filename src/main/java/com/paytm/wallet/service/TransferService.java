package com.paytm.wallet.service;

import com.paytm.wallet.api.dto.TransferRequest;
import com.paytm.wallet.domain.ApiException;
import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.obs.WalletMetrics;
import com.paytm.wallet.repo.TransferRepository;
import com.paytm.wallet.repo.WalletRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The money-movement core.
 *
 * <h2>Simplest-correct mechanism</h2>
 * <ul>
 *   <li><b>No-overdraft &amp; conservation</b>: inside one transaction we take row locks on both
 *       wallets (see below), then debit with an atomic conditional
 *       {@code UPDATE wallets SET balance = balance - :amt WHERE id = :from AND balance >= :amt} —
 *       zero rows changed ⇒ decline, no partial apply — and credit with a matching {@code + :amt}.
 *       We never read a balance into the app and write it back, so there are no lost updates and the
 *       sum of balances is invariant across any transfer.</li>
 *   <li><b>Deadlock</b>: both wallet rows are locked up front <em>in ascending wallet-id order</em>
 *       ({@link WalletRepository#lockPairInOrder}). Every transfer and every reversal therefore
 *       requests the same two locks in the same order, so A→B and B→A running at once cannot form a
 *       lock-ordering cycle — one simply waits for the other to commit. A bounded retry on
 *       {@link org.springframework.dao.TransientDataAccessException} mops up any residual
 *       serialization/lock-timeout blips.</li>
 *   <li><b>Exactly-once</b>: {@link TransferRepository#insertPending} runs <em>in the same
 *       transaction</em> as the debit/credit. The {@code (created_by, idempotency_key)} unique
 *       constraint is the serialization point — a concurrent duplicate blocks on the index and then
 *       either loses the race (gets {@link DuplicateKeyException}, re-reads the committed transfer)
 *       or, if the winner rolled back, proceeds. Same key + different body ⇒ {@code 409}.</li>
 * </ul>
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final WalletRepository wallets;
    private final TransferRepository transfers;
    private final WalletMetrics metrics;
    private final TransactionTemplate tx;

    public TransferService(
            WalletRepository wallets,
            TransferRepository transfers,
            WalletMetrics metrics,
            PlatformTransactionManager txManager) {
        this.wallets = wallets;
        this.transfers = transfers;
        this.metrics = metrics;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Optional<Transfer> find(UUID id) {
        return transfers.findById(id);
    }

    // ---- plain transfer ---------------------------------------------------

    public TransferResult transfer(String callerUserId, TransferRequest req) {
        if (req.from().equals(req.to())) {
            throw ApiException.badRequest("from and to must differ");
        }
        String requestHash = hash("transfer", req.from(), req.to(), req.amountPaise());

        Optional<Transfer> existing = transfers.findByKey(callerUserId, req.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash);
        }

        // Wallet existence is checked before opening the transaction: wallets are never deleted, so
        // this is not a TOCTOU risk, and it keeps "unknown wallet" a clean 404 instead of a FK error.
        wallets.findById(req.from())
                .orElseThrow(() -> ApiException.notFound("from wallet " + req.from() + " not found"));
        wallets.findById(req.to())
                .orElseThrow(() -> ApiException.notFound("to wallet " + req.to() + " not found"));

        try {
            return withRetry(() -> tx.execute(status -> applyTransfer(callerUserId, req, requestHash)));
        } catch (DuplicateKeyException race) {
            Transfer t = transfers.findByKey(callerUserId, req.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("idempotency race but no row", race));
            return replay(t, requestHash);
        }
    }

    private TransferResult applyTransfer(String callerUserId, TransferRequest req, String requestHash) {
        UUID id = UUID.randomUUID();

        // Lock both wallet rows first, in ascending id order — this is the deadlock guard, and it
        // also means the FK KEY-SHARE locks that the transfer INSERT below would take are already
        // covered by a stronger lock we hold, so there is no second, differently-ordered lock step.
        wallets.lockPairInOrder(req.from(), req.to());

        transfers.insertPending(new Transfer(
                id, req.idempotencyKey(), callerUserId, req.from(), req.to(), req.amountPaise(),
                Transfer.STATUS_PENDING, null, requestHash, null, null));
        log.info("transfer.created",
                kv("event", "transfer.created"), kv("transfer_id", id),
                kv("from", req.from()), kv("to", req.to()), kv("amount_paise", req.amountPaise()));

        int debited = wallets.debitIfSufficient(req.from(), req.amountPaise());
        if (debited == 0) {
            transfers.markDeclined(id, "insufficient_funds");
            metrics.transferDeclined("insufficient_funds");
            log.info("transfer.declined",
                    kv("event", "transfer.declined"), kv("transfer_id", id),
                    kv("reason", "insufficient_funds"), kv("from", req.from()),
                    kv("amount_paise", req.amountPaise()));
            return new TransferResult(transfers.findById(id).orElseThrow(), TransferResult.Outcome.DECLINED);
        }
        log.info("transfer.debited",
                kv("event", "transfer.debited"), kv("transfer_id", id),
                kv("from", req.from()), kv("amount_paise", req.amountPaise()));

        if (wallets.credit(req.to(), req.amountPaise()) == 0) {
            throw ApiException.notFound("to wallet " + req.to() + " not found");
        }
        transfers.markSucceeded(id);
        metrics.transferCreated();
        log.info("transfer.credited",
                kv("event", "transfer.credited"), kv("transfer_id", id),
                kv("to", req.to()), kv("amount_paise", req.amountPaise()));
        return new TransferResult(transfers.findById(id).orElseThrow(), TransferResult.Outcome.APPLIED);
    }

    // ---- reversal (POST /transfers/{id}/reverse) -------------------------

    public TransferResult reverse(String callerUserId, UUID originalId, String idempotencyKey) {
        Transfer original = transfers.findById(originalId)
                .orElseThrow(() -> ApiException.notFound("transfer " + originalId + " not found"));
        if (original.reversalOf() != null) {
            throw ApiException.conflict("not_reversible", "cannot reverse a reversal");
        }
        if (!original.isSucceeded()) {
            throw ApiException.conflict("not_reversible",
                    "only succeeded transfers can be reversed (status=" + original.status() + ")");
        }

        String requestHash = hash("reverse", originalId, originalId, original.amountPaise());

        Optional<TransferResult> replay = existingReversal(callerUserId, originalId, idempotencyKey, requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }

        try {
            return withRetry(() ->
                    tx.execute(status -> applyReversal(callerUserId, original, idempotencyKey, requestHash)));
        } catch (DuplicateKeyException race) {
            return existingReversal(callerUserId, originalId, idempotencyKey, requestHash)
                    .orElseThrow(() -> new IllegalStateException("reversal race but no row", race));
        }
    }

    /**
     * Resolves a reversal request that collides with an already-persisted row: our own key (replay),
     * or a reversal of this transfer under a <em>different</em> key ({@code 409 already_reversed}).
     * Returns empty when there is no existing reversal and the caller should proceed.
     */
    private Optional<TransferResult> existingReversal(
            String callerUserId, UUID originalId, String idempotencyKey, String requestHash) {

        Optional<Transfer> byKey = transfers.findByKey(callerUserId, idempotencyKey);
        if (byKey.isPresent()) {
            return Optional.of(replayReverse(byKey.get(), originalId, requestHash));
        }
        Optional<Transfer> byOriginal = transfers.findByReversalOf(originalId);
        if (byOriginal.isPresent()) {
            Transfer existing = byOriginal.get();
            if (existing.createdBy().equals(callerUserId)
                    && existing.idempotencyKey().equals(idempotencyKey)) {
                return Optional.of(replayReverse(existing, originalId, requestHash));
            }
            throw ApiException.conflict("already_reversed",
                    "transfer " + originalId + " already reversed by " + existing.id());
        }
        return Optional.empty();
    }

    private TransferResult applyReversal(
            String callerUserId, Transfer original, String idempotencyKey, String requestHash) {
        UUID id = UUID.randomUUID();
        // Same ledger primitive, roles swapped: debit the original recipient, credit the sender.
        UUID debitWallet = original.toWallet();
        UUID creditWallet = original.fromWallet();
        long amount = original.amountPaise();

        wallets.lockPairInOrder(debitWallet, creditWallet);

        transfers.insertPending(new Transfer(
                id, idempotencyKey, callerUserId, debitWallet, creditWallet, amount,
                Transfer.STATUS_PENDING, null, requestHash, original.id(), null));
        log.info("transfer.reversal.created",
                kv("event", "transfer.reversal.created"), kv("transfer_id", id),
                kv("reversal_of", original.id()), kv("amount_paise", amount));

        int debited = wallets.debitIfSufficient(debitWallet, amount);
        if (debited == 0) {
            transfers.markDeclined(id, "insufficient_funds");
            metrics.transferDeclined("insufficient_funds_on_reverse");
            log.info("transfer.reversal.declined",
                    kv("event", "transfer.reversal.declined"), kv("transfer_id", id),
                    kv("reversal_of", original.id()), kv("reason", "insufficient_funds"),
                    kv("recipient_wallet", debitWallet));
            return new TransferResult(transfers.findById(id).orElseThrow(), TransferResult.Outcome.DECLINED);
        }
        if (wallets.credit(creditWallet, amount) == 0) {
            throw ApiException.notFound("original sender wallet " + creditWallet + " not found");
        }
        transfers.markSucceeded(id);
        metrics.reversalCreated();
        log.info("transfer.reversal.applied",
                kv("event", "transfer.reversal.applied"), kv("transfer_id", id),
                kv("reversal_of", original.id()), kv("amount_paise", amount));
        return new TransferResult(transfers.findById(id).orElseThrow(), TransferResult.Outcome.APPLIED);
    }

    // ---- helpers --------------------------------------------------------

    private TransferResult replay(Transfer stored, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw ApiException.conflict("idempotency_key_conflict",
                    "idempotency key reused with a different request body");
        }
        metrics.idempotentReplay();
        log.info("transfer.idempotent_replay",
                kv("event", "transfer.idempotent_replay"), kv("transfer_id", stored.id()),
                kv("status", stored.status()));
        return new TransferResult(stored, TransferResult.Outcome.REPLAY);
    }

    private TransferResult replayReverse(Transfer stored, UUID originalId, String requestHash) {
        if (!originalId.equals(stored.reversalOf()) || !stored.requestHash().equals(requestHash)) {
            throw ApiException.conflict("idempotency_key_conflict",
                    "idempotency key reused for a different reversal");
        }
        metrics.idempotentReplay();
        log.info("transfer.idempotent_replay",
                kv("event", "transfer.idempotent_replay"), kv("transfer_id", stored.id()),
                kv("status", stored.status()), kv("reversal_of", originalId));
        return new TransferResult(stored, TransferResult.Outcome.REPLAY);
    }

    /**
     * Retries a unit of work a few times on a transient failure (deadlock victim, serialization
     * failure, lock timeout). With the sorted lock order this should essentially never fire, but it
     * turns a rare blip into a slightly slower success instead of a 500.
     */
    private <T> T withRetry(Supplier<T> work) {
        int maxAttempts = 4;
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (TransientDataAccessException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("transfer.retry",
                        kv("event", "transfer.retry"), kv("attempt", attempt),
                        kv("cause", e.getClass().getSimpleName()));
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5L, 25L * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static String hash(String kind, UUID a, UUID b, long amount) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = kind + "|" + a + "|" + b + "|" + amount;
            return HexFormat.of().formatHex(md.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
