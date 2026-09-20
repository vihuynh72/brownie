package io.github.vihuynh72.brownie.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;

/**
 * Answers a request the database could not serve with a plain "try again
 * shortly" instead of an unexplained failure. It has to sit outside
 * everything that touches the database, because the first thing to do so
 * on most requests is the session lookup, which runs in a filter long
 * before any controller or the handler that shapes every other error.
 * Only the filter that names the request sits outside this one, so that
 * what is answered here carries the same ID as what is logged.
 *
 * <p>Only a failure to reach the database is treated this way. A statement
 * the database ran and rejected is a defect, not an outage, and keeps its
 * ordinary handling.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class DatabaseUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (RuntimeException | ServletException failure) {
            if (!isDatabaseUnreachable(failure) || response.isCommitted()) {
                throw failure;
            }
            log.warn("The database could not be reached while handling {} {}.", request.getMethod(), request.getRequestURI());
            response.resetBuffer();
            response.setHeader("Retry-After", "5");
            FilterProblemWriter.write(
                    request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Service Unavailable",
                    "The service cannot reach its database right now. Nothing was changed; try again shortly.", "DATABASE_UNAVAILABLE");
        }
    }

    /**
     * Whether this failure, anywhere in what caused it, is the database not
     * being there: no connection to be had, a connection that was cut, or
     * the server saying it is shutting down. When a connection dies under a
     * transaction the failed rollback is what gets thrown and the original
     * failure rides along beside it rather than beneath it, so that is
     * looked through as well.
     */
    static boolean isDatabaseUnreachable(Throwable failure) {
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth < 32; cause = cause.getCause() == cause ? null : cause.getCause()) {
            depth++;
            if (cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof DataAccessResourceFailureException
                    || cause instanceof SQLTransientConnectionException
                    || cause instanceof SQLNonTransientConnectionException
                    || cause instanceof ConnectException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("08") || SERVER_GOING_AWAY.contains(sql.getSQLState()))) {
                return true;
            }
            if (cause instanceof TransactionSystemException transaction
                    && transaction.getApplicationException() != null
                    && transaction.getApplicationException() != failure
                    && isDatabaseUnreachable(transaction.getApplicationException())) {
                return true;
            }
        }
        return false;
    }

    /** PostgreSQL's own words for "this server is stopping, crashed, or is not taking connections yet". */
    private static final Set<String> SERVER_GOING_AWAY = Set.of("57P01", "57P02", "57P03");

    /**
     * An error dispatch is a second pass through the application for the
     * same request, and with the database down it fails the same way. It
     * has to be answered here too, or the server's own bare error page is.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
