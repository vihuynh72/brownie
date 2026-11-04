package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.form.CatalogReadyRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** {@link CatalogReadyRepository} on {@code catalog_form_source}. */
@Repository
class JdbcCatalogReadyRepository implements CatalogReadyRepository {

    private static final RowMapper<ReadyForm> ROW_MAPPER = (rs, n) -> new ReadyForm(
            rs.getString("catalog_id"),
            rs.getBytes("source_bytes"),
            rs.getString("source_sha256"),
            rs.getObject("checked_at", java.time.OffsetDateTime.class));

    private final JdbcTemplate jdbcTemplate;

    JdbcCatalogReadyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ReadyForm> find(String catalogId) {
        List<ReadyForm> results = jdbcTemplate.query(
                "SELECT catalog_id, source_bytes, source_sha256, checked_at "
                        + "FROM catalog_form_source WHERE catalog_id = ?",
                ROW_MAPPER,
                catalogId);
        return results.stream().findFirst();
    }

    @Override
    public void remember(ReadyForm ready) {
        jdbcTemplate.update(
                """
                INSERT INTO catalog_form_source (catalog_id, source_bytes, source_sha256, checked_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (catalog_id)
                DO UPDATE SET source_bytes = EXCLUDED.source_bytes, source_sha256 = EXCLUDED.source_sha256,
                    checked_at = EXCLUDED.checked_at
                """,
                ready.catalogId(), ready.sourceBytes(), ready.sourceSha256());
    }

    @Override
    public void restamp(String catalogId) {
        jdbcTemplate.update("UPDATE catalog_form_source SET checked_at = now() WHERE catalog_id = ?", catalogId);
    }
}
