package com.paytm.wallet.config;

import java.net.URI;

/**
 * Many free hosts (Render, Railway, Fly, Heroku-style) expose Postgres only as a single
 * {@code DATABASE_URL} like {@code postgres://user:pass@host:5432/dbname}. Spring wants a JDBC URL
 * plus separate credentials. This translates the former into {@code spring.datasource.*} system
 * properties (which outrank the {@code application.yml} defaults), unless the operator has already
 * set {@code SPRING_DATASOURCE_URL} explicitly — that always wins.
 */
public final class DatabaseUrl {

    private DatabaseUrl() {
    }

    public static void applyIfPresent() {
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null || databaseUrl.isBlank()) {
            return;
        }
        if (System.getenv("SPRING_DATASOURCE_URL") != null
                || System.getProperty("spring.datasource.url") != null) {
            return;
        }

        URI uri = URI.create(databaseUrl.replaceFirst("^jdbc:", ""));
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getPath()
                + (uri.getQuery() == null ? "" : "?" + uri.getQuery());
        System.setProperty("spring.datasource.url", jdbcUrl);

        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
            String[] parts = userInfo.split(":", 2);
            System.setProperty("spring.datasource.username", parts[0]);
            if (parts.length > 1) {
                System.setProperty("spring.datasource.password", parts[1]);
            }
        }
    }
}
