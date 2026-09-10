package com.paytm.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.paytm.wallet.support.IntegrationTest;
import com.paytm.wallet.support.WalletClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ApiBasicsTest extends IntegrationTest {

    @Test
    void requires_bearer_token() {
        assertThat(new WalletClient(rest).post("/wallets", null, null).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void rejects_fractional_amount() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet("bob-" + UUID.randomUUID());

        ResponseEntity<JsonNode> r = client.post("/transfers", alice, Map.of(
                "from", a.toString(), "to", b.toString(),
                "amount_paise", 10.5, "idempotency_key", "k1"));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejects_non_positive_amount() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet("bob-" + UUID.randomUUID());

        assertThat(client.transfer(alice, a, b, 0L, "k0").getStatusCode().value()).isEqualTo(400);
        assertThat(client.transfer(alice, a, b, -5L, "kneg").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknown_wallet_is_404() {
        WalletClient client = new WalletClient(rest);
        assertThat(client.get("/wallets/" + UUID.randomUUID(), "someone").getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void happy_path_transfer_and_status() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet(bob);
        client.mint(a, 500L);

        ResponseEntity<JsonNode> t = client.transfer(alice, a, b, 200L, "happy-1");
        assertThat(t.getStatusCode().value()).isEqualTo(200);
        assertThat(t.getBody().get("status").asText()).isEqualTo("succeeded");
        String id = t.getBody().get("id").asText();

        ResponseEntity<JsonNode> status = client.get("/transfers/" + id, alice);
        assertThat(status.getStatusCode().value()).isEqualTo(200);
        assertThat(status.getBody().get("amount_paise").asLong()).isEqualTo(200L);
        assertThat(client.balance(alice, a)).isEqualTo(300L);
        assertThat(client.balance(bob, b)).isEqualTo(200L);
    }

    @Test
    void insufficient_funds_is_declined_422() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet("bob-" + UUID.randomUUID());
        client.mint(a, 100L);

        ResponseEntity<JsonNode> t = client.transfer(alice, a, b, 500L, "od-1");
        assertThat(t.getStatusCode().value()).isEqualTo(422);
        assertThat(t.getBody().get("status").asText()).isEqualTo("declined");
        assertThat(client.balance(alice, a)).isEqualTo(100L);
    }
}
