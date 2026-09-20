package io.github.vihuynh72.brownie.api.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseUnavailableFilterTest {

    private final DatabaseUnavailableFilter filter = new DatabaseUnavailableFilter();

    @Test
    void aSessionLookupThatCannotReachTheDatabaseIsAnsweredWithTryAgainShortlyInTheApisOwnErrorShape() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("X-Correlation-Id", "from-the-caller-42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // What the session filter throws when the pool gives up: the pool's own exception, wrapped twice.
        FilterChain databaseIsDown = (req, res) -> {
            throw new ServletException(new CannotGetJdbcConnectionException(
                    "Failed to obtain JDBC Connection", new SQLTransientConnectionException("Connection is not available, request timed out after 5000ms")));
        };

        filter.doFilter(request, response, databaseIsDown);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentType()).startsWith("application/problem+json");
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("correlationId").asText()).isEqualTo("from-the-caller-42");
        assertThat(body.get("fields").isArray()).isTrue();
        assertThat(body.get("recoveryActions").isArray()).isTrue();
        // Nothing about where the database is or what was being asked of it.
        assertThat(response.getContentAsString()).doesNotContainIgnoringCase("jdbc").doesNotContain("5000ms").doesNotContainIgnoringCase("select");
    }

    @Test
    void aCorrelationIdThatCouldNotSafelyBeEchoedIsReplacedNotRepeated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.addHeader("X-Correlation-Id", "\"},\"injected\":\"yes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new CannotGetJdbcConnectionException("down");
        });

        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertThat(body.has("injected")).isFalse();
        assertThat(body.get("correlationId").asText()).matches("[0-9a-f-]{36}");
    }

    /**
     * What really arrived when the database was stopped under a running
     * API: the connection died mid-transaction, the rollback failed, and
     * the failed rollback is what was thrown. The outage itself is only
     * reachable through the "application exception" carried beside it.
     */
    @Test
    void aConnectionCutUnderATransactionIsRecognisedThroughTheFailedRollbackThatHidesIt() throws Exception {
        org.springframework.transaction.TransactionSystemException rollbackFailed =
                new org.springframework.transaction.TransactionSystemException("JDBC rollback failed", new java.sql.SQLException("Connection is closed"));
        rollbackFailed.initApplicationException(new org.springframework.dao.DataAccessResourceFailureException(
                "PreparedStatementCallback", new java.sql.SQLException("terminating connection due to administrator command", "57P01")));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), response, (req, res) -> {
            throw rollbackFailed;
        });

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(DatabaseUnavailableFilter.isDatabaseUnreachable(new IllegalStateException(new java.sql.SQLException("server closed", "08006")))).isTrue();
        assertThat(DatabaseUnavailableFilter.isDatabaseUnreachable(new IllegalStateException(new java.sql.SQLException("admin shutdown", "57P01")))).isTrue();
        assertThat(DatabaseUnavailableFilter.isDatabaseUnreachable(new IllegalStateException(new java.sql.SQLException("unique violation", "23505")))).isFalse();
    }

    /** The server's second pass for the same failed request must be answered here as well, not by its bare error page. */
    @Test
    void anErrorDispatchIsFilteredToo() {
        assertThat(filter.shouldNotFilterErrorDispatch()).isFalse();
    }

    @Test
    void aStatementTheDatabaseRejectedIsNotAnOutageAndKeepsItsOrdinaryHandling() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/workspaces/1/documents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        DataIntegrityViolationException rejected = new DataIntegrityViolationException("violates check constraint");

        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw rejected;
        })).isSameAs(rejected);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aRequestThatSucceedsIsLeftAlone() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), response, (req, res) -> ((MockHttpServletResponse) res).setStatus(204));
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsString()).isEmpty();
    }
}
