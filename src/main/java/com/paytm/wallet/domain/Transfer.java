package com.paytm.wallet.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Transfer(
        UUID id,
        String idempotencyKey,
        String createdBy,
        UUID fromWallet,
        UUID toWallet,
        long amountPaise,
        String status,
        String reason,
        String requestHash,
        UUID reversalOf,
        OffsetDateTime createdAt) {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_DECLINED = "declined";

    public boolean isSucceeded() {
        return STATUS_SUCCEEDED.equals(status);
    }

    public boolean isDeclined() {
        return STATUS_DECLINED.equals(status);
    }
}
