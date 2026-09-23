package io.github.vihuynh72.brownie.api.web.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimiterTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(1);

    @Test
    void aMinutesAllowanceGoesThroughAtOnceThenTheRestWaitForTheSteadyRate() {
        RateLimiter limiter = limiter(6);

        for (int i = 0; i < 6; i++) {
            assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.RENDER)).as("request %d", i).isZero();
        }
        // Six a minute is one every ten seconds.
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.RENDER)).isEqualTo(10);

        now.addAndGet(9 * SECOND);
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.RENDER)).isEqualTo(1);
        now.addAndGet(SECOND);
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.RENDER)).isZero();
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.RENDER)).isPositive();
    }

    @Test
    void onePersonsRequestsNeverCountAgainstAnotherNorOneKindAgainstAnother() {
        RateLimiter limiter = limiter(1);

        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.RENDER)).isZero();
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.RENDER)).isPositive();

        assertThat(limiter.secondsUntilAllowed("ben", RateLimitClass.RENDER)).isZero();
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.READ)).isZero();
    }

    @Test
    void anAllowanceNeverBuildsUpPastOneMinutesWorth() {
        RateLimiter limiter = limiter(3);
        now.addAndGet(3600 * SECOND);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.WRITE)).isZero();
        }
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.WRITE)).isPositive();
    }

    @Test
    void everyKindOfRequestNeedsAnAllowance() {
        Map<RateLimitClass, Integer> missingOne = new EnumMap<>(RateLimitClass.class);
        missingOne.put(RateLimitClass.READ, 10);
        assertThatThrownBy(() -> new RateLimiter(missingOne, now::get)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRequestIsClassedByWhatItCostsTheHost() {
        assertThat(classOf("GET", "/api/v1/workspaces/7/documents/3/generations")).isEqualTo(RateLimitClass.READ);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/generations")).isEqualTo(RateLimitClass.MODEL);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/assist/execute")).isEqualTo(RateLimitClass.MODEL);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/generations/12/resume")).isEqualTo(RateLimitClass.MODEL);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/validate")).isEqualTo(RateLimitClass.RENDER);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/export")).isEqualTo(RateLimitClass.RENDER);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/revisions/9/compile")).isEqualTo(RateLimitClass.RENDER);
        assertThat(classOf("POST", "/api/v1/workspaces/7/templates/2/versions")).isEqualTo(RateLimitClass.RENDER);
        assertThat(classOf("POST", "/api/v1/workspaces/7/uploads")).isEqualTo(RateLimitClass.UPLOAD);
        assertThat(classOf("PUT", "/api/v1/workspaces/7/uploads/4/content")).isEqualTo(RateLimitClass.UPLOAD);
        assertThat(classOf("POST", "/api/v1/workspaces/7/artifacts/4/extraction")).isEqualTo(RateLimitClass.UPLOAD);
        assertThat(classOf("PATCH", "/api/v1/workspaces/7/documents/3/content")).isEqualTo(RateLimitClass.WRITE);
        assertThat(classOf("POST", "/api/v1/workspaces/7/deletions")).isEqualTo(RateLimitClass.WRITE);
        // Interpreting a request makes no model call; only carrying it out can.
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/assist/interpret")).isEqualTo(RateLimitClass.WRITE);
        // Starting a stopped or failed run again queues the same paid work as starting one.
        assertThat(classOf("POST", "/api/v1/workspaces/7/jobs/12/resume")).isEqualTo(RateLimitClass.MODEL);
        assertThat(classOf("POST", "/api/v1/workspaces/7/jobs/12/retry")).isEqualTo(RateLimitClass.MODEL);
        assertThat(classOf("POST", "/api/v1/workspaces/7/jobs/12/cancel")).isEqualTo(RateLimitClass.WRITE);
        assertThat(classOf("GET", "/api/v1/workspaces/7/events")).isEqualTo(RateLimitClass.READ);
    }

    /** Whatever the method: a read that makes Brownie call Google costs Google's patience with Brownie as a whole. */
    @Test
    void everythingThatMakesBrownieCallGoogleIsCountedApartEvenAGet() {
        assertThat(classOf("GET", "/api/v1/connectors/google/callback")).isEqualTo(RateLimitClass.CONNECTOR);
        assertThat(classOf("POST", "/api/v1/workspaces/7/connections/google")).isEqualTo(RateLimitClass.CONNECTOR);
        assertThat(classOf("POST", "/api/v1/workspaces/7/connections/google/disconnect")).isEqualTo(RateLimitClass.CONNECTOR);
        assertThat(classOf("POST", "/api/v1/workspaces/0x7/connections/google/disconnect")).isEqualTo(RateLimitClass.CONNECTOR);
        assertThat(classOf("GET", "/api/v1/workspaces/7/connections/google/calendar/events")).isEqualTo(RateLimitClass.CONNECTOR);
        assertThat(classOf("POST", "/api/v1/workspaces/7/connections/google/calendar/imports")).isEqualTo(RateLimitClass.CONNECTOR);
        // Listing what is connected only reads Brownie's own records.
        assertThat(classOf("GET", "/api/v1/workspaces/7/connections")).isEqualTo(RateLimitClass.READ);
    }

    /**
     * The application reads {@code +3}, {@code 0x3} and a number followed by a space as the same document as
     * {@code 3}. A pattern that asked for digits would let each of them through to the expensive handler while
     * counting it as an ordinary change.
     */
    @Test
    void anIdentifierWrittenAnotherWayDoesNotBuyAMoreGenerousAllowanceEither() {
        assertThat(classOf("POST", "/api/v1/workspaces/+7/documents/0x3/validate")).isEqualTo(RateLimitClass.RENDER);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3%20/generations")).isEqualTo(RateLimitClass.MODEL);
        assertThat(classOf("POST", "/api/v1/workspaces/7/jobs/0xC/retry")).isEqualTo(RateLimitClass.MODEL);
    }

    @Test
    void twoRequestsThatReadTheClockInOneOrderAndTookTurnsInTheOtherAreNotCreditedTwice() {
        // Each request reads the clock twice. Three at the start use the allowance up; then one that read forty
        // seconds, one that read twenty but took its turn later, and one more at forty.
        long[] readings = {0, 0, 0, 0, 0, 0, 40 * SECOND, 40 * SECOND, 20 * SECOND, 20 * SECOND, 40 * SECOND, 40 * SECOND};
        int[] next = {0};
        Map<RateLimitClass, Integer> all = new EnumMap<>(RateLimitClass.class);
        for (RateLimitClass limitClass : RateLimitClass.values()) {
            all.put(limitClass, 3);
        }
        RateLimiter limiter = new RateLimiter(all, () -> readings[Math.min(next[0]++, readings.length - 1)]);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.MODEL)).isZero();
        }

        // Three a minute is one every twenty seconds, so forty seconds owe two and no more.
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.MODEL)).isZero();
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.MODEL)).isZero();
        assertThat(limiter.secondsUntilAllowed("ana", RateLimitClass.MODEL)).isPositive();
    }

    @Test
    void someoneNotSignedInIsCountedByNetworkSoThatAnAddressBlockIsNotAnEndlessSupplyOfAllowances() {
        assertThat(RateLimitFilter.networkOf("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(RateLimitFilter.networkOf("2001:db8:12:34:aaaa:bbbb:cccc:1"))
                .isEqualTo(RateLimitFilter.networkOf("2001:db8:12:34:1:2:3:4"))
                .isNotEqualTo(RateLimitFilter.networkOf("2001:db8:12:35:1:2:3:4"));
        assertThat(RateLimitFilter.networkOf("fe80::1%en0")).isEqualTo(RateLimitFilter.networkOf("fe80::2"));
        // Never looked up: something that is not an address is kept as it came.
        assertThat(RateLimitFilter.networkOf("not:an:address.example")).isEqualTo("not:an:address.example");
    }

    @Test
    void theMemoryThisUsesIsBoundedHoweverManyDifferentCallersThereAre() {
        RateLimiter limiter = limiter(2);
        for (int i = 0; i < 100_050; i++) {
            limiter.secondsUntilAllowed("address " + i, RateLimitClass.ANONYMOUS);
        }

        assertThat(limiter.trackedBuckets()).isLessThanOrEqualTo(100_001);
        // Whoever arrives past the ceiling shares one allowance: two go through, the third waits.
        assertThat(limiter.secondsUntilAllowed("address a", RateLimitClass.ANONYMOUS)).isPositive();
        // Someone already being counted is unaffected.
        assertThat(limiter.secondsUntilAllowed("address 5", RateLimitClass.ANONYMOUS)).isZero();
    }

    /**
     * Routing decodes the address before it chooses a handler, so the class has to be decided on the decoded
     * address too; otherwise an encoded letter buys the generous allowance for the most expensive request there is.
     */
    @Test
    void anEncodedLetterOrAPathParameterDoesNotBuyAMoreGenerousAllowance() {
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/%76alidate")).isEqualTo(RateLimitClass.RENDER);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/assist/execut%65")).isEqualTo(RateLimitClass.MODEL);
        assertThat(classOf("POST", "/api/v1/workspaces/7/documents/3/export;x=1")).isEqualTo(RateLimitClass.RENDER);
        assertThat(classOf("PUT", "/api/v1/workspaces/7/uploads/4/content;jsessionid=abc")).isEqualTo(RateLimitClass.UPLOAD);
    }

    private RateLimiter limiter(int perMinute) {
        Map<RateLimitClass, Integer> all = new EnumMap<>(RateLimitClass.class);
        for (RateLimitClass limitClass : RateLimitClass.values()) {
            all.put(limitClass, perMinute);
        }
        return new RateLimiter(all, now::get);
    }

    private static RateLimitClass classOf(String method, String path) {
        return RateLimitFilter.classify(new MockHttpServletRequest(method, path));
    }
}
