package com.paytm.wallet.auth;

/** The authenticated principal for a request, derived from the bearer token. */
public record Caller(String userId) {
}
