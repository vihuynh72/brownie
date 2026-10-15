package io.github.vihuynh72.brownie.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;

/**
 * How the JDBC session store writes attributes. Two threads can commit
 * the same session at once: a streaming response crossing its buffer
 * commits the session from inside {@code out.write} while the request
 * thread commits it again at the end of the initial dispatch, and both
 * saves see an attribute added since either loaded the session as new.
 * The repository's stock insert then collides on
 * (SESSION_PRIMARY_ID, ATTRIBUTE_NAME) and the failure is thrown from
 * inside the stream's own write, so the chunk it carried is lost. The
 * PostgreSQL-specific customizer switches that insert to an upsert, so
 * the second commit updates the row instead of failing mid-response.
 */
@Configuration
class SessionStoreConfig {

    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> postgreSqlSessionRepositoryCustomizer() {
        return new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }
}
