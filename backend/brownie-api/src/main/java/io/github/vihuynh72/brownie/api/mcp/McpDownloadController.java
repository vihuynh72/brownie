package io.github.vihuynh72.brownie.api.mcp;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.artifact.ReadableArtifact;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import io.github.vihuynh72.brownie.core.export.ExportRepository;
import io.github.vihuynh72.brownie.core.mcp.ClaimedMcpDownload;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadFile;
import io.github.vihuynh72.brownie.core.mcp.McpDownloadLinkService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The anonymous one-time download a chat app's export link names. The
 * link's presented token is the whole credential: claimed atomically by
 * digest, so unknown, expired, already-used, or revoked-grant links all
 * answer the same 404. The receipt's recorded hash is checked against the
 * stored bytes before a byte is served, and the reads run as the grant's
 * owner -- the member identity the claim returned -- so row-level rules
 * apply exactly as they do inside a tool call.
 */
@RestController
@ConditionalOnProperty(name = "brownie.mcp.enabled", havingValue = "true")
class McpDownloadController {

    private static final Logger log = LoggerFactory.getLogger(McpDownloadController.class);
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final McpDownloadLinkService downloadLinkService;
    private final ExportRepository exportRepository;
    private final ArtifactService artifactService;

    McpDownloadController(
            McpDownloadLinkService downloadLinkService,
            ExportRepository exportRepository,
            ArtifactService artifactService) {
        this.downloadLinkService = downloadLinkService;
        this.exportRepository = exportRepository;
        this.artifactService = artifactService;
    }

    @GetMapping("/api/v1/mcp-downloads/{token}")
    void download(@PathVariable String token, HttpServletResponse response) throws IOException {
        ClaimedMcpDownload claimed = downloadLinkService
                .claim(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ExportReceipt receipt = exportRepository
                .find(claimed.workspaceId(), claimed.userId(), claimed.exportReceiptId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Long artifactId = switch (claimed.file()) {
            case DOCX -> receipt.docxArtifactId();
            case PDF -> receipt.pdfArtifactId();
        };
        String expectedSha256 = switch (claimed.file()) {
            case DOCX -> receipt.docxSha256();
            case PDF -> receipt.pdfSha256();
        };
        if (artifactId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try (ReadableArtifact readable = artifactService.openContent(claimed.workspaceId(), claimed.userId(), artifactId)) {
            Artifact artifact = readable.artifact();
            if (!artifact.sha256().equals(expectedSha256)) {
                // The receipt names these bytes exactly; a mismatch means the
                // stored object is not what was exported, and serving it
                // would hand the caller a file nobody signed off on.
                log.error("Export receipt {} names artifact {} but its stored bytes no longer match.", receipt.id(), artifactId);
                throw new IllegalStateException("Stored export content no longer matches its receipt.");
            }
            response.setContentType(artifact.detectedMediaType().mimeType());
            response.setContentLengthLong(artifact.byteCount());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
            response.setHeader(
                    HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment()
                            .filename(downloadFilename(artifact), StandardCharsets.UTF_8)
                            .build()
                            .toString());
            copy(readable.content(), response.getOutputStream(), artifactId);
        }
    }

    private static String downloadFilename(Artifact artifact) {
        if (artifact.displayFilename() != null && !artifact.displayFilename().isBlank()) {
            return artifact.displayFilename();
        }
        return "artifact-" + artifact.id() + "." + artifact.detectedMediaType().defaultFileExtension();
    }

    /**
     * The same manual copy the artifact route uses: a read failure is the
     * stored object being unreadable and is logged; a write failure is the
     * client going away mid-download and is routine.
     */
    private static void copy(InputStream content, OutputStream out, long artifactId) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        while (true) {
            int read;
            try {
                read = content.read(buffer);
            } catch (IOException e) {
                log.error("Failed to read stored content for artifact {} while serving it.", artifactId, e);
                return;
            }
            if (read == -1) {
                return;
            }
            try {
                out.write(buffer, 0, read);
            } catch (IOException e) {
                log.debug("Client disconnected while receiving artifact {} content.", artifactId, e);
                return;
            }
        }
    }
}
