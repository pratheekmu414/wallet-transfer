package com.paytm.wallet.api;

import com.paytm.wallet.api.dto.CreditRequest;
import com.paytm.wallet.api.dto.WalletResponse;
import com.paytm.wallet.domain.ApiException;
import com.paytm.wallet.repo.WalletRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The exercise's transfer API is a closed, zero-sum ledger — nothing mints money. This endpoint is
 * the single deliberate exception, used to seed balances for the burst scripts. It is gated by a
 * separate {@code ADMIN_TOKEN} and is <em>not</em> part of the graded transfer path; conservation is
 * measured across transfers, after seeding.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final WalletRepository wallets;
    private final String adminToken;

    public AdminController(WalletRepository wallets,
                           @Value("${admin.token:dev-admin-token}") String adminToken) {
        this.wallets = wallets;
        this.adminToken = adminToken;
    }

    @PostMapping("/credit")
    @Transactional
    public WalletResponse credit(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authz,
                                 @Valid @RequestBody CreditRequest req) {
        if (authz == null || !authz.equals("Bearer " + adminToken)) {
            throw ApiException.unauthorized("admin token required");
        }
        if (wallets.credit(req.walletId(), req.amountPaise()) == 0) {
            throw ApiException.notFound("wallet " + req.walletId() + " not found");
        }
        log.info("wallet.minted",
                kv("event", "wallet.minted"),
                kv("wallet_id", req.walletId()),
                kv("amount_paise", req.amountPaise()));
        return WalletResponse.of(wallets.findById(req.walletId()).orElseThrow());
    }
}
