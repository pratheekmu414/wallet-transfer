package com.paytm.wallet.service;

import com.paytm.wallet.domain.Transfer;

/** The transfer plus how it resolved, so the controller can pick an HTTP status. */
public record TransferResult(Transfer transfer, Outcome outcome) {

    public enum Outcome {
        /** A debit + credit was applied by this call. */
        APPLIED,
        /** The debit was declined (e.g. insufficient funds); nothing moved. */
        DECLINED,
        /** This call matched an existing idempotency key and returned the stored transfer. */
        REPLAY
    }
}
