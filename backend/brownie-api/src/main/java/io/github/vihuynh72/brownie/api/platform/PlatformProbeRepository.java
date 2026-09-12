package io.github.vihuynh72.brownie.api.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Plain, explicit SQL against the runtime {@code brownie_api} connection --
 * no ORM, matching the stack's own choice of Spring JDBC over a second
 * persistence framework. Exists solely to back {@link
 * PlatformProbeController}'s demonstration endpoints.
 */
@Repository
class PlatformProbeRepository {

    private final JdbcTemplate jdbcTemplate;

    PlatformProbeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    long insert(String message) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(
                connection -> {
                    // Naming the key column explicitly, rather than the more common
                    // Statement.RETURN_GENERATED_KEYS, matters specifically on Postgres:
                    // its driver returns every column of the inserted row for
                    // RETURN_GENERATED_KEYS, not just the key, which makes
                    // KeyHolder#getKey() fail with "multiple keys" the moment a table
                    // has more than one column -- caught by actually running this
                    // insert, not by the code merely compiling.
                    PreparedStatement statement =
                            connection.prepareStatement("INSERT INTO platform_probe (message) VALUES (?)", new String[] {
                                "id"
                            });
                    statement.setString(1, message);
                    return statement;
                },
                keyHolder);
        return keyHolder.getKey().longValue();
    }

    Optional<PlatformProbe> findById(long id) {
        List<PlatformProbe> results = jdbcTemplate.query(
                "SELECT id, message, created_at FROM platform_probe WHERE id = ?",
                (rs, rowNum) -> new PlatformProbe(
                        rs.getLong("id"), rs.getString("message"), rs.getObject("created_at", OffsetDateTime.class)),
                id);
        return results.stream().findFirst();
    }

    record PlatformProbe(long id, String message, OffsetDateTime createdAt) {
    }
}
