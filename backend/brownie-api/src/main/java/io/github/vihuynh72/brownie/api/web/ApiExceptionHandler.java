package io.github.vihuynh72.brownie.api.web;

import io.github.vihuynh72.brownie.core.artifact.ArtifactNotFoundException;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStateConflictException;
import io.github.vihuynh72.brownie.core.artifact.ArtifactTooLargeException;
import io.github.vihuynh72.brownie.core.artifact.MalwareScannerUnavailableException;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException;
import io.github.vihuynh72.brownie.core.document.ExtractionVersionNotFoundException;
import io.github.vihuynh72.brownie.core.document.NotDocxArtifactException;
import io.github.vihuynh72.brownie.core.document.NotPdfArtifactException;
import io.github.vihuynh72.brownie.core.document.NotPlainTextArtifactException;
import io.github.vihuynh72.brownie.core.evidence.InvalidEvidenceLocatorException;
import io.github.vihuynh72.brownie.core.evidence.SourceSpanNotFoundException;
import io.github.vihuynh72.brownie.core.rule.RuleConflictException;
import io.github.vihuynh72.brownie.core.rule.RuleValidationException;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotNotFoundException;
import io.github.vihuynh72.brownie.core.template.MalformedTemplateRequestException;
import io.github.vihuynh72.brownie.core.template.TemplateBindingValidationException;
import io.github.vihuynh72.brownie.core.template.TemplateNotFoundException;
import io.github.vihuynh72.brownie.core.template.TemplateSourceNotExtractableException;
import io.github.vihuynh72.brownie.core.template.TemplateVersionStateConflictException;
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

    @ExceptionHandler(ArtifactNotFoundException.class)
    public ResponseEntity<Object> handleArtifactNotFound(ArtifactNotFoundException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not Found");
        problem.setDetail(ex.getMessage());
        enrich(problem, "NOT_FOUND");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(ArtifactStateConflictException.class)
    public ResponseEntity<Object> handleArtifactStateConflict(ArtifactStateConflictException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(ex.getMessage());
        enrich(problem, "CONFLICT");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(ArtifactTooLargeException.class)
    public ResponseEntity<Object> handleArtifactTooLarge(ArtifactTooLargeException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONTENT_TOO_LARGE);
        problem.setTitle("Content Too Large");
        problem.setDetail(ex.getMessage());
        enrich(problem, "CONTENT_TOO_LARGE");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONTENT_TOO_LARGE, request);
    }

    @ExceptionHandler(UnsupportedArtifactTypeException.class)
    public ResponseEntity<Object> handleUnsupportedArtifactType(UnsupportedArtifactTypeException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setTitle("Unsupported Media Type");
        problem.setDetail(ex.getMessage());
        enrich(problem, "UNSUPPORTED_MEDIA_TYPE");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @ExceptionHandler(NotDocxArtifactException.class)
    public ResponseEntity<Object> handleNotDocxArtifact(NotDocxArtifactException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setTitle("Unsupported Media Type");
        problem.setDetail(ex.getMessage());
        enrich(problem, "NOT_A_DOCX");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @ExceptionHandler(NotPdfArtifactException.class)
    public ResponseEntity<Object> handleNotPdfArtifact(NotPdfArtifactException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setTitle("Unsupported Media Type");
        problem.setDetail(ex.getMessage());
        enrich(problem, "NOT_A_PDF");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @ExceptionHandler(NotPlainTextArtifactException.class)
    public ResponseEntity<Object> handleNotPlainTextArtifact(NotPlainTextArtifactException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setTitle("Unsupported Media Type");
        problem.setDetail(ex.getMessage());
        enrich(problem, "NOT_PLAIN_TEXT");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.UNSUPPORTED_MEDIA_TYPE, request);
    }

    @ExceptionHandler(ExtractionVersionNotFoundException.class)
    public ResponseEntity<Object> handleExtractionVersionNotFound(ExtractionVersionNotFoundException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not Found");
        problem.setDetail(ex.getMessage());
        enrich(problem, "NOT_FOUND");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(SourceSnapshotNotFoundException.class)
    public ResponseEntity<Object> handleSourceSnapshotNotFound(SourceSnapshotNotFoundException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not Found");
        problem.setDetail(ex.getMessage());
        enrich(problem, "NOT_FOUND");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(SourceSpanNotFoundException.class)
    public ResponseEntity<Object> handleSourceSpanNotFound(SourceSpanNotFoundException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not Found");
        problem.setDetail(ex.getMessage());
        enrich(problem, "NOT_FOUND");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    /**
     * An invalid evidence locator means the *request's own content* does
     * not correspond to anything real in the source's extraction -- a bad
     * request, not a conflict or a missing resource.
     */
    @ExceptionHandler(InvalidEvidenceLocatorException.class)
    public ResponseEntity<Object> handleInvalidEvidenceLocator(InvalidEvidenceLocatorException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad Request");
        problem.setDetail(ex.getMessage());
        enrich(problem, "INVALID_EVIDENCE_LOCATOR");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<Object> handleTemplateNotFound(TemplateNotFoundException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Not Found");
        problem.setDetail(ex.getMessage());
        enrich(problem, "NOT_FOUND");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(TemplateVersionStateConflictException.class)
    public ResponseEntity<Object> handleTemplateVersionStateConflict(TemplateVersionStateConflictException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail(ex.getMessage());
        enrich(problem, "CONFLICT");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /**
     * The request is well-formed JSON but names a field ID that is blank,
     * or a binding missing the sub-field its own kind requires -- a bad
     * request, the same category {@link #handleInvalidEvidenceLocator}
     * already covers for a different resource.
     */
    @ExceptionHandler(MalformedTemplateRequestException.class)
    public ResponseEntity<Object> handleMalformedTemplateRequest(MalformedTemplateRequestException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad Request");
        problem.setDetail(ex.getMessage());
        enrich(problem, "MALFORMED_REQUEST");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(TemplateSourceNotExtractableException.class)
    public ResponseEntity<Object> handleTemplateSourceNotExtractable(TemplateSourceNotExtractableException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setTitle("Unprocessable Entity");
        problem.setDetail(ex.getMessage());
        enrich(problem, "SOURCE_NOT_EXTRACTABLE");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    /**
     * The request is well-formed and every field ID is unique, but at
     * least one binding does not resolve to exactly one real node in the
     * source's extracted structure -- semantically invalid, not malformed;
     * {@code fields} names every failing field at once, the same
     * affected-fields shape {@link #handleMethodArgumentNotValid} already
     * uses for ordinary bean-validation failures.
     */
    @ExceptionHandler(TemplateBindingValidationException.class)
    public ResponseEntity<Object> handleTemplateBindingValidation(TemplateBindingValidationException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setTitle("Unprocessable Entity");
        problem.setDetail("One or more field bindings are not supported. See the affected fields below.");
        enrich(problem, "UNSUPPORTED_BINDING");
        List<Map<String, String>> fields = ex.problems().stream()
                .map(problemField -> Map.of("field", problemField.fieldId(), "message", problemField.reason().name()))
                .toList();
        problem.setProperty("fields", fields);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    /**
     * Two or more of the rules proposed against a template's own draft
     * cannot both hold -- activation refuses rather than silently picking
     * one, naming every conflict and every rule ID it involves so a human
     * resolves it.
     */
    @ExceptionHandler(RuleConflictException.class)
    public ResponseEntity<Object> handleRuleConflict(RuleConflictException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        problem.setTitle("Conflict");
        problem.setDetail("One or more proposed rules conflict with each other. See the conflicts below.");
        enrich(problem, "RULE_CONFLICT");
        List<Map<String, Object>> conflicts = ex.conflicts().stream()
                .map(conflict -> Map.<String, Object>of(
                        "reason", conflict.reason().name(), "ruleIds", conflict.ruleIds(), "detail", conflict.detail()))
                .toList();
        problem.setProperty("conflicts", conflicts);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /**
     * A rule that was valid when proposed can become invalid if the still-open
     * draft's field definitions are replaced before activation. The caller
     * must repair that draft rather than treating the invalid rule as absent.
     */
    @ExceptionHandler(RuleValidationException.class)
    public ResponseEntity<Object> handleRuleValidation(RuleValidationException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        problem.setTitle("Unprocessable Entity");
        problem.setDetail("One or more proposed rules no longer apply to this draft. See the problems below.");
        enrich(problem, "RULE_VALIDATION_FAILED");
        List<Map<String, String>> problems = ex.problems().stream()
                .map(ruleProblem -> Map.of(
                        "reason", ruleProblem.reason().name(), "detail", ruleProblem.detail()))
                .toList();
        problem.setProperty("problems", problems);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.UNPROCESSABLE_CONTENT, request);
    }

    /**
     * A scan that could not complete is not the caller's fault and not a
     * verdict on their content -- 503, not 409 or 500, and the detail
     * tells them this is worth retrying rather than reporting.
     */
    @ExceptionHandler(MalwareScannerUnavailableException.class)
    public ResponseEntity<Object> handleMalwareScannerUnavailable(MalwareScannerUnavailableException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Service Unavailable");
        problem.setDetail(ex.getMessage());
        enrich(problem, "SCANNER_UNAVAILABLE");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request);
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
            case 409 -> "CONFLICT";
            case 413 -> "CONTENT_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 500 -> "INTERNAL_ERROR";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> "REQUEST_FAILED";
        };
    }
}
