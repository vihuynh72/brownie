package io.github.vihuynh72.brownie.api.persistence.jdbc;

import io.github.vihuynh72.brownie.core.evidence.EvidenceLocator;
import io.github.vihuynh72.brownie.core.evidence.SourceSpan;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanRepository;
import io.github.vihuynh72.brownie.core.identity.UserIdentity;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Round-trips all three {@link EvidenceLocator} shapes through real
 * {@code jsonb}, and proves row-level security, against a real,
 * disposable Postgres -- the hand-written type-discriminated JSON in
 * {@code JdbcSourceSpanRepository} is exactly the part worth proving
 * against a real column, not just trusting it compiles.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
class JdbcSourceSpanRepositoryTest {

    private static final String BOOTSTRAP_PASSWORD = "postgres_bootstrap_only";
    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("brownie")
            .withUsername("postgres")
            .withPassword(BOOTSTRAP_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    private static Path initScriptPath() {
        return Path.of("").toAbsolutePath()
                .getParent()
                .getParent()
                .resolve("infra/local/postgres/init/01-app-roles.sql");
    }

    @Autowired
    private SourceSpanRepository sourceSpanRepository;

    @Autowired
    private SourceSnapshotRepository sourceSnapshotRepository;

    @Autowired
    private UserIdentityRepository userIdentityRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private DataSource dataSource;

    @Test
    void roundTripsADocxLocatorExactly() {
        Fixture fixture = new Fixture("subject-span-docx");
        EvidenceLocator.Docx locator = new EvidenceLocator.Docx("word/document.xml", "p0/r0", 0, 7);

        SourceSpan saved = sourceSpanRepository.create(
                fixture.workspaceId, fixture.userId, fixture.snapshotId, "brownie-docx-graph-v1", locator, "deadbeef");
        SourceSpan reloaded = sourceSpanRepository.find(fixture.workspaceId, fixture.userId, saved.id()).orElseThrow();

        assertThat(reloaded).isEqualTo(saved);
        assertThat(reloaded.locator()).isEqualTo(locator);
        assertThat(reloaded.extractionParserVersion()).isEqualTo("brownie-docx-graph-v1");
        assertThat(reloaded.excerptHash()).isEqualTo("deadbeef");
    }

    @Test
    void roundTripsAPdfLocatorExactly() {
        Fixture fixture = new Fixture("subject-span-pdf");
        EvidenceLocator.Pdf locator = new EvidenceLocator.Pdf(2, 3, 5, 20);

        SourceSpan saved =
                sourceSpanRepository.create(fixture.workspaceId, fixture.userId, fixture.snapshotId, "brownie-pdf-graph-v1", locator, "cafef00d");
        SourceSpan reloaded = sourceSpanRepository.find(fixture.workspaceId, fixture.userId, saved.id()).orElseThrow();

        assertThat(reloaded.locator()).isEqualTo(locator);
    }

    @Test
    void roundTripsAPlainTextLocatorExactly() {
        Fixture fixture = new Fixture("subject-span-text");
        EvidenceLocator.PlainText locator = new EvidenceLocator.PlainText(10, 42);

        SourceSpan saved = sourceSpanRepository.create(
                fixture.workspaceId, fixture.userId, fixture.snapshotId, "brownie-plain-text-graph-v1", locator, "0123abcd");
        SourceSpan reloaded = sourceSpanRepository.find(fixture.workspaceId, fixture.userId, saved.id()).orElseThrow();

        assertThat(reloaded.locator()).isEqualTo(locator);
    }

    @Test
    void findingANonexistentSpanIsEmpty() {
        Fixture fixture = new Fixture("subject-span-none");
        assertThat(sourceSpanRepository.find(fixture.workspaceId, fixture.userId, 999_999L)).isEmpty();
    }

    @Test
    void oneUsersContextCannotReadAnotherWorkspacesSpan() {
        Fixture fixtureA = new Fixture("subject-span-rls-a");
        Fixture fixtureB = new Fixture("subject-span-rls-b");
        SourceSpan span = sourceSpanRepository.create(
                fixtureB.workspaceId, fixtureB.userId, fixtureB.snapshotId, "v1", new EvidenceLocator.PlainText(0, 1), "hash");

        assertThat(sourceSpanRepository.find(fixtureB.workspaceId, fixtureA.userId, span.id())).isEmpty();
    }

    @Test
    void aNonMemberCannotInsertASpanIntoAnotherWorkspace() throws SQLException {
        Fixture fixtureA = new Fixture("subject-span-rls-insert-a");
        Fixture fixtureB = new Fixture("subject-span-rls-insert-b");

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setLocalContext(connection, fixtureA.userId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO source_span (workspace_id, source_snapshot_id, extraction_parser_version, locator, excerpt_hash) "
                            + "VALUES (?, ?, 'v1', '{\"type\":\"PLAIN_TEXT\",\"startCodePoint\":0,\"endCodePointExclusive\":1}'::jsonb, 'hash')")) {
                statement.setLong(1, fixtureB.workspaceId);
                statement.setLong(2, fixtureB.snapshotId);
                statement.executeUpdate();
                fail("expected the insert to be rejected by row-level security");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).containsIgnoringCase("row-level security");
            }
            connection.rollback();
        }
    }

    private void setLocalContext(Connection connection, long userId) throws SQLException {
        try (PreparedStatement setContext =
                connection.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
            setContext.setString(1, String.valueOf(userId));
            setContext.executeQuery();
        }
    }

    /** A ready-to-use workspace, user, artifact, and attached snapshot -- the common setup every test here needs. */
    private final class Fixture {
        final long userId;
        final long workspaceId;
        final long artifactId;
        final long snapshotId;

        Fixture(String subject) {
            UserIdentity user = userIdentityRepository.recordLogin("https://issuer-source-span-tests", subject, null, null);
            this.userId = user.id();
            this.workspaceId = workspaceRepository.ensurePersonalWorkspace(userId).id();
            this.artifactId = insertArtifact(workspaceId, userId);
            SourceSnapshot snapshot = sourceSnapshotRepository.create(workspaceId, userId, artifactId, SourceKind.ARTIFACT);
            this.snapshotId = snapshot.id();
        }

        private long insertArtifact(long workspaceId, long userId) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                setLocalContext(connection, userId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO artifact (workspace_id, blob_key, status, byte_count, detected_media_type) "
                                + "VALUES (?, ?, 'READY', 100, 'PLAIN_TEXT') RETURNING id")) {
                    statement.setLong(1, workspaceId);
                    statement.setString(2, "test-blob-" + UUID.randomUUID());
                    try (var resultSet = statement.executeQuery()) {
                        resultSet.next();
                        long id = resultSet.getLong(1);
                        connection.commit();
                        return id;
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
