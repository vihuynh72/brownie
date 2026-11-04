package io.github.vihuynh72.brownie.api.form;

import io.github.vihuynh72.brownie.core.form.CatalogReadyRepository;
import io.github.vihuynh72.brownie.core.form.CatalogReadyRepository.ReadyForm;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The form library's kept agency bytes: a fresh copy lets a start take the
 * form in without downloading it, and after {@link #TRUSTED_FOR} the bytes
 * are re-read from the agency -- the copy is replaced only when they changed.
 */
@Service
public class CatalogReadyService {

    /** A kept copy is trusted for three days, then re-read against the agency. */
    static final Duration TRUSTED_FOR = Duration.ofDays(3);

    private final CatalogReadyRepository readyRepository;

    public CatalogReadyService(CatalogReadyRepository readyRepository) {
        this.readyRepository = readyRepository;
    }

    /** The kept bytes fresh enough to use without asking the agency, or empty. */
    public Optional<ReadyForm> fresh(String catalogId) {
        return readyRepository.find(catalogId)
                .filter(r -> r.checkedAt() != null
                        && r.checkedAt().isAfter(OffsetDateTime.now().minus(TRUSTED_FOR)));
    }

    /** Any kept copy, fresh or not, for the re-check's compare. */
    public Optional<ReadyForm> find(String catalogId) {
        return readyRepository.find(catalogId);
    }

    /** Keeps the agency's fetched bytes under the catalog id. */
    public void remember(String catalogId, byte[] sourceBytes) {
        readyRepository.remember(new ReadyForm(catalogId, sourceBytes, sha256(sourceBytes), OffsetDateTime.now()));
    }

    /** Stamps the re-check when the agency's bytes came back unchanged. */
    public void restamp(String catalogId) {
        readyRepository.restamp(catalogId);
    }

    /** True when fetched bytes are what the kept copy was made from. */
    public boolean sameBytes(ReadyForm ready, byte[] sourceBytes) {
        return ready.sourceSha256() != null && ready.sourceSha256().equals(sha256(sourceBytes));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
