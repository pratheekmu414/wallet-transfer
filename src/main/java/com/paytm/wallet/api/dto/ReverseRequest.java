package com.paytm.wallet.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body for {@code POST /transfers/{id}/reverse}. The reversal carries its own idempotency key. */
public record ReverseRequest(
        @NotBlank @Size(max = 200) String idempotencyKey) {
}
