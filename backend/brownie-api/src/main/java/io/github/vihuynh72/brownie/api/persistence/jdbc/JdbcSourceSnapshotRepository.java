package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.source.SourceConversion;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.source.SourceOrigin;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * {@code create} is insert-or-return-existing under the table's own {@code
 * UNIQUE (artifact_id)} constraint, the same reasoning the extraction-
 * version repositories already apply: two callers attaching the same
 * artifact as a source concurrently both converge on the one real snapshot
 * that fact deserves, rather than one silently overwriting the other's.
 *
 * <p>{@code createImported} does the same for a copied source, with one more
 * rule: the connection and the choice it was read through must still be open
 * when the row is written. Both are locked for the rest of the transaction
 * before the row is written, the connection first and then the choice, the
 * order a disconnect changes them in, so a disconnect either finished first
 * (and nothing is kept) or waits until this copy is recorded. The copy is
 * linked to its document in the same transaction, so a document deleted for
 * good meanwhile undoes the copy too instead of leaving it linked to nothing:
 * the link's reference to the document waits for a deletion already under
 * way and fails if it went through.
 */
@Repository
class JdbcSourceSnapshotRepository implements SourceSnapshotRepository {

    private static final String SELECT_COLUMNS = "id, workspace_id, artifact_id, kind, fetched_at, origin_connection_id, origin_grant_id,"
            + " origin_external_id, origin_revision, origin_modified_at, origin_title, origin_link, origin_conversion";

    private final JdbcTemplate jdbcTemplate;

    JdbcSourceSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceSnapshot> find(long workspaceId, long userId, long snapshotId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM source_snapshot WHERE id = ? AND workspace_id = ?",
                        this::mapRow,
                        snapshotId,
                        workspaceId)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceSnapshot> findByArtifact(long workspaceId, long userId, long artifactId) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return findByArtifactInTransaction(workspaceId, artifactId);
    }

    @Override
    @Transactional
    public SourceSnapshot create(long workspaceId, long userId, long artifactId, SourceKind kind) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        jdbcTemplate.update(
                """
                INSERT INTO source_snapshot (workspace_id, artifact_id, kind)
                VALUES (?, ?, ?)
                ON CONFLICT (artifact_id) DO NOTHING
                """,
                workspaceId,
                artifactId,
                kind.name());
        return findByArtifact(workspaceId, userId, artifactId)
                .orElseThrow(() -> new IllegalStateException("Source snapshot for artifact " + artifactId + " vanished after saving it."));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceSnapshot> findImported(long workspaceId, long userId, long grantId, String externalId, String revision) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return findImportedInTransaction(workspaceId, grantId, externalId, revision);
    }

    @Override
    @Transactional
    public Optional<SourceSnapshot> createImported(
            long workspaceId, long userId, long documentId, long artifactId, SourceKind kind, SourceOrigin origin, Instant fetchedAt) {
        String grantType = switch (kind) {
            case GOOGLE_CALENDAR -> "CALENDAR";
            case ARTIFACT -> throw new IllegalArgumentException("An upload has no origin elsewhere.");
        };
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        // Two imports of the same thing through the same choice take turns, so the second finds the first's copy.
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(hashtextextended('source_snapshot_origin:' || ?::text || ':' || ?, 0))",
                rs -> null,
                origin.grantId(),
                origin.externalId());
        // The connection, and then the choice, must be open, and stay so until this transaction ends.
        boolean connected = !jdbcTemplate
                .queryForList(
                        "SELECT id FROM connector_connection WHERE workspace_id = ? AND id = ? AND state <> 'DISCONNECTED' FOR SHARE",
                        Long.class,
                        workspaceId,
                        origin.connectionId())
                .isEmpty();
        if (!connected) {
            return Optional.empty();
        }
        boolean open = !jdbcTemplate
                .queryForList(
                        "SELECT id FROM connector_resource_grant"
                                + " WHERE workspace_id = ? AND id = ? AND connection_id = ? AND resource_type = ? AND revoked_at IS NULL"
                                + " FOR SHARE",
                        Long.class,
                        workspaceId,
                        origin.grantId(),
                        origin.connectionId(),
                        grantType)
                .isEmpty();
        if (!open) {
            return Optional.empty();
        }
        SourceSnapshot snapshot = findImportedInTransaction(workspaceId, origin.grantId(), origin.externalId(), origin.revision())
                .orElseGet(() -> insertImported(workspaceId, userId, artifactId, kind, origin, fetchedAt));
        boolean documentExists = !jdbcTemplate
                .queryForList("SELECT id FROM document WHERE workspace_id = ? AND id = ?", Long.class, workspaceId, documentId)
                .isEmpty();
        if (!documentExists) {
            // Deleted for good while the copy was being made: undone with everything above.
            throw new DocumentNotFoundException(documentId);
        }
        jdbcTemplate.update(
                """
                INSERT INTO document_source (workspace_id, document_id, source_snapshot_id, attached_by_user_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (workspace_id, document_id, source_snapshot_id) DO NOTHING
                """,
                workspaceId,
                documentId,
                snapshot.id(),
                userId);
        return Optional.of(snapshot);
    }

    private SourceSnapshot insertImported(
            long workspaceId, long userId, long artifactId, SourceKind kind, SourceOrigin origin, Instant fetchedAt) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO source_snapshot (workspace_id, artifact_id, kind, fetched_at, origin_connection_id, origin_grant_id,
                    origin_external_id, origin_revision, origin_modified_at, origin_title, origin_link, origin_conversion)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (artifact_id) DO NOTHING
                """,
                workspaceId,
                artifactId,
                kind.name(),
                fetchedAt.atOffset(ZoneOffset.UTC),
                origin.connectionId(),
                origin.grantId(),
                origin.externalId(),
                origin.revision(),
                origin.modifiedAt(),
                origin.title(),
                origin.link(),
                origin.conversion() == null ? null : origin.conversion().name());
        if (inserted == 0) {
            throw new IllegalStateException("Artifact " + artifactId + " is already a source; a copy is always a new artifact.");
        }
        SourceSnapshot saved = findByArtifactInTransaction(workspaceId, artifactId)
                .orElseThrow(() -> new IllegalStateException("Source snapshot for artifact " + artifactId + " vanished after saving it."));
        // Which connection and choice, never what it was called: an audit row names no titles.
        AuditWriter.append(
                jdbcTemplate, workspaceId, userId, "SOURCE_IMPORTED", "source-snapshot", saved.id(),
                "{\"kind\":\"" + kind.name() + "\",\"connectionId\":" + origin.connectionId() + ",\"grantId\":" + origin.grantId()
                        + ",\"conversion\":" + (origin.conversion() == null ? "null" : "\"" + origin.conversion().name() + "\"") + "}");
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceSnapshot> findLatestImportedWithContent(
            long workspaceId, long userId, long grantId, String externalId, String sha256Hex) {
        TenantContext.setCurrentUser(jdbcTemplate, userId);
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM ("
                                + "SELECT " + prefixed("s") + ", a.sha256 AS content_sha256 FROM source_snapshot s"
                                + " JOIN artifact a ON a.workspace_id = s.workspace_id AND a.id = s.artifact_id"
                                + " WHERE s.workspace_id = ? AND s.origin_grant_id = ? AND s.origin_external_id = ?"
                                + " ORDER BY s.fetched_at DESC, s.id DESC LIMIT 1) latest"
                                + " WHERE latest.content_sha256 = ?",
                        this::mapRow,
                        workspaceId,
                        grantId,
                        externalId,
                        sha256Hex)
                .stream()
                .findFirst();
    }

    private static String prefixed(String alias) {
        return alias + "." + SELECT_COLUMNS.replace(", ", ", " + alias + ".");
    }

    private Optional<SourceSnapshot> findByArtifactInTransaction(long workspaceId, long artifactId) {
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM source_snapshot WHERE artifact_id = ? AND workspace_id = ?",
                        this::mapRow,
                        artifactId,
                        workspaceId)
                .stream()
                .findFirst();
    }

    private Optional<SourceSnapshot> findImportedInTransaction(long workspaceId, long grantId, String externalId, String revision) {
        return jdbcTemplate
                .query(
                        "SELECT " + SELECT_COLUMNS + " FROM source_snapshot"
                                + " WHERE workspace_id = ? AND origin_grant_id = ? AND origin_external_id = ? AND origin_revision = ?",
                        this::mapRow,
                        workspaceId,
                        grantId,
                        externalId,
                        revision)
                .stream()
                .findFirst();
    }

    private SourceSnapshot mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long connectionId = rs.getLong("origin_connection_id");
        SourceOrigin origin = null;
        if (!rs.wasNull()) {
            String conversion = rs.getString("origin_conversion");
            origin = new SourceOrigin(
                    connectionId,
                    rs.getLong("origin_grant_id"),
                    rs.getString("origin_external_id"),
                    rs.getString("origin_revision"),
                    rs.getObject("origin_modified_at", OffsetDateTime.class),
                    rs.getString("origin_title"),
                    rs.getString("origin_link"),
                    conversion == null ? null : SourceConversion.valueOf(conversion));
        }
        return new SourceSnapshot(
                rs.getLong("id"),
                rs.getLong("workspace_id"),
                rs.getLong("artifact_id"),
                SourceKind.valueOf(rs.getString("kind")),
                rs.getObject("fetched_at", OffsetDateTime.class),
                origin);
    }
}
