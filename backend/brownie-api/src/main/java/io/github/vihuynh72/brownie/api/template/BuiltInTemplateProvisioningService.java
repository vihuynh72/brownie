package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.core.artifact.Artifact;
import io.github.vihuynh72.brownie.core.artifact.ArtifactRepository;
import io.github.vihuynh72.brownie.core.artifact.BlobStore;
import io.github.vihuynh72.brownie.core.artifact.ArtifactService;
import io.github.vihuynh72.brownie.core.document.DocumentExtractionService;
import io.github.vihuynh72.brownie.core.prepare.PdfFormPreparationService;
import io.github.vihuynh72.brownie.core.template.BuiltInTemplate;
import io.github.vihuynh72.brownie.core.template.BuiltInTemplateRegistry;
import io.github.vihuynh72.brownie.core.template.Template;
import io.github.vihuynh72.brownie.core.template.TemplateService;
import io.github.vihuynh72.brownie.core.template.TemplateVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Turns {@link BuiltInTemplateRegistry}'s fixed catalog into real,
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
    private final PdfFormPreparationService pdfFormPreparationService;

    public BuiltInTemplateProvisioningService(
            ArtifactService artifactService,
            DocumentExtractionService documentExtractionService,
            TemplateService templateService,
            ArtifactRepository artifactRepository,
            BlobStore blobStore,
            PdfFormPreparationService pdfFormPreparationService) {
        this.artifactService = artifactService;
        this.documentExtractionService = documentExtractionService;
        this.templateService = templateService;
        this.artifactRepository = artifactRepository;
        this.blobStore = blobStore;
        this.pdfFormPreparationService = pdfFormPreparationService;
    }

    /**
     * Idempotent: once the workspace has at least one template, whether a
     * previously provisioned built-in or one the owner created themselves,
     * nothing new is given to it. Checking "does this workspace have any
     * template yet" rather than recording a one-time provisioning flag
     * keeps this self-healing: a workspace left with zero templates after
     * a previous attempt failed is retried the next time this runs.
     *
     * <p>An attempt can also fail after it has written something: the last
     * step renders the template, and the renderer can be down, or busy for
     * longer than anyone waits. What that leaves behind is a built-in that
     * was never activated, which nobody can make a document from and which,
     * being a template, used to stop every later attempt. So every draft is
     * created before anything is activated, which makes such a draft the
     * sign of an interrupted attempt wherever it stopped; and finding one,
     * this finishes it and creates whichever built-ins are still missing.
     * Without that sign a missing built-in is left missing: the owner may
     * have removed it.
     *
     * <p>Templates in the Trash Bin count here like any other. A built-in the
     * owner moved to the Trash Bin is therefore present, not missing, and is
     * never created again behind their back; nor does a workspace whose
     * every template is in the Trash Bin count as one that has none.
     */
    public void ensureBuiltInTemplates(long workspaceId, long userId) {
        // Two sign-ins of the same new person at the same moment (two tabs, two devices) would each find no
        // templates and each create them all. One goes first; the other then finds them there. Within this
        // process only, like the request limits: a second API instance would need this in the database.
        synchronized (TURNS[(int) Math.floorMod(workspaceId, (long) TURNS.length)]) {
            List<Template> existing = templateService.findAllIncludingTrashed(workspaceId, userId);
            if (existing.isEmpty()) {
                provision(workspaceId, userId, BuiltInTemplateRegistry.forNewWorkspace(), new LinkedHashMap<>());
                return;
            }
            if (finishInterruptedProvisioning(workspaceId, userId, existing)) {
                existing = templateService.findAllIncludingTrashed(workspaceId, userId);
            }
            repairUnreadableBuiltIns(workspaceId, userId, existing);
        }
    }

    /**
     * Provisions the retired built-ins a workspace predates -- the entries
     * {@code forNewWorkspace} leaves out so a new account never sees them.
     * Repair keeps their blobs readable once provisioned; this is the "an
     * existing workspace still has them" half of the same rule.
     */
    public void ensureRetiredBuiltInTemplates(long workspaceId, long userId) {
        synchronized (TURNS[(int) Math.floorMod(workspaceId, (long) TURNS.length)]) {
            java.util.Set<String> names = new java.util.HashSet<>();
            templateService.findAllIncludingTrashed(workspaceId, userId)
                    .forEach(template -> names.add(template.displayName()));
            java.util.List<BuiltInTemplate> missing = BuiltInTemplateRegistry.all().stream()
                    .filter(BuiltInTemplate::retired)
                    .filter(builtIn -> !names.contains(builtIn.displayName()))
                    .toList();
            if (!missing.isEmpty()) {
                provision(workspaceId, userId, missing, new LinkedHashMap<>());
            }
        }
    }

    private static final Object[] TURNS = new Object[64];

    static {
        for (int i = 0; i < TURNS.length; i++) {
            TURNS[i] = new Object();
        }
    }

    private boolean finishInterruptedProvisioning(long workspaceId, long userId, List<Template> existing) {
        Map<BuiltInTemplate, Template> neverActivated = new LinkedHashMap<>();
        List<BuiltInTemplate> missing = new ArrayList<>();
        for (BuiltInTemplate builtIn : BuiltInTemplateRegistry.forNewWorkspace()) {
            boolean present = false;
            for (Template template : existing) {
                if (!template.displayName().equals(builtIn.displayName())) {
                    continue;
                }
                present = true;
                if (isNeverActivatedBuiltIn(workspaceId, userId, template, builtIn)) {
                    neverActivated.putIfAbsent(builtIn, template);
                }
            }
            if (!present) {
                missing.add(builtIn);
            }
        }
        if (neverActivated.isEmpty()) {
            return false;
        }
        log.warn("Workspace {} has {} built-in template(s) that were never activated; finishing what an earlier attempt started.",
                workspaceId, neverActivated.size());
        provision(workspaceId, userId, missing, neverActivated);
        return true;
    }

    /**
     * The name alone is not enough: the owner may have a draft of their own
     * under the same name, and finishing that would overwrite their fields
     * with the built-in's. Only a draft made from exactly the packaged file
     * is one of these.
     */
    private boolean isNeverActivatedBuiltIn(long workspaceId, long userId, Template template, BuiltInTemplate builtIn) {
        if (template.currentActiveVersionId() != null) {
            return false;
        }
        Optional<TemplateVersion> draft = templateService.findDraftVersion(workspaceId, userId, template.id());
        if (draft.isEmpty()) {
            return false;
        }
        Optional<Artifact> source = artifactRepository.find(workspaceId, userId, draft.get().sourceArtifactId());
        return source.isPresent() && sha256Hex(readPackagedTemplate(builtIn)).equals(source.get().sha256());
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
        for (BuiltInTemplate builtIn : BuiltInTemplateRegistry.all()) {
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
                    byte[] bytes = readPackagedTemplate(builtIn);
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

    /** Creates a draft for each of {@code toCreate}, then activates those and every draft in {@code alreadyDrafted}. */
    private void provision(
            long workspaceId,
            long userId,
            List<BuiltInTemplate> toCreate,
            Map<BuiltInTemplate, Template> alreadyDrafted) {
        Map<BuiltInTemplate, Template> drafts = new LinkedHashMap<>(alreadyDrafted);
        for (BuiltInTemplate builtIn : toCreate) {
            drafts.put(builtIn, createDraft(workspaceId, userId, builtIn));
        }
        for (Map.Entry<BuiltInTemplate, Template> draft : drafts.entrySet()) {
            bindAndActivate(workspaceId, userId, draft.getValue(), draft.getKey());
        }
    }

    private Template createDraft(long workspaceId, long userId, BuiltInTemplate builtIn) {
        byte[] bytes = readPackagedTemplate(builtIn);
        Artifact artifact = artifactService.initiateUpload(workspaceId, userId, builtIn.displayName() + "." + builtIn.extension());
        artifactService.receiveContent(workspaceId, userId, artifact.id(), new java.io.ByteArrayInputStream(bytes));
        artifactService.finalizeUpload(workspaceId, userId, artifact.id());
        documentExtractionService.extract(workspaceId, userId, artifact.id());
        if ("pdf".equals(builtIn.extension())) {
            pdfFormPreparationService.prepare(workspaceId, userId, artifact.id());
        }
        return templateService.createDraft(workspaceId, userId, builtIn.displayName(), artifact.id());
    }

    private void bindAndActivate(long workspaceId, long userId, Template template, BuiltInTemplate builtIn) {
        TemplateVersion draft = templateService
                .findDraftVersion(workspaceId, userId, template.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Built-in template " + builtIn.id() + " has no draft version to activate."));
        TemplateVersion bound = templateService.replaceDraftBindings(
                workspaceId, userId, template.id(), draft.versionNumber(), builtIn.fields());
        templateService.activate(workspaceId, userId, template.id(), bound.versionNumber());
    }

    /**
     * Reads {@link BuiltInTemplateRegistry}'s source file for a built-in
     * from this module's own packaged {@code builtin-templates/}
     * classpath resources: the extension on the catalog entry names it
     * ("docx", "pdf"). A deployed server ships just this module's built
     * artifact, so the files live here as real application assets.
     */
    private static byte[] readPackagedTemplate(BuiltInTemplate builtIn) {
        String resourcePath = "builtin-templates/" + builtIn.id() + "." + builtIn.extension();
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Missing packaged built-in template resource: " + resourcePath, e);
        }
    }
}
