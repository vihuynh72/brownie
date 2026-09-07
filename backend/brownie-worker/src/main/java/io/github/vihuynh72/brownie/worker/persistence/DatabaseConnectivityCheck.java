package io.github.vihuynh72.brownie.worker.persistence;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens and immediately closes one connection from the runtime
 * {@link DataSource} as the very first thing the application does after
 * startup.
 *
 * <p>Without this, a wrong {@code BROWNIE_DB_PASSWORD} goes completely
 * unnoticed: Spring Boot's connection pool builds itself lazily, so the
 * application reports "Started" successfully and only discovers the bad
 * credential on the first real query, far away from the actual
 * misconfiguration. This turns that into the same fail-fast, fail-clear
 * behavior already required of {@code BROWNIE_ENVIRONMENT}.
 *
 * <p>Takes an {@link ObjectProvider} rather than a required {@link
 * DataSource}: {@code @ConditionalOnBean} on a plain, component-scanned
 * bean is evaluated before auto-configured beans such as the datasource
 * exist, so it cannot reliably tell whether one will end up present --
 * this checks at the one moment ({@link #run}) where that is actually
 * known, and does nothing when the fast, Docker-free context-shape test
 * deliberately excludes datasource autoconfiguration.
 */
@Component
class DatabaseConnectivityCheck implements ApplicationRunner {

    private final ObjectProvider<DataSource> dataSource;

    DatabaseConnectivityCheck(ObjectProvider<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        DataSource actual = dataSource.getIfAvailable();
        if (actual == null) {
            return;
        }
        try (Connection connection = actual.getConnection()) {
            connection.isValid(5);
        }
    }
}
