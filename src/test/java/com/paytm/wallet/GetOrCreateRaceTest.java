package com.paytm.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.paytm.wallet.support.Concurrently;
import com.paytm.wallet.support.IntegrationTest;
import com.paytm.wallet.support.WalletClient;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Gate 1 — two (here: fifty) concurrent POST /wallets for a fresh user yield exactly one wallet. */
class GetOrCreateRaceTest extends IntegrationTest {

    @Test
    void concurrent_get_or_create_yields_one_wallet() {
        WalletClient client = new WalletClient(rest);
        String token = "race-user-" + UUID.randomUUID();

        List<ResponseEntity<JsonNode>> responses =
                Concurrently.run(50, i -> client.post("/wallets", token, null));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));

        Set<String> walletIds = responses.stream()
                .map(r -> r.getBody().get("id").asText())
                .collect(Collectors.toSet());
        assertThat(walletIds).hasSize(1);

        String derivedUserId = jdbc.queryForObject(
                "SELECT user_id FROM wallets LIMIT 1", String.class);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE user_id = ?", Long.class, derivedUserId);
        assertThat(count).isEqualTo(1L);
    }
}
