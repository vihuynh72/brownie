package io.github.vihuynh72.brownie.core.connector;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ArtifactStatus;
import io.github.vihuynh72.brownie.core.artifact.ArtifactTooLargeException;
import io.github.vihuynh72.brownie.core.artifact.SupportedMediaType;
import io.github.vihuynh72.brownie.core.artifact.UnsupportedArtifactTypeException;
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
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Reading files a person picked in their Google Drive into Brownie, as text
 * sources: a Google Doc as the plain text Google's own export of it produces,
 * a plain-text file as its bytes. Nothing else.
 *
 * <p>Picking and reading are separate. Google's own picker sends the ids of
 * what the person chose back beside the consent's code; each is described
 * with the access token of that same consent, and a Google Doc or a
 * plain-text file becomes a choice Brownie may read (a read-only grant). The
 * permission Brownie holds opens only files the person has given it, and ids
 * are taken only from that single-use return, never from a request, so every
 * grant records a pick.
 *
 * <p>A copy is made from a grant, never from an id a request supplies, and in
 * an order that asks Google nothing for a document in the trash or a choice
 * that is not the person's, reads nothing that what Drive says of the file
 * already refuses, and records the version that was actually read: Drive is
 * asked again after the read, and a file that changed meanwhile is not kept.
 * The bytes then go through exactly what an upload goes through (classified
 * by what they are, scanned) and become an ordinary artifact. The same
 * version of a file is copied once; a newer version with the same text links
 * the copy already made; a changed file is a new copy, and the old one stays
 * wherever it is cited. A file Google says is gone, or no longer Brownie's to
 * open, stops being offered. Everything happens during the request that
 * asked for it, and the access token is never kept.
 */
public class DriveImportService {

    /** The most files one pick adds; the ids past it are never sent to Drive. */
    public static final int MAX_FILES_PER_PICK = 10;

