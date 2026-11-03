package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The one infrastructure adapter for {@link UserIdentityRepository}: plain
 * SQL against the runtime {@code brownie_api} connection, matching the
 * stack's choice of Spring JDBC over an ORM.
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
            rs.getObject("disabled_at", OffsetDateTime.class),
            rs.getString("given_name"),
            rs.getString("family_name"),
            rs.getObject("name_confirmed_at", OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    JdbcUserIdentityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserIdentity recordLogin(String issuer, String subject, String email, String displayName) {
        return recordLogin(issuer, subject, email, displayName, false);
    }

    @Override
    @Transactional
    public UserIdentity recordLogin(String issuer, String subject, String email, String displayName, boolean emailVerified) {
        /* A provider sign-in is a login to a person, not a new person. When (issuer, subject) is already a known
           login, or the provider vouches for an email that names exactly one person, the sign-in lands on them;
           anything else is a brand-new person, with this login as its only one so far. */
        Long userId = findUserIdByLogin(issuer, subject);
        if (userId == null && emailVerified && email != null && !email.isBlank()) {
            List<Long> matches = jdbcTemplate.queryForList(
                    "SELECT id FROM user_identity WHERE lower(email) = lower(?)", Long.class, email);
            if (matches.size() == 1) {
                userId = matches.get(0);
                jdbcTemplate.update(
                        "INSERT INTO user_login (issuer, subject, user_id) VALUES (?, ?, ?) ON CONFLICT (issuer, subject) DO NOTHING",
                        issuer, subject, userId);
            }
        }
        if (userId != null) {
            TenantContext.setCurrentUser(jdbcTemplate, userId);
            return jdbcTemplate.queryForObject(
                    """
                    UPDATE user_identity
                    SET email = COALESCE(?, email), display_name = COALESCE(?, display_name), last_login_at = now()
                    WHERE id = ?
                    RETURNING id, issuer, subject, email, display_name, created_at, last_login_at, disabled_at,
                        given_name, family_name, name_confirmed_at
                    """,
                    ROW_MAPPER,
                    email,
                    displayName,
                    userId);
        }
        // ON CONFLICT makes first-login-creates / later-logins-update a single
        // atomic statement, safe under concurrent logins from the same
        // identity racing each other, rather than a separate check-then-write.
        UserIdentity created = jdbcTemplate.queryForObject(
                """
                INSERT INTO user_identity (issuer, subject, email, display_name)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (issuer, subject)
                DO UPDATE SET email = EXCLUDED.email, display_name = EXCLUDED.display_name, last_login_at = now()
                RETURNING id, issuer, subject, email, display_name, created_at, last_login_at, disabled_at,
                    given_name, family_name, name_confirmed_at
                """,
                ROW_MAPPER,
                issuer,
                subject,
                email,
                displayName);
        jdbcTemplate.update(
                "INSERT INTO user_login (issuer, subject, user_id) VALUES (?, ?, ?) ON CONFLICT (issuer, subject) DO NOTHING",
                issuer, subject, created.id());
        return created;
    }

    private Long findUserIdByLogin(String issuer, String subject) {
        List<Long> hits = jdbcTemplate.queryForList(
                "SELECT user_id FROM user_login WHERE issuer = ? AND subject = ?", Long.class, issuer, subject);
        return hits.isEmpty() ? null : hits.get(0);
    }

    @Override
    public UserIdentity recordName(long userId, String givenName, String familyName) {
        return jdbcTemplate.queryForObject(
                """
                UPDATE user_identity
                SET given_name = ?, family_name = ?, name_confirmed_at = now()
                WHERE id = ?
                RETURNING id, issuer, subject, email, display_name, created_at, last_login_at, disabled_at,
                    given_name, family_name, name_confirmed_at
                """,
                ROW_MAPPER,
                givenName,
                familyName,
                userId);
    }

    @Override
    public UserIdentity prefillNames(long userId, String givenName, String familyName) {
        // A confirmed name is deliberately untouched, so the update writes
        // no row; it must not be asked for one -- the identity is then read
        // back separately, or the next sign-in would fail on the absence.
        jdbcTemplate.update(
                """
                UPDATE user_identity
                SET given_name = COALESCE(given_name, ?), family_name = COALESCE(family_name, ?)
                WHERE id = ? AND name_confirmed_at IS NULL
                """,
                givenName,
                familyName,
                userId);
        return jdbcTemplate.queryForObject(
                """
                SELECT id, issuer, subject, email, display_name, created_at, last_login_at, disabled_at,
                    given_name, family_name, name_confirmed_at
                FROM user_identity
                WHERE id = ?
                """,
                ROW_MAPPER,
                userId);
    }

    @Override
    public Optional<UserIdentity> findByIssuerAndSubject(String issuer, String subject) {
        List<UserIdentity> results = jdbcTemplate.query(
                "SELECT ui.id, ui.issuer, ui.subject, ui.email, ui.display_name, ui.created_at, ui.last_login_at, ui.disabled_at, "
                        + "ui.given_name, ui.family_name, ui.name_confirmed_at "
                        + "FROM user_identity ui JOIN user_login ul ON ul.user_id = ui.id "
                        + "WHERE ul.issuer = ? AND ul.subject = ?",
                ROW_MAPPER,
                issuer,
                subject);
        return results.stream().findFirst();
    }

    @Override
    @Transactional
    public void recordInvitationRequest(long userId, String note) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO invitation_request (user_id, note)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET note = EXCLUDED.note
                """,
                userId,
                note);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasInvitationRequest(long userId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM invitation_request WHERE user_id = ?)", Boolean.class, userId));
    }
}
