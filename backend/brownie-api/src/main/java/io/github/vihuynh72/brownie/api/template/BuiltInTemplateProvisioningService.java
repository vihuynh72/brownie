package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplate;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Turns {@link BuiltInMinutesTemplateRegistry}'s fixed catalog into real,
 * activated {@code Template}/{@code TemplateVersion} rows in one
 * workspace, the first time that workspace needs them. Before this
 * existed, the registry was only ever read by tests -- nothing put a
 * built-in template in front of an actual user, so a brand-new workspace
 * had none to choose from at all. This runs the same
 * upload-then-extract-then-draft-then-bind-then-activate sequence a person
 * teaching a custom template would drive by hand through the API, just
 * with the source bytes and field definitions already known.
 */
@Service
public class BuiltInTemplateProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(BuiltInTemplateProvisioningService.class);

    private final ArtifactService artifactService;
    private final DocumentExtractionService documentExtractionService;
    private final TemplateService templateService;
    private final ArtifactRepository artifactRepository;
    private final BlobStore blobStore;

    public BuiltInTemplateProvisioningService(
            ArtifactService artifactService,
            DocumentExtractionService documentExtractionService,
            TemplateService templateService,
            ArtifactRepository artifactRepository,
            BlobStore blobStore) {
        this.artifactService = artifactService;
        this.documentExtractionService = documentExtractionService;
        this.templateService = templateService;
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
    }

    /**
     * Idempotent: does nothing once the workspace already has at least one
     * template, whether that is a previously provisioned built-in or one
     * the owner created themselves. Checking "does this workspace have any
     * template yet" rather than recording a one-time provisioning flag
     * keeps this self-healing -- a workspace left with zero templates after
     * a previous attempt failed partway (for example, the isolated
     * renderer was briefly unavailable) is retried the next time this
     * runs, instead of being permanently skipped.
     */
    public void ensureBuiltInTemplates(long workspaceId, long userId) {
        List<Template> existing = templateService.findAll(workspaceId, userId);
        if (!existing.isEmpty()) {
            repairUnreadableBuiltIns(workspaceId, userId, existing);
            return;
        }
        for (BuiltInMinutesTemplate builtIn : BuiltInMinutesTemplateRegistry.all()) {
            provision(workspaceId, userId, builtIn);
        }
    }

    /**
     * A built-in template whose rows exist but whose DOCX bytes are gone
     * from storage (a blob store reset, a lost volume) would otherwise
     * fail every compile, validation, preview and export of every
     * document made from it, forever, because "at least one template
     * exists" is all the check above looks at. The packaged bytes are
     * deterministic and the artifact row keeps their hash, so when the
     * stored object is missing and the packaged copy hashes to exactly
     * what the row recorded, the bytes are written back to the same key
     * and the template is whole again. A row whose hash does not match
     * is left alone and logged: it is not the packaged file.
     */
    private void repairUnreadableBuiltIns(long workspaceId, long userId, List<Template> templates) {
        for (BuiltInMinutesTemplate builtIn : BuiltInMinutesTemplateRegistry.all()) {
            for (Template template : templates) {
                if (!template.displayName().equals(builtIn.displayName()) || template.currentActiveVersionId() == null) {
                    continue;
                }
                Optional<TemplateVersion> active = templateService.findVersion(workspaceId, userId, template.id(), template.currentActiveVersionId());
                if (active.isEmpty()) {
                    continue;
                }
                Optional<Artifact> artifact = artifactRepository.find(workspaceId, userId, active.get().sourceArtifactId());
                if (artifact.isEmpty()) {
                    continue;
                }
                try {
                    if (blobStore.sizeOf(artifact.get().blobKey()).isPresent()) {
                        continue;
                    }
                    byte[] bytes = readPackagedTemplate(builtIn.id());
                    if (!sha256Hex(bytes).equals(artifact.get().sha256())) {
                        log.warn("Built-in template artifact {} in workspace {} has no stored bytes and the packaged copy does not match its recorded hash; not repaired.",
                                artifact.get().id(), workspaceId);
                        continue;
                    }
                    blobStore.writeNewAndDigest(artifact.get().blobKey(), new java.io.ByteArrayInputStream(bytes), bytes.length);
                    log.warn("Restored the missing stored bytes of built-in template artifact {} in workspace {} from the packaged copy.",
                            artifact.get().id(), workspaceId);
                } catch (IOException e) {
                    log.warn("Could not check or restore built-in template artifact {} in workspace {}.", artifact.get().id(), workspaceId, e);
                }
            }
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a JDK-guaranteed algorithm; this should be unreachable.", e);
        }
    }

    private void provision(long workspaceId, long userId, BuiltInMinutesTemplate builtIn) {
        byte[] bytes = readPackagedTemplate(builtIn.id());
        Artifact artifact = artifactService.initiateUpload(workspaceId, userId, builtIn.displayName() + ".docx");
        artifactService.receiveContent(workspaceId, userId, artifact.id(), new java.io.ByteArrayInputStream(bytes));
        artifactService.finalizeUpload(workspaceId, userId, artifact.id());
        documentExtractionService.extract(workspaceId, userId, artifact.id());

        Template template = templateService.createDraft(workspaceId, userId, builtIn.displayName(), artifact.id());
        TemplateVersion draft = templateService
                .findDraftVersion(workspaceId, userId, template.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Built-in template " + builtIn.id() + " has no draft version immediately after creating it."));
        TemplateVersion bound = templateService.replaceDraftBindings(
                workspaceId, userId, template.id(), draft.versionNumber(), builtIn.fields());
        templateService.activate(workspaceId, userId, template.id(), bound.versionNumber());
    }

    /**
     * Reads the same DOCX bytes {@link BuiltInMinutesTemplateRegistry}
     * names, but from this module's own packaged {@code
     * builtin-templates/} classpath resources rather than the
     * repository-relative {@code fixtures/} path the registry's own
     * javadoc describes. That path only resolves on a machine with the
     * full repository checked out (as every test environment has); a
     * deployed server ships just this module's built artifact, so the two
     * built-in DOCX files are duplicated here deliberately, as a real
     * application asset rather than a test fixture.
     */
    private static byte[] readPackagedTemplate(String builtInId) {
        String resourcePath = "builtin-templates/" + builtInId + ".docx";
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing packaged built-in template resource: " + resourcePath, e);
        }
    }
}
