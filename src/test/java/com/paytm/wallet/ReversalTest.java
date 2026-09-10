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

/** R3 follow-up — POST /transfers/{id}/reverse: conserves money, refunds exactly once. */
class ReversalTest extends IntegrationTest {

    @Test
    void reverse_twice_concurrently_refunds_once_and_restores_total() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet(bob);
        client.mint(a, 100_00L);
        long totalBefore = total();

        UUID transferId = UUID.fromString(
                client.transfer(alice, a, b, 30_00L, "t-" + UUID.randomUUID()).getBody().get("id").asText());
        assertThat(client.balance(alice, a)).isEqualTo(70_00L);

        String reverseKey = "rev-" + UUID.randomUUID();
        List<ResponseEntity<JsonNode>> results =
                Concurrently.run(20, i -> client.reverse(alice, transferId, reverseKey));

        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));
        Set<String> ids = results.stream()
                .map(r -> r.getBody().get("id").asText()).collect(Collectors.toSet());
        assertThat(ids).hasSize(1);

        assertThat(client.balance(alice, a)).isEqualTo(100_00L);
        assertThat(client.balance(bob, b)).isEqualTo(0L);
        assertThat(total()).isEqualTo(totalBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE reversal_of = ?::uuid", Long.class, transferId.toString()))
                .isEqualTo(1L);
    }

    @Test
    void reversing_an_already_reversed_transfer_is_409() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet(bob);
        client.mint(a, 50_00L);

        UUID transferId = UUID.fromString(
                client.transfer(alice, a, b, 20_00L, "t-" + UUID.randomUUID()).getBody().get("id").asText());
        assertThat(client.reverse(alice, transferId, "rev-1").getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> second = client.reverse(alice, transferId, "rev-2");
        assertThat(second.getStatusCode().value()).isEqualTo(409);
        assertThat(second.getBody().get("code").asText()).isEqualTo("already_reversed");
    }

    @Test
    void reversal_declines_cleanly_when_recipient_has_spent_the_funds() {
        WalletClient client = new WalletClient(rest);
        String alice = "alice-" + UUID.randomUUID();
        String bob = "bob-" + UUID.randomUUID();
        String carol = "carol-" + UUID.randomUUID();
        UUID a = client.createWallet(alice);
        UUID b = client.createWallet(bob);
        UUID c = client.createWallet(carol);
        client.mint(a, 40_00L);
        long totalBefore = total();

        UUID transferId = UUID.fromString(
                client.transfer(alice, a, b, 40_00L, "t-" + UUID.randomUUID()).getBody().get("id").asText());
        // Bob moves everything onward before the reversal lands.
        client.transfer(bob, b, c, 40_00L, "t2-" + UUID.randomUUID());

        ResponseEntity<JsonNode> reversal = client.reverse(alice, transferId, "rev-" + UUID.randomUUID());
        assertThat(reversal.getStatusCode().value()).isEqualTo(422);
        assertThat(reversal.getBody().get("status").asText()).isEqualTo("declined");
        assertThat(reversal.getBody().get("reason").asText()).isEqualTo("insufficient_funds");

        assertThat(total()).isEqualTo(totalBefore);
        Long negatives = jdbc.queryForObject("SELECT COUNT(*) FROM wallets WHERE balance_paise < 0", Long.class);
        assertThat(negatives).isEqualTo(0L);
    }

    private long total() {
        Long t = jdbc.queryForObject("SELECT COALESCE(SUM(balance_paise),0) FROM wallets", Long.class);
        return t == null ? 0L : t;
    }
}
