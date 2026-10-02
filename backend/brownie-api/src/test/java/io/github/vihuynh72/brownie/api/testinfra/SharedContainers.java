package io.github.vihuynh72.brownie.api.testinfra;

import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * One Postgres, one Azurite and one ClamAV for every test class in this
 * JVM, instead of a new set for each class. Each is started the first time
 * a test asks for it, so a run that never scans a file never starts ClamAV.
 * Nothing here stops them: Testcontainers' own cleanup container removes
 * all three once the JVM has exited, after every cached Spring context has
 * closed its pool against a database that is still there.
 *
 * <p>A test class still gets what it had with containers of its own. It
 * declares {@code static final TestDatabase DB = SharedContainers.newDatabase();}
 * and gets a database no other class uses, copied from the one the init
 * script prepared, reached with the same three least-privilege roles and
 * migrated by its own Flyway run; and, through the same handle, a storage
 * account no other class uses. ClamAV keeps nothing between scans, so every
 * class uses the same one as it is.
 */
public final class SharedContainers {

    /**
     * The cluster's initial superuser, as the init script describes it. No
     * application connects as it; {@link TestDatabase#superuserConnection()}
     * is for a test that needs a superuser's view.
     */
    static final String SUPERUSER = "postgres";
    static final String SUPERUSER_PASSWORD = "postgres_bootstrap_only";

    /**
     * The database the init script prepares. Every test database is a copy
     * of it, and Postgres refuses to copy a database anyone is connected to,
     * so nothing ever connects to it: it is closed to connections as soon as
     * the container is up, and the copies are made from the maintenance
     * database.
     */
    private static final String TEMPLATE_DATABASE = "brownie";
    private static final String MAINTENANCE_DATABASE = "postgres";

    private static final int CLAMD_PORT = 3310;

    /** Azurite's published development key, shared by every account below; it opens nothing outside this disposable container. */
    private static final String AZURITE_ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    /**
     * Azurite serves only the accounts it was started with, so it starts
     * with enough for every test class in one run to have its own and room
     * to spare. Classes cannot share one: object keys are built from row ids
     * (a job's pending questions, a staged output), every new database
     * numbers its rows from one again, and the deletion record is read by
     * listing its whole container.
     */
    private static final int AZURITE_ACCOUNTS = 200;

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final AtomicInteger STORAGE_ACCOUNTS = new AtomicInteger();

    private SharedContainers() {
    }

    /**
     * A database of the calling class's own. It is created the first time
     * anything asks for its name or address, so declaring one in a static
     * field starts nothing: a class that is skipped, or left out by tag,
     * never reaches Docker.
     */
    public static TestDatabase newDatabase() {
        return new TestDatabase("brownie_" + DATABASES.incrementAndGet());
    }

    /**
     * The connection string of a storage account on the shared Azurite that
     * no other caller gets, for a test that needs storage without a
     * database. A class with a {@link TestDatabase} uses
     * {@link TestDatabase#azuriteConnectionString()} instead.
     */
    public static String newAzuriteAccount() {
        int account = STORAGE_ACCOUNTS.incrementAndGet();
        if (account > AZURITE_ACCOUNTS) {
            throw new IllegalStateException("Azurite was started with " + AZURITE_ACCOUNTS
                    + " storage accounts and every one is taken; start it with more.");
        }
        return Azurite.CONTAINER.getConnectionString(azuriteAccountName(account), AZURITE_ACCOUNT_KEY);
    }

    /** The shared ClamAV's host, for {@code brownie.security.clamav.host}. */
    public static String clamAvHost() {
        return ClamAv.CONTAINER.getHost();
    }

    /** The shared ClamAV's port, for {@code brownie.security.clamav.port}. */
    public static int clamAvPort() {
        return ClamAv.CONTAINER.getMappedPort(CLAMD_PORT);
    }

    /** Creates {@code name} as a copy of the prepared database, reachable by the same roles as the original. */
    static synchronized void createDatabase(String name) {
        try (Connection maintenance = DriverManager.getConnection(
                        jdbcUrl(Postgres.CONTAINER, MAINTENANCE_DATABASE), SUPERUSER, SUPERUSER_PASSWORD);
                Statement statement = maintenance.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE_DATABASE);
            // The copy keeps the schema's owner and grants and the default
            // privileges, but a database's own grants are not copied: this
            // repeats the init script's CONNECT grant.
            statement.execute("GRANT CONNECT ON DATABASE " + name + " TO brownie_api, brownie_worker");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the test database " + name + ".", e);
        }
    }

    static String jdbcUrl(String database) {
        return jdbcUrl(Postgres.CONTAINER, database);
    }

    static ExecResult execInPostgres(String... command) throws IOException, InterruptedException {
        return Postgres.CONTAINER.execInContainer(command);
    }

    /** The same form as Testcontainers' own address for the container's default database. */
    private static String jdbcUrl(PostgreSQLContainer postgres, String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/" + database + "?loggerLevel=OFF";
    }

    private static String azuriteAccountName(int account) {
        return "brownie" + account;
    }

    /** Held apart so the container starts only when a test first needs it. */
    private static final class Postgres {

        static final PostgreSQLContainer CONTAINER = start();

        private static PostgreSQLContainer start() {
            PostgreSQLContainer container = new PostgreSQLContainer("postgres:17")
                    .withDatabaseName(TEMPLATE_DATABASE)
                    .withUsername(SUPERUSER)
                    .withPassword(SUPERUSER_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(initScriptPath()), "/docker-entrypoint-initdb.d/01-app-roles.sql")
                    // fsync is off, as in Testcontainers' own default command.
                    // Every cached Spring context keeps a pool open against
                    // its own database, so the cluster takes more than the
                    // default hundred connections.
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=400");
            container.start();
            try (Connection maintenance = DriverManager.getConnection(
                            jdbcUrl(container, MAINTENANCE_DATABASE), SUPERUSER, SUPERUSER_PASSWORD);
                    Statement statement = maintenance.createStatement()) {
                // A stray connection to the original would make every later
                // copy fail; refusing it makes the mistake show where it is made.
                statement.execute("ALTER DATABASE " + TEMPLATE_DATABASE + " WITH ALLOW_CONNECTIONS false");
            } catch (SQLException e) {
                throw new IllegalStateException("Could not close the prepared database to connections.", e);
            }
            return container;
        }

        private static Path initScriptPath() {
            return Path.of("").toAbsolutePath()
                    .getParent()
                    .getParent()
                    .resolve("infra/local/postgres/init/01-app-roles.sql");
        }
    }

    /** Held apart so the container starts only when a test first needs it. */
    private static final class Azurite {

        static final AzuriteContainer CONTAINER = start();

        private static AzuriteContainer start() {
            String accounts = IntStream.rangeClosed(1, AZURITE_ACCOUNTS)
                    .mapToObj(account -> azuriteAccountName(account) + ":" + AZURITE_ACCOUNT_KEY)
                    .collect(Collectors.joining(";"));
            AzuriteContainer container = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.37.0")
                    .withEnv("AZURITE_ACCOUNTS", accounts);
            container.start();
            return container;
        }
    }

    /** Held apart so the container starts only when a test first needs it. */
    private static final class ClamAv {

        static final GenericContainer<?> CONTAINER = start();

        private static GenericContainer<?> start() {
            GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse("clamav/clamav-debian:1.4"))
                    .withExposedPorts(CLAMD_PORT)
                    .waitingFor(Wait.forLogMessage(".*socket found, clamd started\\.\\n", 1))
                    .withStartupTimeout(Duration.ofMinutes(3));
            container.start();
            return container;
        }
    }
}
