package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The one infrastructure adapter for {@link UserIdentityRepository}: plain
 * SQL against the runtime {@code brownie_api} connection, matching the
 * stack's choice of Spring JDBC over an ORM (§5.2's {@code persistence.jdbc}
 * boundary).
 */
@Repository
class JdbcUserIdentityRepository implements UserIdentityRepository {

    private static final RowMapper<UserIdentity> ROW_MAPPER = (rs, rowNum) -> new UserIdentity(
            rs.getLong("id"),
            rs.getString("issuer"),
            rs.getString("subject"),
            rs.getString("email"),
            rs.getString("display_name"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("last_login_at", OffsetDateTime.class),
            rs.getObject("disabled_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    JdbcUserIdentityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserIdentity recordLogin(String issuer, String subject, String email, String displayName) {
        // ON CONFLICT makes first-login-creates / later-logins-update a single
        // atomic statement, safe under concurrent logins from the same
        // identity racing each other, rather than a separate check-then-write.
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO user_identity (issuer, subject, email, display_name)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (issuer, subject)
                DO UPDATE SET email = EXCLUDED.email, display_name = EXCLUDED.display_name, last_login_at = now()
                RETURNING id, issuer, subject, email, display_name, created_at, last_login_at, disabled_at
                """,
                ROW_MAPPER,
                issuer,
                subject,
                email,
                displayName);
    }

    @Override
    public Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject) {
        List<UserIdentity> results = jdbcTemplate.query(
                "SELECT id, issuer, subject, email, display_name, created_at, last_login_at, disabled_at "
                        + "FROM user_identity WHERE issuer = ? AND subject = ?",
                ROW_MAPPER,
                issuer,
                subject);
        return results.stream().findFirst();
    }
}
