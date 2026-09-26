package io.github.vihuynh72.brownie.core.connector;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.revision.DocumentNotFoundException;
import io.github.vihuynh72.brownie.core.revision.RevisionService;
import io.github.vihuynh72.brownie.core.source.AttachedSource;
import io.github.vihuynh72.brownie.core.source.DocumentSource;
import io.github.vihuynh72.brownie.core.source.DocumentSourceRepository;
import io.github.vihuynh72.brownie.core.source.DocumentSourceService;
import io.github.vihuynh72.brownie.core.source.SourceConversion;
import io.github.vihuynh72.brownie.core.source.SourceKind;
import io.github.vihuynh72.brownie.core.source.SourceOrigin;
import io.github.vihuynh72.brownie.core.source.SourceService;
import io.github.vihuynh72.brownie.core.source.SourceSnapshot;
import io.github.vihuynh72.brownie.core.source.SourceSnapshotRepository;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Reading a person's own calendar into Brownie: a window of events to choose
 * from, and one chosen event copied into a document as a text source.
 *
 * <p>Only the primary calendar is read, only in a window of at most 31 days,
 * and at most 50 events at a time. Reading it is recorded as the person's
 * choice (a read-only grant for that calendar), which every copy names.
 *
 * <p>An import re-reads the event by its id, so what is copied is what the
 * calendar holds at that moment, not what a listing showed earlier. Only an
 * ordinary event or one occurrence of a repeating one is copied, never a
 * whole series or a working location and the like. The text goes through the
 * same checks as an upload and becomes an ordinary artifact; the snapshot
 * keeps where it came from and when it was read. The same event is copied
 * once for as long as its text stays the same: importing it again links the
 * copy already made, even when the calendar's version changed for something
 * the text does not show (a guest replying, say). A copy is never refreshed
 * on its own; a changed event is a new copy, and the old one stays where
 * history cites it. Everything happens during the request that asked for
 * it, and the access token is never kept.
 */
public class CalendarImportService {

    /** The longest window a listing covers. */
    public static final Duration MAX_WINDOW = Duration.ofDays(31);
    /** The most events a listing returns. */
    public static final int MAX_EVENTS = 50;

