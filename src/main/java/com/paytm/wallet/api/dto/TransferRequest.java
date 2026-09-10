package com.paytm.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Body for {@code POST /transfers}. JSON is snake_case ({@code amount_paise},
 * {@code idempotency_key}). Money is an integer number of paise; a fractional value fails to bind
 * (see {@code spring.jackson.deserialization.accept-float-as-int=false}).
 */
public record TransferRequest(
        @NotNull UUID from,
        @NotNull UUID to,
        @NotNull @Positive Long amountPaise,
        @NotBlank @Size(max = 200) String idempotencyKey) {
}
