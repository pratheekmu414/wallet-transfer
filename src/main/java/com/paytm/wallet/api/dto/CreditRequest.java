package com.paytm.wallet.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/** Body for {@code POST /admin/credit} — the only way money enters the system (test/ops seeding). */
public record CreditRequest(
        @NotNull UUID walletId,
        @NotNull @Positive Long amountPaise) {
}
