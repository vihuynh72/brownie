package io.github.vihuynh72.brownie.api.web.ratelimit;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A token bucket for each person and class of request: the bucket holds a
 * minute's allowance, refills evenly, and a request that finds it empty is
 * told how many seconds until there is one token again. A bucket that
 * starts full lets a burst through and then settles to the steady rate,
 * which is how a person actually uses an application.
 *
 * <p>It lives in this process's memory. That is the right size for one
 * API instance; with more than one, each would allow the full rate, and
 * the limit would have to move to something they share.
 */
public final class RateLimiter {

    /**
     * Buckets are dropped once unused this long (one unused for a minute is
     * already full again, so nothing is lost). Past this many, a sweep
     * runs before a new one is added, but no more often than once a minute:
     * when every bucket is in use a sweep removes nothing, and running one
     * on every request would be the cost of the flood it was meant to absorb.
     */
    private static final long IDLE_NANOS = 2L * 60 * 1_000_000_000L;
    private static final int SWEEP_THRESHOLD = 20_000;
    /**
     * Past this many, someone not yet being counted is counted together
     * with everyone else in the same position, in one bucket per kind of
     * request. It is what keeps the memory this uses bounded whatever is
     * sent; an unused bucket is full again within a minute, so nobody who
     * was being counted loses anything by it.
     */
    private static final int MAX_BUCKETS = 100_000;
    private static final String EVERYONE_ELSE = "everyone not yet counted";
    private final AtomicLong lastSweep = new AtomicLong(Long.MIN_VALUE);
    private static final long NANOS_PER_MINUTE = 60L * 1_000_000_000L;

    private final Map<RateLimitClass, Integer> perMinute;
    private final LongSupplier nanoClock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(Map<RateLimitClass, Integer> perMinute, LongSupplier nanoClock) {
        this.perMinute = new EnumMap<>(Objects.requireNonNull(perMinute, "perMinute"));
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        for (RateLimitClass limitClass : RateLimitClass.values()) {
            Integer limit = this.perMinute.get(limitClass);
            if (limit == null || limit < 1) {
                throw new IllegalArgumentException("A rate of at least one a minute is required for " + limitClass + ".");
            }
        }
    }

    /** Zero when the request may go ahead; otherwise the whole seconds to wait before one would. */
    public long secondsUntilAllowed(String who, RateLimitClass limitClass) {
        long now = nanoClock.getAsLong();
        if (buckets.size() > SWEEP_THRESHOLD) {
            long swept = lastSweep.get();
            if ((swept == Long.MIN_VALUE || now - swept > NANOS_PER_MINUTE) && lastSweep.compareAndSet(swept, now)) {
                buckets.values().removeIf(bucket -> bucket.idleSince(now) > IDLE_NANOS);
            }
        }
        int limit = perMinute.get(limitClass);
        String key = limitClass.name() + '\n' + who;
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(key)) {
            key = limitClass.name() + '\n' + EVERYONE_ELSE;
        }
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(limit, now));
        return bucket.take(limit, nanoClock);
    }

    int trackedBuckets() {
        return buckets.size();
    }

    private static final class Bucket {

        private double tokens;
        private long lastRefill;
        private long lastUsed;

        private Bucket(int capacity, long now) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastUsed = now;
        }

        /**
         * The clock is read once this bucket is held, not before. Two
         * requests that read it first and then took turns in the other
         * order would set the refill time backwards, and the next request
         * would be credited for the same interval twice.
         */
        private synchronized long take(int capacity, LongSupplier nanoClock) {
            long now = Math.max(lastRefill, nanoClock.getAsLong());
            double refilled = (now - lastRefill) * (double) capacity / NANOS_PER_MINUTE;
            tokens = Math.min(capacity, tokens + Math.max(0, refilled));
            lastRefill = now;
            lastUsed = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            double secondsPerToken = 60.0 / capacity;
            return Math.max(1, (long) Math.ceil((1 - tokens) * secondsPerToken));
        }

        private synchronized long idleSince(long now) {
            return now - lastUsed;
        }
    }
}
