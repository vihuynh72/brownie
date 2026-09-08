package io.github.vihuynh72.brownie.api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

/**
 * Turns every error -- expected or not -- into the same structured shape:
 * a stable machine-readable {@code code}, a message safe to show a user,
 * this request's correlation ID, any affected fields, and a (currently
 * always empty) list of recovery actions.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} means every
 * exception Spring MVC itself already recognizes (bad JSON, an
 * unsupported method, a route nothing maps to, a failed {@code @Valid})
 * is covered from day one, before this module has written a single
 * {@code @RestController} of its own -- future controllers inherit this
 * contract automatically rather than needing to opt in.
 *
 * <p>Two ways a {@link ProblemDetail} arrives at {@link
 * #handleExceptionInternal}: already enriched, because {@link
 * #handleUnexpected} or {@link #handleMethodArgumentNotValid} built it
 * directly and called {@link #enrich} themselves; or not yet enriched,
 * because it came from one of the base class's many other built-in
 * handlers (unsupported method, unmapped route, unreadable body, and so
 * on) that this class does not individually override. {@code
 * handleExceptionInternal} is the one place both paths meet, and only
 * fills in the generic, status-code-derived code when nothing has set one
 * already -- it never overwrites a more specific one.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * A denied capability check throws this from ordinary application
     * code (a controller or a service it calls), not from a URL-pattern
     * authorization rule -- Spring MVC's own dispatch resolves it here,
     * through this catch-all advice, before it could ever reach Spring
     * Security's separate, filter-level access-denied handling. Confirmed
     * by tracing an actual denied request through this method, not
     * assumed from how the two mechanisms are usually described.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Forbidden");
        problem.setDetail("You do not have the required access for this resource.");
        enrich(problem, "FORBIDDEN");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

    /**
     * Catches anything Spring MVC's own handling does not recognize -- an
     * unexpected {@code RuntimeException} from application code, for
     * instance. The real exception is logged in full server-side; the
     * client only ever sees a safe, generic message, never {@code
     * ex.getMessage()} or a stack trace.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception while processing request", ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("An unexpected error occurred. If this persists, report it with the correlation ID below.");
        enrich(problem, "INTERNAL_ERROR");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle("Validation Failed");
        problem.setDetail("The request did not pass validation. See the affected fields below.");
        List<Map<String, String>> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", String.valueOf(fieldError.getDefaultMessage())))
                .toList();
        enrich(problem, "VALIDATION_FAILED");
        problem.setProperty("fields", fields);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = asProblemDetail(ex, body, statusCode);
        if (!isEnriched(problem)) {
            enrich(problem, codeFor(statusCode));
        }
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    /**
     * Most of the base class's built-in handlers this class does not
     * specifically override (an unmapped route, an unsupported method, and
     * so on) pass {@code body == null} here, expecting the {@link
     * ErrorResponse} the exception itself carries -- via {@link
     * ErrorResponse#getBody()} -- to be used instead. Confirmed by tracing
     * an actual request through this method rather than assumed: the base
     * class's own default behavior for those cases does not go through
     * this override with a usable body at all otherwise.
     */
    private static ProblemDetail asProblemDetail(Exception ex, Object body, HttpStatusCode statusCode) {
        if (body instanceof ProblemDetail problem) {
            return problem;
        }
        if (ex instanceof ErrorResponse errorResponse) {
            return errorResponse.getBody();
        }
        return ProblemDetail.forStatus(statusCode);
    }

    private static boolean isEnriched(ProblemDetail problem) {
        return problem.getProperties() != null && problem.getProperties().containsKey("code");
    }

    private void enrich(ProblemDetail problem, String code) {
        problem.setProperty("code", code);
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        problem.setProperty("fields", List.of());
        problem.setProperty("recoveryActions", List.of());
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 500 -> "INTERNAL_ERROR";
            default -> "REQUEST_FAILED";
        };
    }
}
