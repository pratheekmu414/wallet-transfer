package com.paytm.wallet.api;

import com.paytm.wallet.api.dto.WalletResponse;
import com.paytm.wallet.auth.Caller;
import com.paytm.wallet.service.WalletService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /** Get-or-create the caller's wallet. Idempotent: always returns the one wallet for this user. */
    @PostMapping
    public WalletResponse getOrCreate(Caller caller) {
        return WalletResponse.of(wallets.getOrCreate(caller.userId()));
    }

    @GetMapping("/{id}")
    public WalletResponse get(@PathVariable UUID id, Caller caller) {
        return WalletResponse.of(wallets.require(id));
    }
}
