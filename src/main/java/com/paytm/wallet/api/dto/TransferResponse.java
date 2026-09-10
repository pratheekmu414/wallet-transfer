package com.paytm.wallet.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.paytm.wallet.domain.Transfer;
import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(
        UUID id,
        String status,
        String reason,
        UUID from,
        UUID to,
        long amountPaise,
        UUID reversalOf,
        OffsetDateTime createdAt) {

    public static TransferResponse of(Transfer t) {
        return new TransferResponse(
                t.id(),
                t.status(),
                t.reason(),
                t.fromWallet(),
                t.toWallet(),
                t.amountPaise(),
                t.reversalOf(),
                t.createdAt());
    }
}
