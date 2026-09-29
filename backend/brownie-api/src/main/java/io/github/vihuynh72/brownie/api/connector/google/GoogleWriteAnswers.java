package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.action.ActionFailure;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * What Google's answer to a change proves, which is not what its answer to a
 * read would mean. A read that fails can be tried again; a write whose answer
 * was lost, or that met a server error, may already have happened, so it is
 * unknown until someone asks Google what became of it. Only an answer that
 * shows the request was not processed counts as "not done": no connection
 * was made at all, the access token was refused, or Google refused it for a
 * reason it names. A refusal that trying again would meet the same way (a
 * full Drive, an organisation's policy, a missing permission, a usage cap)
 * ends the change; one that passes (a rate limit) leaves it to be tried
 * again. A conflict on a create that carried Brownie's own id means an
 * earlier attempt already made it.
 *
 * <p>The status and Google's reason words are logged; Google's message, the
 * body and the token never are.
 */
final class GoogleWriteAnswers {

    private static final Logger log = LoggerFactory.getLogger(GoogleWriteAnswers.class);

    private static final Set<String> PASSING_LIMITS = Set.of(
            "rateLimitExceeded", "userRateLimitExceeded", "RATE_LIMIT_EXCEEDED", "RESOURCE_EXHAUSTED");
    private static final Set<String> STORAGE_FULL = Set.of("storageQuotaExceeded");
    private static final Set<String> ORGANIZATION_REFUSED = Set.of("domainPolicy");
    /** A cap a short wait does not lift: the project's daily cap, and Calendar's own "usage limits exceeded" for a person. */
    private static final Set<String> LASTING_LIMITS = Set.of("dailyLimitExceeded", "quotaExceeded");
    private static final Set<String> PERMISSION_REFUSED = Set.of(
            "insufficientFilePermissions", "appNotAuthorizedToFile", "insufficientPermissions", "ACCESS_TOKEN_SCOPE_INSUFFICIENT",
            "forbidden", "forbiddenForNonOrganizer", "requiredAccessLevel");

    private GoogleWriteAnswers() {
    }

    /** Anything but an answer in the 2xx range, sorted by what it proves about the change. */
    static WriteAnswer refusal(GoogleHttp http, GoogleHttp.WriteExchange exchange, String what) {
        return switch (exchange) {
            case GoogleHttp.NotSent ignored -> {
                log.info("A {} could not be sent: no connection to Google could be made. Nothing was changed.", what);
                yield new WriteAnswer.NotAppliedRetryable(null, List.of(), false);
            }
            case GoogleHttp.Lost ignored -> {
                log.warn("A {} was sent and its answer was lost; whether it happened is unknown.", what);
                yield new WriteAnswer.Unknown(null, List.of());
            }
            case GoogleHttp.Answered answered -> refusal(http, answered.answer(), what);
        };
    }

    static WriteAnswer refusal(GoogleHttp http, GoogleHttp.Answer answer, String what) {
        int status = answer.status();
        if (status >= 500) {
            log.warn("Google answered a {} with a server error (HTTP {}); whether it happened is unknown.", what, status);
            return new WriteAnswer.Unknown(status, List.of());
        }
        List<String> reasons = status >= 400 ? GoogleApiRefusals.reasonsOf(http, answer) : List.of();
        String reasonText = reasons.isEmpty() ? "no reason given" : String.join(", ", reasons);
        if (status == 409) {
            log.info("Google says the {} already exists (HTTP 409, {}).", what, reasonText);
            return new WriteAnswer.Exists(status, reasons);
        }
        if (status == 401) {
            log.info("Google did not accept the access token for a {} (HTTP 401); nothing was changed.", what);
            return new WriteAnswer.NotAppliedRetryable(status, reasons, true);
        }
        if (status == 429 || reasons.stream().anyMatch(PASSING_LIMITS::contains)) {
            log.info("Google is limiting requests; a {} was not made (HTTP {}, {}).", what, status, reasonText);
            return new WriteAnswer.NotAppliedRetryable(status, reasons, false);
        }
        ActionFailure failure;
        if (reasons.stream().anyMatch(STORAGE_FULL::contains)) {
            failure = ActionFailure.STORAGE_FULL;
        } else if (reasons.stream().anyMatch(ORGANIZATION_REFUSED::contains)) {
            failure = ActionFailure.BLOCKED_BY_ORGANIZATION;
        } else if (reasons.stream().anyMatch(LASTING_LIMITS::contains)) {
            failure = ActionFailure.LIMIT_REACHED;
        } else if (reasons.stream().anyMatch(PERMISSION_REFUSED::contains)) {
            failure = ActionFailure.PERMISSION_REFUSED;
        } else if (status == 404 || status == 410) {
            failure = ActionFailure.TARGET_UNAVAILABLE;
        } else {
            failure = ActionFailure.PROVIDER_REFUSED;
        }
        log.warn("Google refused a {} (HTTP {}, {}); nothing was changed.", what, status, reasonText);
        return new WriteAnswer.NotAppliedFinal(status, reasons, failure);
    }
}
