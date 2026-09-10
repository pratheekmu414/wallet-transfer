package com.paytm.wallet.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;

/** One shared embedded Postgres for the whole test JVM (no Docker required). */
public final class EmbeddedPg {

    private static EmbeddedPostgres instance;

    private EmbeddedPg() {
    }

    public static synchronized EmbeddedPostgres get() {
        if (instance == null) {
            try {
                instance = EmbeddedPostgres.builder().start();
            } catch (IOException e) {
                throw new IllegalStateException("failed to start embedded Postgres", e);
            }
        }
        return instance;
    }

    public static String jdbcUrl() {
        return get().getJdbcUrl("postgres", "postgres");
    }
}
