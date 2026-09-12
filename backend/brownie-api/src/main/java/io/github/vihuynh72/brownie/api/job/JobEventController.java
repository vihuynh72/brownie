package io.github.vihuynh72.brownie.api.job;

import io.github.vihuynh72.brownie.api.workspace.WorkspaceAuthorizationService;
import io.github.vihuynh72.brownie.core.identity.UserIdentityRepository;
import io.github.vihuynh72.brownie.core.job.JobEvent;
import io.github.vihuynh72.brownie.core.job.JobEventRepository;
import io.github.vihuynh72.brownie.core.workspace.WorkspaceCapability;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delivers persisted job progress as a bounded convenience stream. The
 * polling endpoint remains the recovery path when a browser disconnects or
 * cannot keep an event stream open.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/events")
class JobEventController {

    private static final int PAGE_SIZE = 100;
    private static final int MAX_CONCURRENT_STREAMS = 20;
    private static final long STREAM_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long POLL_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(1);
    private static final long HEARTBEAT_INTERVAL_MILLIS = TimeUnit.SECONDS.toMillis(10);

    private final JobEventRepository jobEventRepository;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final UserIdentityRepository userIdentityRepository;
    private final ScheduledExecutorService eventStreamExecutor;
    private final AtomicInteger activeStreams = new AtomicInteger();

    JobEventController(
            JobEventRepository jobEventRepository,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            UserIdentityRepository userIdentityRepository,
            ScheduledExecutorService eventStreamExecutor) {
        this.jobEventRepository = jobEventRepository;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.userIdentityRepository = userIdentityRepository;
        this.eventStreamExecutor = eventStreamExecutor;
    }

    @GetMapping(value = "/poll", produces = MediaType.APPLICATION_JSON_VALUE)
    EventBatchResponse poll(
            @PathVariable long workspaceId,
            @RequestParam(value = "afterEventId", required = false) Long afterEventId,
            @RequestParam(value = "limit", required = false) Integer requestedLimit,
            @AuthenticationPrincipal OidcUser principal) {
        long cursor = cursor(afterEventId);
        int limit = limit(requestedLimit);
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        if (requiresResync(workspaceId, userId, cursor)) {
            return EventBatchResponse.resync(cursor);
        }
        List<JobEvent> events = jobEventRepository.findAfter(workspaceId, userId, cursor, limit);
        return EventBatchResponse.events(cursor, events);
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(
            @PathVariable long workspaceId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @AuthenticationPrincipal OidcUser principal) {
        long cursor = cursor(lastEventId);
        long userId = currentUserId(principal);
        requireAccess(userId, workspaceId);
        acquireStreamSlot();

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        AtomicLong deliveredCursor = new AtomicLong(cursor);
        AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> scheduledTask = new AtomicReference<>();
        Runnable cleanup = () -> closeStream(closed, scheduledTask);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        if (requiresResync(workspaceId, userId, cursor)) {
            sendResyncAndClose(emitter, cursor, cleanup);
            return emitter;
        }
        if (!deliverAvailable(emitter, workspaceId, userId, deliveredCursor, lastHeartbeat, cleanup)) {
            return emitter;
        }
        ScheduledFuture<?> future = eventStreamExecutor.scheduleWithFixedDelay(
                () -> deliverAvailable(emitter, workspaceId, userId, deliveredCursor, lastHeartbeat, cleanup),
                POLL_INTERVAL_MILLIS,
                POLL_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
        scheduledTask.set(future);
        if (closed.get()) {
            future.cancel(false);
        }
        return emitter;
    }

    private boolean deliverAvailable(
            SseEmitter emitter,
            long workspaceId,
            long userId,
            AtomicLong deliveredCursor,
            AtomicLong lastHeartbeat,
            Runnable cleanup) {
        try {
            requireAccess(userId, workspaceId);
            List<JobEvent> events = jobEventRepository.findAfter(workspaceId, userId, deliveredCursor.get(), PAGE_SIZE);
            for (JobEvent event : events) {
                emitter.send(SseEmitter.event()
                        .id(Long.toString(event.id()))
                        .name("job-progress")
                        .data(EventResponse.from(event)));
                deliveredCursor.set(event.id());
            }
            long now = System.currentTimeMillis();
            if (events.isEmpty() && now - lastHeartbeat.get() >= HEARTBEAT_INTERVAL_MILLIS) {
                emitter.send(SseEmitter.event().comment("keepalive"));
                lastHeartbeat.set(now);
            }
            return true;
        } catch (AccessDeniedException denied) {
            emitter.complete();
            cleanup.run();
            return false;
        } catch (IOException | RuntimeException failure) {
            emitter.completeWithError(failure);
            cleanup.run();
            return false;
        }
    }

    private void sendResyncAndClose(SseEmitter emitter, long cursor, Runnable cleanup) {
        try {
            emitter.send(SseEmitter.event()
                    .name("resync-required")
                    .data(new ResyncResponse(cursor, "Fetch authoritative job state, then reconnect without this cursor.")));
        } catch (IOException | RuntimeException ignored) {
            // The browser may have left before the short recovery instruction arrived.
        } finally {
            emitter.complete();
            cleanup.run();
        }
    }

    private boolean requiresResync(long workspaceId, long userId, long cursor) {
        return cursor > 0 && !jobEventRepository.exists(workspaceId, userId, cursor);
    }

    private void acquireStreamSlot() {
        if (activeStreams.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
            activeStreams.decrementAndGet();
            throw new EventStreamCapacityException();
        }
    }

    private void closeStream(AtomicBoolean closed, AtomicReference<ScheduledFuture<?>> scheduledTask) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> future = scheduledTask.get();
        if (future != null) {
            future.cancel(false);
        }
        activeStreams.decrementAndGet();
    }

    private void requireAccess(long userId, long workspaceId) {
        workspaceAuthorizationService.requireCapability(userId, workspaceId, WorkspaceCapability.MANAGE_WORKSPACE);
    }

    private long currentUserId(OidcUser principal) {
        String issuer = principal.getIssuer().toString();
        String subject = principal.getSubject();
        return userIdentityRepository
                .findByIssuerAndSubject(issuer, subject)
                .orElseThrow(() -> new IllegalStateException(
                        "Authenticated principal has no recorded identity for issuer/subject " + issuer + "/" + subject))
                .id();
    }

    private static int limit(Integer requestedLimit) {
        int limit = requestedLimit == null ? PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > PAGE_SIZE) {
            throw new JobRequestValidationException("limit must be between 1 and " + PAGE_SIZE + ".");
        }
        return limit;
    }

