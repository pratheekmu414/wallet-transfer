package com.paytm.wallet.service;

import com.paytm.wallet.domain.ApiException;
import com.paytm.wallet.domain.Wallet;
import com.paytm.wallet.obs.WalletMetrics;
import com.paytm.wallet.repo.WalletRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository wallets;
    private final WalletMetrics metrics;

    public WalletService(WalletRepository wallets, WalletMetrics metrics) {
        this.wallets = wallets;
        this.metrics = metrics;
    }

    /**
     * Race-free get-or-create. The {@code wallets.user_id} unique constraint is the arbiter: at most
     * one concurrent {@code INSERT} wins, every loser blocks on the index, gets a
     * {@link DuplicateKeyException}, and re-reads the committed row. Two concurrent calls for a fresh
     * user therefore yield exactly one wallet.
     */
    public Wallet getOrCreate(String userId) {
        return wallets.findByUserId(userId).orElseGet(() -> create(userId));
    }

    private Wallet create(String userId) {
        try {
            Wallet w = wallets.insert(userId);
            metrics.walletCreated();
            log.info("wallet.created",
                    kv("event", "wallet.created"),
                    kv("wallet_id", w.id()),
                    kv("user_id", userId));
            return w;
        } catch (DuplicateKeyException race) {
            metrics.getOrCreateRace();
            Wallet w = wallets.findByUserId(userId)
                    .orElseThrow(() -> new IllegalStateException(
                            "unique violation on wallets.user_id but no row found for " + userId, race));
            log.info("wallet.get_or_create.race_lost",
                    kv("event", "wallet.get_or_create.race_lost"),
                    kv("wallet_id", w.id()),
                    kv("user_id", userId));
            return w;
        }
    }

    public Wallet require(UUID id) {
        return wallets.findById(id).orElseThrow(() -> ApiException.notFound("wallet " + id + " not found"));
    }
}
