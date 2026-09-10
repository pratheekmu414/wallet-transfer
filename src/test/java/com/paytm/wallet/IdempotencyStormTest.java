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

/** Gate 2 — the same transfer fired K times concurrently applies exactly one debit + credit. */
class IdempotencyStormTest extends IntegrationTest {

    @Test
    void same_key_fired_30x_applies_once() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet(bob);
        client.mint(a, 100_00L);

        String key = "storm-" + UUID.randomUUID();
        List<ResponseEntity<JsonNode>> responses =
                Concurrently.run(30, i -> client.transfer(alice, a, b, 40_00L, key));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));
        Set<String> transferIds = responses.stream()
                .map(r -> r.getBody().get("id").asText()).collect(Collectors.toSet());
        assertThat(transferIds).as("all replays return the same transfer id").hasSize(1);

        assertThat(client.balance(alice, a)).isEqualTo(60_00L);
        assertThat(client.balance(bob, b)).isEqualTo(40_00L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfers", Long.class)).isEqualTo(1L);
    }

    @Test
    void same_key_different_body_is_409() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet(bob);
        client.mint(a, 100_00L);

        String key = "dupe-" + UUID.randomUUID();
        assertThat(client.transfer(alice, a, b, 10_00L, key).getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> conflict = client.transfer(alice, a, b, 20_00L, key);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo("idempotency_key_conflict");

        // the retried (identical) request still returns the original result
        assertThat(client.transfer(alice, a, b, 10_00L, key).getStatusCode().value()).isEqualTo(200);
        assertThat(client.balance(alice, a)).isEqualTo(90_00L);
    }
}
