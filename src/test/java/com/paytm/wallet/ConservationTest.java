package com.paytm.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.paytm.wallet.support.Concurrently;
import com.paytm.wallet.support.IntegrationTest;
import com.paytm.wallet.support.WalletClient;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 3 — hundreds of concurrent transfers among a small set of wallets, including A→B and B→A at
 * once and some that overdraw. Total balance is unchanged; no balance goes negative; overdrawing
 * transfers are declined cleanly.
 */
class ConservationTest extends IntegrationTest {

    @Test
    void conservation_and_no_overdraft_under_contention() {
        WalletClient client = new WalletClient(rest);
        String owner = "owner-" + UUID.randomUUID();

        int walletCount = 5;
        UUID[] w = new UUID[walletCount];
        for (int i = 0; i < walletCount; i++) {
            w[i] = client.createWallet(owner + "-" + i);
            client.mint(w[i], 1_000_00L);
        }
        long totalBefore = total();
        assertThat(totalBefore).isEqualTo((long) walletCount * 1_000_00L);

        int attempts = 400;
        List<ResponseEntity<JsonNode>> results = Concurrently.run(attempts, i -> {
            int from = ThreadLocalRandom.current().nextInt(walletCount);
            int to = ThreadLocalRandom.current().nextInt(walletCount);
            while (to == from) {
                to = ThreadLocalRandom.current().nextInt(walletCount);
            }
            // Mix of affordable and deliberately-overdrawing amounts.
            long amount = ThreadLocalRandom.current().nextBoolean()
                    ? ThreadLocalRandom.current().nextLong(1, 50_00L)
                    : ThreadLocalRandom.current().nextLong(900_00L, 5_000_00L);
            return client.transfer(owner, w[from], w[to], amount, "conv-" + i);
        });

        long applied = results.stream().filter(r -> r.getStatusCode().value() == 200).count();
        long declined = results.stream().filter(r -> r.getStatusCode().value() == 422).count();
        assertThat(applied + declined).as("every request resolved cleanly (no 500s)").isEqualTo(attempts);
        assertThat(declined).as("some transfers overdrew and were declined").isPositive();

        assertThat(total()).as("money is conserved").isEqualTo(totalBefore);

        Long negatives = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE balance_paise < 0", Long.class);
        assertThat(negatives).as("no negative balances").isEqualTo(0L);

        // declined transfers persist as declined and moved nothing
        Long declinedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE status = 'declined'", Long.class);
        assertThat(declinedRows).isEqualTo(declined);
        Long pendingRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE status = 'pending'", Long.class);
        assertThat(pendingRows).as("nothing left half-applied").isEqualTo(0L);
    }

    private long total() {
        Long t = jdbc.queryForObject("SELECT COALESCE(SUM(balance_paise),0) FROM wallets", Long.class);
        return t == null ? 0L : t;
    }
}