    private static long cursor(Long cursor) {
        if (cursor == null) {
            return 0L;
        }
        if (cursor < 0) {
            throw new JobRequestValidationException("afterEventId must not be negative.");
        }
        return cursor;
    }

    private static long cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(cursor);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new JobRequestValidationException("Last-Event-ID must be a non-negative integer.");
        }
    }

    record EventBatchResponse(boolean resyncRequired, long nextEventId, List<EventResponse> events) {

        static EventBatchResponse resync(long cursor) {
            return new EventBatchResponse(true, cursor, List.of());
        }

        static EventBatchResponse events(long cursor, List<JobEvent> events) {
            List<EventResponse> responses = events.stream().map(EventResponse::from).toList();
            long nextEventId = responses.isEmpty() ? cursor : responses.getLast().eventId();
            return new EventBatchResponse(false, nextEventId, responses);
        }
    }

    record EventResponse(
            long eventId,
            long workspaceId,
            long jobId,
            String resourceType,
            long resourceId,
            long resourceVersion,
            String stage,
            long jobSequence,
            String type,
            String state,
            String message,
            Integer progressCurrent,
            Integer progressTotal,
            OffsetDateTime occurredAt) {

        static EventResponse from(JobEvent event) {
            return new EventResponse(
                    event.id(),
                    event.workspaceId(),
                    event.jobId(),
                    event.target().resourceType(),
                    event.target().resourceId(),
                    event.target().resourceVersion(),
                    event.stage().value(),
                    event.sequence(),
                    event.type().name(),
                    event.state().name(),
                    event.safeMessage(),
                    event.progressCurrent(),
                    event.progressTotal(),
                    event.createdAt());
        }
    }

    record ResyncResponse(long staleCursor, String instruction) {
    }
}
