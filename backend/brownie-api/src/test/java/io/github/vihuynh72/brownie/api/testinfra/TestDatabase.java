package io.github.vihuynh72.brownie.api.testinfra;

import org.testcontainers.containers.Container.ExecResult;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * One test class's own database on the shared Postgres, and its own
 * storage account on the shared Azurite, from
 * {@link SharedContainers#newDatabase()}. The database is created the first
 * time its name or address is asked for, and is never dropped: a cached
 * Spring context still holding a pool against it can close that pool
 * quickly whenever it is evicted.
 *
 * <p>The application's roles reach it exactly as they reached a container
 * of the class's own: {@code brownie_api}, {@code brownie_worker} and
 * {@code brownie_migration}, with the passwords the init script gives them
 * and row-level security in force.
 */
public final class TestDatabase {

    private final String name;
    private boolean created;
    private String azuriteConnectionString;

    TestDatabase(String name) {
        this.name = name;
    }

    /** The database's name, for a tool that takes one, such as pg_dump's {@code -d}. */
    public String name() {
        ensureCreated();
        return name;
    }

    /** The database's address, for {@code spring.datasource.url}, {@code spring.flyway.url} or a test's own connection as one of the application's roles. */
    public String jdbcUrl() {
        ensureCreated();
        return SharedContainers.jdbcUrl(name);
    }

    /**
     * A connection to this database as the cluster's superuser. It bypasses
     * row-level security, so it is only ever a test's own view, never the
     * application's. The cluster is shared, so a query of
     * {@code pg_stat_activity} through it sees every class's sessions and
     * narrows itself with {@code datname = current_database()}.
     */
    public Connection superuserConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), SharedContainers.SUPERUSER, SharedContainers.SUPERUSER_PASSWORD);
    }

    /**
     * Runs a command inside the shared Postgres container, such as a
     * {@code pg_dump} of {@link #name()}. Every class shares that
     * container's file system too, so a file a command writes there carries
     * this database's name.
     */
    public ExecResult execInContainer(String... command) throws IOException, InterruptedException {
        ensureCreated();
        return SharedContainers.execInPostgres(command);
    }

    /** This class's own storage account on the shared Azurite, for {@code brownie.storage.local-connection}. */
    public synchronized String azuriteConnectionString() {
        if (azuriteConnectionString == null) {
            azuriteConnectionString = SharedContainers.newAzuriteAccount();
        }
        return azuriteConnectionString;
    }

    private synchronized void ensureCreated() {
        if (!created) {
            SharedContainers.createDatabase(name);
            created = true;
        }
    }
}