    static final String PRIMARY_CALENDAR = "primary";
    private static final String PRIMARY_CALENDAR_NAME = "Primary calendar";
    /** A month of local days is an hour longer when the clocks go back within it; up to two hours is allowed for that. */
    private static final Duration CLOCK_CHANGE_ALLOWANCE = Duration.ofHours(2);
    private static final Instant EARLIEST = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("9999-12-31T00:00:00Z");
    /** Google's event ids, including those of one occurrence of a repeating event, use only these characters. */
    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,1024}$");
    private static final int MAX_TITLE_CODE_POINTS = 500;
    private static final int MAX_FILENAME_CODE_POINTS = 200;
    /** Leaves room for ".txt" inside the 255 characters a file name is kept to. */
    private static final int MAX_FILENAME_BASE_CHARS = 251;

    /** The document's source for the event, and whether this import made a new copy or linked one already made. */
    public record ImportOutcome(AttachedSource source, boolean newCopy) {
    }

    private final ConnectorService connectorService;
    private final ResourceGrantRepository grantRepository;
    private final CalendarEventReader calendarReader;
    private final ArtifactService artifactService;
    private final SourceService sourceService;
    private final SourceSnapshotRepository sourceSnapshotRepository;
    private final DocumentSourceService documentSourceService;
    private final DocumentSourceRepository documentSourceRepository;
    private final RevisionService revisionService;

    public CalendarImportService(
            ConnectorService connectorService,
            ResourceGrantRepository grantRepository,
            CalendarEventReader calendarReader,
            ArtifactService artifactService,
            SourceService sourceService,
            SourceSnapshotRepository sourceSnapshotRepository,
            DocumentSourceService documentSourceService,
            DocumentSourceRepository documentSourceRepository,
            RevisionService revisionService) {
        this.connectorService = connectorService;
        this.grantRepository = grantRepository;
        this.calendarReader = calendarReader;
        this.artifactService = artifactService;
        this.sourceService = sourceService;
        this.sourceSnapshotRepository = sourceSnapshotRepository;
        this.documentSourceService = documentSourceService;
        this.documentSourceRepository = documentSourceRepository;
        this.revisionService = revisionService;
    }

    /** The events overlapping {@code from} to {@code to} on the person's primary calendar, at most {@link #MAX_EVENTS}. */
    public CalendarWindow events(long workspaceId, long userId, Instant from, Instant to) {
        requireWindow(from, to);
        UsableConnection calendar = connectorService.use(workspaceId, userId, ConnectorAccess.CALENDAR_EVENTS);
        primaryCalendarGrant(workspaceId, userId, calendar);
        return read(calendar, () -> calendarReader.listEvents(calendar.accessToken(), from, to, MAX_EVENTS));
    }

    /** Copies one event of the person's primary calendar into a document as a source, or links the copy already made of it. */
    public ImportOutcome importEvent(long workspaceId, long userId, long documentId, String eventId) {
        if (eventId == null || !EVENT_ID.matcher(eventId).matches()) {
            throw new InvalidCalendarRequestException("eventId is not a calendar event id.");
        }
        // Before anything is asked of Google: a document that is not there, or is in the trash, takes nothing in.
        revisionService.findDocument(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        UsableConnection calendar = connectorService.use(workspaceId, userId, ConnectorAccess.CALENDAR_EVENTS);
        ResourceGrant grant = primaryCalendarGrant(workspaceId, userId, calendar);

        CalendarEvent event = read(calendar, () -> calendarReader.readEvent(calendar.accessToken(), eventId));
        Instant readAt = Instant.now();
        if (event.status() == CalendarEventStatus.CANCELLED) {
            throw new ConnectorResourceUnavailableException(ConnectorResourceUnavailableException.Reason.CANCELLED, ConnectorAccess.CALENDAR_EVENTS);
        }
        if (event.series()) {
            throw new ConnectorResourceUnsupportedException("This is a repeating series; choose one of its occurrences instead.");
        }
        if (event.eventType() != null && !"default".equals(event.eventType())) {
            throw new ConnectorResourceUnsupportedException("This is not an ordinary calendar event, so Brownie does not copy it.");
        }
        if (event.revision() == null || event.revision().isBlank() || event.revision().length() > 255) {
            throw new ProviderUnavailableException("Google's answer did not say which version of the event it was.");
        }
        Optional<SourceSnapshot> sameVersion =
                sourceSnapshotRepository.findImported(workspaceId, userId, grant.id(), event.id(), event.revision());
        if (sameVersion.isPresent()) {
            return new ImportOutcome(documentSourceService.attach(workspaceId, userId, documentId, sameVersion.get().artifactId()), false);
        }

        String timeZone = read(calendar, () -> calendarReader.calendarTimeZone(calendar.accessToken()));
        byte[] text = CalendarEventText.render(event, CalendarEventText.zoneOrNull(timeZone)).getBytes(StandardCharsets.UTF_8);
        // A new version whose text is unchanged (a guest replied, a reminder moved) is not a new copy.
        Optional<SourceSnapshot> sameText =
                sourceSnapshotRepository.findLatestImportedWithContent(workspaceId, userId, grant.id(), event.id(), sha256Hex(text));
        if (sameText.isPresent()) {
            return new ImportOutcome(documentSourceService.attach(workspaceId, userId, documentId, sameText.get().artifactId()), false);
        }

        Artifact artifact = artifactService.initiateUpload(workspaceId, userId, fileName(event));
        artifactService.receiveContent(workspaceId, userId, artifact.id(), new ByteArrayInputStream(text));
        Artifact finalized = artifactService.finalizeUpload(workspaceId, userId, artifact.id());
        if (finalized.status() != ArtifactStatus.READY) {
            throw new ConnectorResourceRefusedException(finalized.rejectionReason());
        }
        if (finalized.detectedMediaType() != SupportedMediaType.PLAIN_TEXT) {
            throw new ConnectorResourceRefusedException("UNSUPPORTED_MEDIA_TYPE");
        }

        SourceOrigin origin = new SourceOrigin(
                calendar.connection().id(),
                grant.id(),
                event.id(),
                event.revision(),
                event.updated(),
                title(event),
                event.link() != null && event.link().startsWith("https://") && event.link().length() <= 2048 ? event.link() : null,
                SourceConversion.CALENDAR_EVENT_AS_TEXT);
        // Recorded and linked to the document in one step, whether or not the document went to the trash meanwhile (its
        // purge then removes both), and undone together if it was deleted for good. Empty when the person disconnected
        // while the copy was being made: then it is not kept as a source.
        SourceSnapshot snapshot = sourceService
                .attachImportedSnapshot(workspaceId, userId, documentId, finalized.id(), SourceKind.GOOGLE_CALENDAR, origin, readAt)
                .orElseThrow(() -> new ConnectionNotFoundException(ConnectorAccess.CALENDAR_EVENTS));
        if (snapshot.artifactId() != finalized.id()) {
            // Another import of the same version finished first; its copy is the one linked, and this one's bytes are cleared away unused.
            return new ImportOutcome(documentSourceService.attach(workspaceId, userId, documentId, snapshot.artifactId()), false);
        }
        DocumentSource link = documentSourceRepository.find(workspaceId, userId, documentId, snapshot.id())
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return new ImportOutcome(new AttachedSource(link, snapshot, finalized.displayFilename()), true);
    }

    private ResourceGrant primaryCalendarGrant(long workspaceId, long userId, UsableConnection calendar) {
        return grantRepository.grant(
                workspaceId, userId, calendar.connection().id(), ResourceGrantType.CALENDAR, PRIMARY_CALENDAR, PRIMARY_CALENDAR_NAME);
    }

    /** A token refused moments after it was issued means the person took Brownie's access away: recorded, and the person asked to connect again. */
    private <T> T read(UsableConnection calendar, Supplier<T> call) {
        try {
            return call.get();
        } catch (ProviderTokenRejectedException e) {
            throw connectorService.tokenRefusedDuringUse(calendar.connection());
        }
    }

    private static void requireWindow(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new InvalidCalendarRequestException("from and to are both required.");
        }
        if (from.isBefore(EARLIEST) || to.isAfter(LATEST)) {
            throw new InvalidCalendarRequestException("The window must lie between 1970 and 9999.");
        }
        if (Duration.between(from, to).compareTo(Duration.ofMinutes(1)) < 0) {
            throw new InvalidCalendarRequestException("to must be at least a minute after from.");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW.plus(CLOCK_CHANGE_ALLOWANCE)) > 0) {
            throw new InvalidCalendarRequestException("A window can be at most " + MAX_WINDOW.toDays() + " days long.");
        }
    }

    private static String title(CalendarEvent event) {
        String title = CalendarEventText.oneLine(event.summary());
        return title.isEmpty() ? null : firstCodePoints(title, MAX_TITLE_CODE_POINTS, Integer.MAX_VALUE);
    }

    /** The event's title as a file name: a slash would otherwise be read as a folder, and the name is cut to a length any page can show. */
    static String fileName(CalendarEvent event) {
        String title = CalendarEventText.oneLine(event.summary()).replace('/', '-').replace('\\', '-');
        String base = title.isEmpty() ? "Calendar event" : firstCodePoints(title, MAX_FILENAME_CODE_POINTS, MAX_FILENAME_BASE_CHARS).strip();
        return base + ".txt";
    }

    /** At most {@code maxCodePoints} characters and {@code maxChars} UTF-16 units, cut between characters, never through one. */
    private static String firstCodePoints(String text, int maxCodePoints, int maxChars) {
        int end = 0;
        int codePoints = 0;
        while (end < text.length() && codePoints < maxCodePoints) {
            int next = text.offsetByCodePoints(end, 1);
            if (next > maxChars) {
                break;
            }
            end = next;
            codePoints++;
        }
        return text.substring(0, end);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }
}
