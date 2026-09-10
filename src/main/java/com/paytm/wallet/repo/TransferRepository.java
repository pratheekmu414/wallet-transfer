package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Transfer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransferRepository {

    private static final String COLUMNS =
            "id, idempotency_key, created_by, from_wallet, to_wallet, amount_paise, "
                    + "status, reason, request_hash, reversal_of, created_at";

    private static final RowMapper<Transfer> MAPPER = new DataClassRowMapper<>(Transfer.class);

    private final NamedParameterJdbcTemplate jdbc;

    public TransferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Transfer> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM transfers WHERE id = :id",
                new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    public Optional<Transfer> findByKey(String createdBy, String idempotencyKey) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transfers WHERE created_by = :createdBy "
                        + "AND idempotency_key = :key",
                new MapSqlParameterSource().addValue("createdBy", createdBy).addValue("key", idempotencyKey),
                MAPPER).stream().findFirst();
    }

    public Optional<Transfer> findByReversalOf(UUID originalTransferId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM transfers WHERE reversal_of = :orig",
                new MapSqlParameterSource("orig", originalTransferId), MAPPER).stream().findFirst();
    }

    /**
     * Inserts the transfer row in {@code pending} state. This is the idempotency checkpoint: it runs
     * <em>inside the same transaction</em> as the debit/credit, and the
     * {@code (created_by, idempotency_key)} unique constraint (and, for reversals, the partial
     * unique index on {@code reversal_of}) is what serialises concurrent duplicates. A losing racer
     * blocks here and then gets a {@link org.springframework.dao.DuplicateKeyException}.
     */
    public void insertPending(Transfer t) {
        jdbc.update(
                "INSERT INTO transfers "
                        + "(id, idempotency_key, created_by, from_wallet, to_wallet, amount_paise, "
                        + " status, request_hash, reversal_of) "
                        + "VALUES (:id, :key, :createdBy, :from, :to, :amount, 'pending', :hash, :reversalOf)",
                new MapSqlParameterSource()
                        .addValue("id", t.id())
                        .addValue("key", t.idempotencyKey())
                        .addValue("createdBy", t.createdBy())
                        .addValue("from", t.fromWallet())
                        .addValue("to", t.toWallet())
                        .addValue("amount", t.amountPaise())
                        .addValue("hash", t.requestHash())
                        .addValue("reversalOf", t.reversalOf()));
    }

    public void markSucceeded(UUID id) {
        jdbc.update("UPDATE transfers SET status = 'succeeded', reason = NULL WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    public void markDeclined(UUID id, String reason) {
        jdbc.update("UPDATE transfers SET status = 'declined', reason = :reason WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("reason", reason));
    }

    public long count() {
        Long count = jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM transfers", Long.class);
        return count == null ? 0L : count;
    }
}
