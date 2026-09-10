package com.paytm.wallet.api;

import com.paytm.wallet.api.dto.ReverseRequest;
import com.paytm.wallet.api.dto.TransferRequest;
import com.paytm.wallet.api.dto.TransferResponse;
import com.paytm.wallet.auth.Caller;
import com.paytm.wallet.domain.ApiException;
import com.paytm.wallet.domain.Transfer;
import com.paytm.wallet.service.TransferResult;
import com.paytm.wallet.service.TransferService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /**
     * Move money between two wallets, exactly once per {@code idempotency_key}.
     *
     * <p>Status codes are chosen so a retry storm produces <em>identical</em> responses: a succeeded
     * transfer is always {@code 200} (whether applied now or replayed), a declined transfer is
     * always {@code 422}, and a key reused with a different body is {@code 409}.
     */
    @PostMapping
    public ResponseEntity<TransferResponse> create(@Valid @RequestBody TransferRequest req, Caller caller) {
        return respond(transfers.transfer(caller.userId(), req));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id, Caller caller) {
        Transfer t = transfers.find(id)
                .orElseThrow(() -> ApiException.notFound("transfer " + id + " not found"));
        return TransferResponse.of(t);
    }

    /** Reverse a succeeded transfer: move the exact amount back, with its own idempotency key. */
    @PostMapping("/{id}/reverse")
    public ResponseEntity<TransferResponse> reverse(
            @PathVariable UUID id, @Valid @RequestBody ReverseRequest req, Caller caller) {
        return respond(transfers.reverse(caller.userId(), id, req.idempotencyKey()));
    }

    private static ResponseEntity<TransferResponse> respond(TransferResult result) {
        HttpStatus status = switch (result.outcome()) {
            case APPLIED, REPLAY ->
                    result.transfer().isDeclined() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.OK;
            case DECLINED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(TransferResponse.of(result.transfer()));
    }
}
