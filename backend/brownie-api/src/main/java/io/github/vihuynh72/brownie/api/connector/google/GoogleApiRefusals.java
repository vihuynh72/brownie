package io.github.vihuynh72.brownie.api.connector.google;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorBlockedByOrganizationException;
import io.github.vihuynh72.brownie.core.connector.ProviderMisconfiguredException;
import io.github.vihuynh72.brownie.core.connector.ProviderTokenRejectedException;
import io.github.vihuynh72.brownie.core.connector.ProviderUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a Google API's refusal of a read means, the same for every read made
 * with a person's access token. No answer, a throttle or a server error means
 * trying later may work. A 401 means the token itself was refused. Google says
 * a 403 means a usage limit or missing privileges, and that its reason tells
 * which: a rate limit passes, the organization managing the account can
 * refuse the app, and the rest (an API not enabled for Brownie's project, a
 * daily cap its owner set, anything unrecognised) is for whoever runs Brownie
 * to look into. That includes Google saying the token lacks the permission a
 * read needs: every read follows a refresh whose answer names the permissions
 * the token carries, and one that leaves out the calendar's is already "connect
 * again" before any read. A read refused for a permission Google has just
 * said was given is Brownie asking for more than that permission covers,
 * which connecting again cannot change. The status and Google's reason words are logged; Google's
 * message, the answer body and the token never are.
 */
final class GoogleApiRefusals {

    private static final Logger log = LoggerFactory.getLogger(GoogleApiRefusals.class);
    private static final Pattern PLAIN_WORD = Pattern.compile("^[A-Za-z_]{1,64}$");
    /**
     * Google's words for "too many requests just now", in the older and newer
     * error forms, and Calendar's own "usage limits exceeded" for one person.
     * Not {@code dailyLimitExceeded}: Google documents that as a cap the
     * project's owner set, which a short wait does not lift; only the next day
     * or the owner removing it does.
     */
    private static final Set<String> THROTTLE_REASONS = Set.of(
            "rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "RATE_LIMIT_EXCEEDED", "RESOURCE_EXHAUSTED");
    /** Drive's word for "the administrators of this account's domain do not allow Drive apps". */
    private static final String ORGANIZATION_REFUSAL = "domainPolicy";

    private GoogleApiRefusals() {
    }

    /** The exception an unsuccessful answer to a read of {@code what} means, for the caller to throw. */
    static RuntimeException of(GoogleHttp http, GoogleHttp.Answer answer, ConnectorAccess access, String what) {
        if (answer.isProviderFailure()) {
            return new ProviderUnavailableException("Google could not answer about the " + what + " right now (HTTP " + answer.status() + ").");
        }
        if (answer.status() == 401) {
            log.info("Google did not accept the new access token for the {} (HTTP 401).", what);
            return new ProviderTokenRejectedException("Google did not accept the new access token for the " + what + ".");
        }
        List<String> reasons = answer.status() == 403 ? reasonsOf(http, answer) : List.of();
        String reasonText = reasons.isEmpty() ? "no reason given" : String.join(", ", reasons);
        if (reasons.stream().anyMatch(THROTTLE_REASONS::contains)) {
            log.info("Google is limiting requests for the {} (HTTP 403, {}).", what, reasonText);
            return new ProviderUnavailableException("Google is limiting requests for the " + what + " right now.");
        }
        if (reasons.contains(ORGANIZATION_REFUSAL)) {
            log.info("The organization managing the account does not allow this app to read the {} (HTTP 403, {}).", what, reasonText);
            return new ConnectorBlockedByOrganizationException(access);
        }
        log.warn("Google refused the request for the {} (HTTP {}, {}).", what, answer.status(), reasonText);
        return new ProviderMisconfiguredException("Google refused the request for the " + what + " (HTTP " + answer.status() + ").");
    }

    /**
     * The reasons a Google API gave for a refusal, in both of the forms it
     * uses: the older {@code error.errors[].reason} and {@code error.status},
     * and the newer {@code error.details[].reason}. Only plain words are
     * kept; Google's message is not, since it is free text.
     */
    private static List<String> reasonsOf(GoogleHttp http, GoogleHttp.Answer answer) {
        JsonNode error;
        try {
            error = http.json(answer).path("error");
        } catch (ProviderUnavailableException e) {
            return List.of();
        }
        Set<String> reasons = new LinkedHashSet<>();
        for (JsonNode each : error.path("errors")) {
            addIfPlainWord(reasons, GoogleHttp.text(each, "reason"));
        }
        for (JsonNode each : error.path("details")) {
            addIfPlainWord(reasons, GoogleHttp.text(each, "reason"));
        }
        addIfPlainWord(reasons, GoogleHttp.text(error, "status"));
        return List.copyOf(reasons);
    }

    private static void addIfPlainWord(Set<String> reasons, String reason) {
        if (reason != null && PLAIN_WORD.matcher(reason).matches() && reasons.size() < 8) {
            reasons.add(reason);
        }
    }
}