    /** Drive file ids are letters, digits, '-' and '_'. Anything else is not one, whatever arrived beside the code. */
    private static final Pattern DRIVE_FILE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,256}$");
    /** Characters that reorder what follows them on screen: in a name someone else chose, they could make it read as something else. */
    private static final Pattern BIDI_CONTROLS = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Set<String> DRIVE_HOSTS = Set.of("docs.google.com", "drive.google.com");
    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 255;
    private static final int MAX_TITLE_CODE_POINTS = 500;
    private static final int MAX_LINK_CHARS = 2048;
    private static final int MAX_VERSION_CHARS = 255;
    private static final String UNNAMED_FILE = "Google Drive file";

    /** Why a pick stopped before every file was checked, when it was not Drive merely failing to answer. */
    public enum PickStop {
        /** Google refused the pick's own access token: the connection must be made again. */
        TOKEN_REFUSED,
        /** The organization that manages the account does not allow Drive apps. */
        BLOCKED_BY_ORGANIZATION,
        /** Brownie's own setup, such as the Drive API not being enabled for it. */
        NOT_CONFIGURED
    }

    /**
     * How one pick went: how many of the files picked are on the person's list
     * now (one already there counts too), and why the rest are not: a kind
     * Brownie does not read, one Drive would not describe, one left unchecked,
     * and ids past {@link #MAX_FILES_PER_PICK}. {@code stoppedBy} says why the
     * unchecked ones were not checked, or is null when Drive simply stopped
     * answering or nothing stopped the pick. What was recorded before a stop
     * stays recorded, because the consent had already completed.
     */
    public record PickOutcome(int added, int unsupported, int unavailable, int unchecked, int overLimit, PickStop stoppedBy) {

        public PickOutcome(int added, int unsupported, int unavailable, int unchecked, int overLimit) {
            this(added, unsupported, unavailable, unchecked, overLimit, null);
        }
    }

    /** The document's source for the file, and whether this import made a new copy or linked one already made. */
    public record ImportOutcome(AttachedSource source, boolean newCopy) {
    }

    private final ConnectorService connectorService;
    private final ResourceGrantRepository grantRepository;
    private final DriveFileReader driveFileReader;
    private final ArtifactService artifactService;
    private final SourceService sourceService;
    private final SourceSnapshotRepository sourceSnapshotRepository;
    private final DocumentSourceService documentSourceService;
    private final DocumentSourceRepository documentSourceRepository;
    private final RevisionService revisionService;
    private final int maxSourceBytes;

    public DriveImportService(
            ConnectorService connectorService,
            ResourceGrantRepository grantRepository,
            DriveFileReader driveFileReader,
            ArtifactService artifactService,
            SourceService sourceService,
            SourceSnapshotRepository sourceSnapshotRepository,
            DocumentSourceService documentSourceService,
            DocumentSourceRepository documentSourceRepository,
            RevisionService revisionService,
            long maxSourceBytes) {
        if (maxSourceBytes < 1) {
            throw new IllegalArgumentException("maxSourceBytes must be positive.");
        }
        this.connectorService = connectorService;
        this.grantRepository = grantRepository;
        this.driveFileReader = driveFileReader;
        this.artifactService = artifactService;
        this.sourceService = sourceService;
        this.sourceSnapshotRepository = sourceSnapshotRepository;
        this.documentSourceService = documentSourceService;
        this.documentSourceRepository = documentSourceRepository;
        this.revisionService = revisionService;
        this.maxSourceBytes = (int) Math.min(Integer.MAX_VALUE, maxSourceBytes);
    }

    /**
     * Records what the person just picked, through the Drive connection the
     * same consent completed, with that consent's own access token. Only the
     * first {@link #MAX_FILES_PER_PICK} distinct well-formed ids are sent to
     * Drive; the rest, and anything that is not an id, never are. Stops at
     * the first sign that Drive cannot be reached, so a slow Drive cannot hold
     * the person on the way back, and at a refusal that would refuse every
     * other file too, which the outcome names rather than throws: the consent
     * has completed by then, and the person must be told what it left.
     */
    public PickOutcome recordPicks(long workspaceId, long userId, UsableConnection drive, List<String> pickedIds) {
        if (drive.connection().access() != ConnectorAccess.DRIVE_FILES) {
            throw new IllegalArgumentException("Files are picked through a Drive connection.");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String id : pickedIds) {
            if (id != null && DRIVE_FILE_ID.matcher(id).matches()) {
                distinct.add(id);
            }
        }
        List<String> toCheck = new ArrayList<>(distinct).subList(0, Math.min(distinct.size(), MAX_FILES_PER_PICK));
        int overLimit = distinct.size() - toCheck.size();
        int added = 0;
        int unsupported = 0;
        int unavailable = 0;
        for (int i = 0; i < toCheck.size(); i++) {
            String id = toCheck.get(i);
            DriveFile file;
            try {
                file = driveFileReader.describeFile(drive.accessToken(), id);
            } catch (ConnectorResourceUnavailableException e) {
                unavailable++;
                continue;
            } catch (ProviderUnavailableException e) {
                return new PickOutcome(added, unsupported, unavailable, toCheck.size() - i, overLimit);
            } catch (ProviderTokenRejectedException e) {
                // Recorded, so the connection asks to be made again; returned rather than thrown, so the person hears what was added.
                connectorService.tokenRefusedDuringUse(drive.connection());
                return new PickOutcome(added, unsupported, unavailable, toCheck.size() - i, overLimit, PickStop.TOKEN_REFUSED);
            } catch (ConnectorBlockedByOrganizationException e) {
                return new PickOutcome(added, unsupported, unavailable, toCheck.size() - i, overLimit, PickStop.BLOCKED_BY_ORGANIZATION);
            } catch (ProviderMisconfiguredException | ConnectorNotConfiguredException e) {
                return new PickOutcome(added, unsupported, unavailable, toCheck.size() - i, overLimit, PickStop.NOT_CONFIGURED);
            }
            if (!id.equals(file.id())) {
                // Drive answered about some other file: nothing is recorded that the person did not pick.
                unavailable++;
                continue;
            }
            if (!file.isGoogleDoc() && !file.isPlainText()) {
                unsupported++;
                continue;
            }
            grantRepository.grant(workspaceId, userId, drive.connection().id(), ResourceGrantType.DRIVE_FILE, id, displayName(file.name()));
            added++;
        }
        return new PickOutcome(added, unsupported, unavailable, 0, overLimit);
    }

    /** Stops Brownie reading one picked file. What was already copied from it stays wherever it is used. */
    public ResourceGrant forget(long workspaceId, long userId, long grantId) {
        ResourceGrant grant = openDriveGrant(workspaceId, userId, grantId);
        return grantRepository
                .revoke(workspaceId, userId, grant.id(), GrantRevocationReason.REMOVED)
                .orElseThrow(() -> new ConnectorResourceNotFoundException(grantId));
    }

    /** Copies one picked file into a document as a source, or links the copy already made of it. */
    public ImportOutcome importFile(long workspaceId, long userId, long documentId, long grantId) {
        // Before anything is asked of Google, a token refresh included: a document that is not there, or is in the
        // trash, takes nothing in; and only a file this person picked, and has not taken back, is read.
        revisionService.findDocument(workspaceId, userId, documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
        ResourceGrant grant = openDriveGrant(workspaceId, userId, grantId);
        UsableConnection drive = connectorService.use(workspaceId, userId, ConnectorAccess.DRIVE_FILES);
        if (drive.connection().id() != grant.connectionId()) {
            // Picked through a connection that has since been replaced.
            throw new ConnectorResourceNotFoundException(grantId);
        }

        DriveFile file = describe(workspaceId, userId, drive, grant);
        String version = file.version();
        if (version.isBlank() || version.length() > MAX_VERSION_CHARS) {
            throw new ProviderUnavailableException("Google Drive's answer did not say which version of the file it was.");
        }
        Optional<SourceSnapshot> sameVersion = sourceSnapshotRepository.findImported(workspaceId, userId, grant.id(), file.id(), version);
        if (sameVersion.isPresent()) {
            return linkEarlierCopy(workspaceId, userId, documentId, grant, sameVersion.get());
        }
        // A Doc's stated size is not the size of its text, so only a text file's is used, and only to refuse early.
        if (file.isPlainText() && file.size() != null && file.size() > maxSourceBytes) {
            throw new ConnectorResourceTooLargeException(ConnectorAccess.DRIVE_FILES);
        }

        byte[] content = fromDrive(workspaceId, userId, drive, grant, () -> file.isGoogleDoc()
                ? driveFileReader.readGoogleDocAsText(drive.accessToken(), file.id(), maxSourceBytes)
                : driveFileReader.readTextFile(drive.accessToken(), file.id(), maxSourceBytes));
        Instant readAt = Instant.now();
        if (content == null) {
            throw new ProviderUnavailableException("Google Drive sent no content.");
        }
        if (content.length > maxSourceBytes) {
            throw new ConnectorResourceTooLargeException(ConnectorAccess.DRIVE_FILES);
        }
        if (!isUtf8(content)) {
            throw new ConnectorResourceUnsupportedException(ConnectorResourceUnsupportedException.Reason.NOT_UTF8);
        }
        // Asked again, so that the version recorded is the one that was read, and a restriction or the trash that
        // arrived during the read is respected.
        DriveFile after = describe(workspaceId, userId, drive, grant);
        if (!version.equals(after.version())) {
            throw new ConnectorResourceUnavailableException(ConnectorResourceUnavailableException.Reason.CHANGED_DURING_COPY, ConnectorAccess.DRIVE_FILES);
        }

        // A newer version whose text is unchanged (a comment added, the file moved) is not a new copy.
        Optional<SourceSnapshot> sameText =
                sourceSnapshotRepository.findLatestImportedWithContent(workspaceId, userId, grant.id(), file.id(), sha256Hex(content));
        if (sameText.isPresent()) {
            return linkEarlierCopy(workspaceId, userId, documentId, grant, sameText.get());
        }

        Artifact artifact = artifactService.initiateUpload(workspaceId, userId, fileName(file));
        try {
            artifactService.receiveContent(workspaceId, userId, artifact.id(), new ByteArrayInputStream(content));
        } catch (UnsupportedArtifactTypeException e) {
            // Text with a NUL byte in it, or a "text" file that is really a broken package: refused as an upload would be.
            throw new ConnectorResourceRefusedException("UNSUPPORTED_MEDIA_TYPE");
        } catch (ArtifactTooLargeException e) {
            throw new ConnectorResourceRefusedException("DECOMPRESSION_LIMIT_EXCEEDED");
        }
        Artifact finalized = artifactService.finalizeUpload(workspaceId, userId, artifact.id());
        if (finalized.status() != ArtifactStatus.READY) {
            throw new ConnectorResourceRefusedException(finalized.rejectionReason());
        }
        // Classified by its bytes, like any upload: a "text" file that is really something else is not kept as text.
        if (finalized.detectedMediaType() != SupportedMediaType.PLAIN_TEXT) {
            throw new ConnectorResourceRefusedException("UNSUPPORTED_MEDIA_TYPE");
        }

        SourceOrigin origin = new SourceOrigin(
                drive.connection().id(),
                grant.id(),
                file.id(),
                version,
                file.modifiedTime(),
                title(file.name()),
                link(file.webViewLink()),
                file.isGoogleDoc() ? SourceConversion.GOOGLE_DOC_AS_TEXT : null);
        // Recorded and linked in one step. Empty when the person disconnected, or took this file back, while the copy
        // was being made: then it is not kept, and the file is no longer on their list.
        SourceSnapshot snapshot = sourceService
                .attachImportedSnapshot(workspaceId, userId, documentId, finalized.id(), SourceKind.GOOGLE_DRIVE, origin, readAt)
                .orElseThrow(() -> new ConnectorResourceNotFoundException(grantId));
        if (snapshot.artifactId() != finalized.id()) {
            // Another import of the same version finished first; its copy is the one linked, and this one's bytes are cleared away unused.
            return new ImportOutcome(documentSourceService.attach(workspaceId, userId, documentId, snapshot.artifactId()), false);
        }
        DocumentSource link = documentSourceRepository.find(workspaceId, userId, documentId, snapshot.id())
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return new ImportOutcome(new AttachedSource(link, snapshot, finalized.displayFilename()), true);
    }

    /**
     * Links a copy made earlier through this choice. The choice is asked about
     * again first, so one taken back, or disconnected, while Drive was being
     * read links nothing, as a new copy would not be recorded then either.
     */
    private ImportOutcome linkEarlierCopy(long workspaceId, long userId, long documentId, ResourceGrant grant, SourceSnapshot earlier) {
        openDriveGrant(workspaceId, userId, grant.id());
        return new ImportOutcome(documentSourceService.attach(workspaceId, userId, documentId, earlier.artifactId()), false);
    }

    private ResourceGrant openDriveGrant(long workspaceId, long userId, long grantId) {
        return grantRepository
                .find(workspaceId, userId, grantId)
                .filter(grant -> grant.isOpen() && grant.type() == ResourceGrantType.DRIVE_FILE)
                .orElseThrow(() -> new ConnectorResourceNotFoundException(grantId));
    }

    /** What Drive says of the granted file now, refused when it is not one Brownie copies as it stands. */
    private DriveFile describe(long workspaceId, long userId, UsableConnection drive, ResourceGrant grant) {
        DriveFile file = fromDrive(workspaceId, userId, drive, grant, () -> driveFileReader.describeFile(drive.accessToken(), grant.externalId()));
        if (file == null || !grant.externalId().equals(file.id())) {
            throw new ProviderUnavailableException("Google Drive answered about a different file.");
        }
        if (file.trashed()) {
            throw new ConnectorResourceUnavailableException(ConnectorResourceUnavailableException.Reason.TRASHED, ConnectorAccess.DRIVE_FILES);
        }
        if (!file.isGoogleDoc() && !file.isPlainText()) {
            throw new ConnectorResourceUnsupportedException(ConnectorResourceUnsupportedException.Reason.TYPE);
        }
        if (!file.canDownload()) {
            throw new ConnectorResourceUnavailableException(
                    ConnectorResourceUnavailableException.Reason.DOWNLOAD_RESTRICTED, ConnectorAccess.DRIVE_FILES);
        }
        return file;
    }

    /**
     * One call to Drive about a granted file. A file Drive says is gone, or no
     * longer Brownie's to open, is taken off the person's list; a token
     * refused moments after it was issued means the person took Brownie's
     * access away, which is recorded, and they are asked to connect again.
     */
    private <T> T fromDrive(long workspaceId, long userId, UsableConnection drive, ResourceGrant grant, Supplier<T> call) {
        try {
            return call.get();
        } catch (ConnectorResourceUnavailableException e) {
            if (e.reason() == ConnectorResourceUnavailableException.Reason.GONE
                    || e.reason() == ConnectorResourceUnavailableException.Reason.ACCESS_LOST) {
                grantRepository.revoke(workspaceId, userId, grant.id(), GrantRevocationReason.PROVIDER_ACCESS_LOST);
            }
            throw e;
        } catch (ConnectorResourceTooLargeException e) {
            throw new ConnectorResourceTooLargeException(ConnectorAccess.DRIVE_FILES);
        } catch (ProviderTokenRejectedException e) {
            // Disconnecting takes the token back at Google first, so a copy that meets that refusal is told the file is off
            // the list, not asked to connect again.
            if (grantRepository.find(workspaceId, userId, grant.id()).filter(ResourceGrant::isOpen).isEmpty()) {
                throw new ConnectorResourceNotFoundException(grant.id());
            }
            throw connectorService.tokenRefusedDuringUse(drive.connection());
        }
    }

    /** Drive's name for the file as the person's list shows it: one line, nothing that reorders it on screen, cut; null when nothing is left. */
    static String displayName(String name) {
        String cleaned = BIDI_CONTROLS.matcher(CalendarEventText.oneLine(name)).replaceAll("").strip();
        return cleaned.isEmpty() ? null : ImportedNames.firstCodePoints(cleaned, MAX_DISPLAY_NAME_CODE_POINTS, Integer.MAX_VALUE).strip();
    }

    private static String title(String name) {
        String cleaned = BIDI_CONTROLS.matcher(CalendarEventText.oneLine(name)).replaceAll("").strip();
        return cleaned.isEmpty() ? null : ImportedNames.firstCodePoints(cleaned, MAX_TITLE_CODE_POINTS, Integer.MAX_VALUE).strip();
    }

    /** The copy is text whatever it was in Drive, so its name ends in .txt once, never twice. */
    static String fileName(DriveFile file) {
        String name = file.name() == null ? "" : BIDI_CONTROLS.matcher(file.name()).replaceAll("").strip();
        if (name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            name = name.substring(0, name.length() - ".txt".length());
        }
        return ImportedNames.textFileName(name, UNNAMED_FILE);
    }

    /** Where the file opens in Drive, kept only as an https address on Google's Docs or Drive site. */
    static String link(String webViewLink) {
        if (webViewLink == null || webViewLink.length() > MAX_LINK_CHARS || !webViewLink.startsWith("https://")) {
            return null;
        }
        try {
            URI uri = new URI(webViewLink);
            return uri.getRawUserInfo() == null && uri.getPort() == -1 && uri.getHost() != null
                    && DRIVE_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) ? webViewLink : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Strict UTF-8: a byte order mark is allowed, a malformed or overlong sequence or an encoded surrogate is not. */
    static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }
}
