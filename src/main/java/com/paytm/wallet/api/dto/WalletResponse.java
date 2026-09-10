package com.paytm.wallet.api.dto;

import com.paytm.wallet.domain.Wallet;
import java.util.UUID;

public record WalletResponse(UUID id, long balancePaise) {

    public static WalletResponse of(Wallet w) {
        return new WalletResponse(w.id(), w.balancePaise());
    }
}
