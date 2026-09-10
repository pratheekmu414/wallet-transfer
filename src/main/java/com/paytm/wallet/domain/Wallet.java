package com.paytm.wallet.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Wallet(UUID id, String userId, long balancePaise, OffsetDateTime createdAt) {
}
