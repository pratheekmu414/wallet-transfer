package com.paytm.wallet.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Thin HTTP helper for the integration tests. */
public class WalletClient {

    private final TestRestTemplate rest;

    public WalletClient(TestRestTemplate rest) {
        this.rest = rest;
    }

    public ResponseEntity<JsonNode> post(String path, String token, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(token)), JsonNode.class);
    }

    public ResponseEntity<JsonNode> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)), JsonNode.class);
    }

    public UUID createWallet(String token) {
        ResponseEntity<JsonNode> r = post("/wallets", token, null);
        if (!r.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("createWallet failed: " + r.getStatusCode() + " " + r.getBody());
        }
        return UUID.fromString(r.getBody().get("id").asText());
    }

    public long balance(String token, UUID walletId) {
        ResponseEntity<JsonNode> r = get("/wallets/" + walletId, token);
        return r.getBody().get("balance_paise").asLong();
    }

    public ResponseEntity<JsonNode> transfer(String token, UUID from, UUID to, long amountPaise, String key) {
        return post("/transfers", token, Map.of(
                "from", from.toString(),
                "to", to.toString(),
                "amount_paise", amountPaise,
                "idempotency_key", key));
    }

    public void mint(UUID walletId, long amountPaise) {
        ResponseEntity<JsonNode> r = post("/admin/credit", "dev-admin-token", Map.of(
                "wallet_id", walletId.toString(), "amount_paise", amountPaise));
        if (!r.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("mint failed: " + r.getStatusCode() + " " + r.getBody());
        }
    }

    public ResponseEntity<JsonNode> reverse(String token, UUID transferId, String key) {
        return post("/transfers/" + transferId + "/reverse", token, Map.of("idempotency_key", key));
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }
}
