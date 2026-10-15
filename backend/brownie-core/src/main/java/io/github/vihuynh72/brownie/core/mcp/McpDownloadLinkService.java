package io.github.vihuynh72.brownie.core.mcp;

import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Issues and claims single-use download links. Issuing refuses a file the
 * receipt does not carry, so a link is never minted for bytes that do not
 * exist. Claiming delegates the used/expired/revoked checks to the
 * database function that reads and marks the link in one statement, which
 * is also why claim needs no workspace context: the function is the
 * tenant boundary for this one anonymous read.
 */
public class McpDownloadLinkService {

    /** How long a link stays claimable: long enough to click through, short enough that a leaked link rots fast. */
    public static final Duration LINK_TTL = Duration.ofMinutes(10);

    private final McpDownloadLinkRepository downloadLinkRepository;
    private final Clock clock;

    public McpDownloadLinkService(McpDownloadLinkRepository downloadLinkRepository, Clock clock) {
        this.downloadLinkRepository = downloadLinkRepository;
        this.clock = clock;
    }

    public IssuedMcpDownload issue(long workspaceId, long userId, long grantId, ExportReceipt receipt, McpDownloadFile file) {
        boolean exists = switch (file) {
            case DOCX -> receipt.docxArtifactId() != null;
            case PDF -> receipt.pdfArtifactId() != null;
        };
        if (!exists) {
            throw new McpRequestInvalidException("Export " + receipt.id() + " has no " + file + " file.");
        }
        String token = McpTokenGeneration.newDownloadToken();
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plus(LINK_TTL);
        McpDownloadLink link = downloadLinkRepository.create(
                workspaceId, userId, grantId, receipt.id(), file, McpTokenGeneration.sha256Hex(token), expiresAt);
        return new IssuedMcpDownload(link.id(), token, expiresAt);
    }

    public Optional<ClaimedMcpDownload> claim(String presented) {
        if (presented == null || presented.isBlank()) {
            return Optional.empty();
        }
        return downloadLinkRepository.claim(McpTokenGeneration.sha256Hex(presented));
    }

    /** A minted link's one-time secret and its expiry, for the URL the tool hands back. */
    public record IssuedMcpDownload(long linkId, String token, OffsetDateTime expiresAt) {
    }
}
