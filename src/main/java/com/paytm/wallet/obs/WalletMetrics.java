package com.paytm.wallet.obs;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Domain counters, alongside the HTTP request rate / latency (p99) / error rate that Spring Boot's
 * actuator exports automatically as {@code http_server_requests_seconds}.
 *
 * <p>Micrometer appends {@code _total} to counter names for Prometheus, so the builder name here is
 * the exported name minus that suffix. ({@code _created} is avoided as a trailing word — Prometheus
 * reserves {@code _created} for OpenMetrics counter-creation timestamps.)
 */
@Component
public class WalletMetrics {

    private final Counter walletsOpened;
    private final Counter getOrCreateRaces;
    private final Counter transfersApplied;
    private final Counter idempotentReplays;
    private final Counter reversalsApplied;
    private final MeterRegistry registry;

    public WalletMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.walletsOpened = Counter.builder("wallet_wallets_opened")
                .description("Wallets created via get-or-create").register(registry);
        this.getOrCreateRaces = Counter.builder("wallet_getorcreate_races")
                .description("Concurrent get-or-create calls that lost the insert race and reused the existing wallet")
                .register(registry);
        this.transfersApplied = Counter.builder("wallet_transfers_applied")
                .description("Transfers that applied a debit + credit").register(registry);
        this.idempotentReplays = Counter.builder("wallet_idempotent_replays")
                .description("Requests that matched an existing idempotency key and returned the stored result")
                .register(registry);
        this.reversalsApplied = Counter.builder("wallet_reversals_applied")
                .description("Reversal transfers that applied").register(registry);
    }

    public void walletCreated() {
        walletsOpened.increment();
    }

    public void getOrCreateRace() {
        getOrCreateRaces.increment();
    }

    public void transferCreated() {
        transfersApplied.increment();
    }

    public void reversalCreated() {
        reversalsApplied.increment();
    }

    public void idempotentReplay() {
        idempotentReplays.increment();
    }

    public void transferDeclined(String reason) {
        Counter.builder("wallet_transfers_declined")
                .description("Transfers declined without applying")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }
}
