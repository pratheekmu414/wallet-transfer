package com.paytm.wallet.repo;

import com.paytm.wallet.domain.Wallet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WalletRepository {

    private static final RowMapper<Wallet> MAPPER = new DataClassRowMapper<>(Wallet.class);

    private final NamedParameterJdbcTemplate jdbc;

    public WalletRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Wallet> findById(UUID id) {
        return jdbc.query(
                "SELECT id, user_id, balance_paise, created_at FROM wallets WHERE id = :id",
                new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    public Optional<Wallet> findByUserId(String userId) {
        return jdbc.query(
                "SELECT id, user_id, balance_paise, created_at FROM wallets WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), MAPPER).stream().findFirst();
    }

    /**
     * Inserts a fresh zero-balance wallet. Throws
     * {@link org.springframework.dao.DuplicateKeyException} if a wallet already exists for the user
     * (the {@code wallets.user_id} unique constraint) — the caller turns that into a get.
     */
    public Wallet insert(String userId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO wallets (id, user_id, balance_paise) VALUES (:id, :userId, 0)",
                new MapSqlParameterSource().addValue("id", id).addValue("userId", userId));
        return findById(id).orElseThrow();
    }

    /**
     * Takes row locks on the two wallets in ascending id order, so every transfer (and reversal)
     * acquires the same two locks in the same order. This is what makes the A→B / B→A cross
     * deadlock-free: there is no lock-ordering cycle to form.
     */
    public void lockPairInOrder(UUID one, UUID two) {
        UUID lo = one.compareTo(two) <= 0 ? one : two;
        UUID hi = lo.equals(one) ? two : one;
        jdbc.queryForList("SELECT id FROM wallets WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", lo));
        jdbc.queryForList("SELECT id FROM wallets WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", hi));
    }

    public boolean exists(UUID id) {
        Boolean present = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM wallets WHERE id = :id)",
                new MapSqlParameterSource("id", id), Boolean.class);
        return Boolean.TRUE.equals(present);
    }

    /**
     * Atomic conditional debit — the whole correctness story for no-overdraft. Returns the number
     * of rows changed: {@code 1} means the debit applied, {@code 0} means insufficient funds (or the
     * wallet does not exist) and nothing changed.
     */
    public int debitIfSufficient(UUID id, long amountPaise) {
        return jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise - :amount "
                        + "WHERE id = :id AND balance_paise >= :amount",
                new MapSqlParameterSource().addValue("id", id).addValue("amount", amountPaise));
    }

    /** Unconditional credit. Returns rows changed ({@code 0} if the wallet does not exist). */
    public int credit(UUID id, long amountPaise) {
        return jdbc.update(
                "UPDATE wallets SET balance_paise = balance_paise + :amount WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("amount", amountPaise));
    }

    // ---- test / ops helpers -------------------------------------------------

    public long totalBalancePaise() {
        Long total = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COALESCE(SUM(balance_paise), 0) FROM wallets", Long.class);
        return total == null ? 0L : total;
    }

    public List<Wallet> findAll() {
        return jdbc.getJdbcTemplate().query(
                "SELECT id, user_id, balance_paise, created_at FROM wallets ORDER BY created_at", MAPPER);
    }

    public long countForUser(String userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE user_id = :userId",
                new MapSqlParameterSource("userId", userId), Long.class);
        return count == null ? 0L : count;
    }
}
