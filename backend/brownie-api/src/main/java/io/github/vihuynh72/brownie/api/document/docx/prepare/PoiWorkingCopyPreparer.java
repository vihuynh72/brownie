package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxFeatureFinding;
import io.github.vihuynh72.brownie.core.document.DocxParseException;
import io.github.vihuynh72.brownie.core.document.DocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.document.UnsupportedDocxFeature;
import io.github.vihuynh72.brownie.core.prepare.PreparationMode;
import io.github.vihuynh72.brownie.core.prepare.PreparationNotice;
import io.github.vihuynh72.brownie.core.prepare.PreparedCopy;
import io.github.vihuynh72.brownie.core.prepare.WorkingCopyPreparationException;
import io.github.vihuynh72.brownie.core.prepare.WorkingCopyPreparer;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackageRelationship;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Makes the clean working copy of a Word file in seven steps, each over the
 * body, headers, footers, footnotes and endnotes:
 *
 * <ol>
 * <li>the package becomes an ordinary document, without macros, signature
 * or links to local files ({@link OoxmlPackageNormalizer});</li>
 * <li>tracked changes are accepted ({@link TrackedChangeAcceptor});</li>
 * <li>comments are left out ({@link CommentRemover});</li>
 * <li>fields that reach outside the file are frozen ({@link FieldNeutralizer});</li>
 * <li>embedded objects not on the allowed list become their pictures
 * ({@link EmbeddedObjectReducer});</li>
 * <li>wrappers around text, and content controls around whole blocks, are
 * taken away ({@link ControlUnwrapper});</li>
 * <li>editing restrictions are lifted ({@link EditingRestrictionRemover}).</li>
 * </ol>
 *
 * <p>The copy is then checked before it is handed on: it must hold no macro
 * project, control or signature part, and the reader must read it as a
 * supported document. What the reader reports the copy keeps as it is is
 * passed on as {@link PreparationNotice#KEPT_AS_IS} notices, one per kind.
 */
public final class PoiWorkingCopyPreparer implements WorkingCopyPreparer {

    private final DocxStructuralExtractor extractor;

    public PoiWorkingCopyPreparer() {
        this(new PoiDocxStructuralExtractor());
    }

    public PoiWorkingCopyPreparer(DocxStructuralExtractor extractor) {
        this.extractor = extractor;
    }

    @Override
    public PreparedCopy prepare(byte[] docx, PreparationMode mode) {
        List<PreparationNotice> notices = new ArrayList<>();
        byte[] cleaned;
        try (WordPackage word = WordPackage.open(docx)) {
            OoxmlPackageNormalizer.Result normalized = OoxmlPackageNormalizer.normalize(word, mode);
            int changes = TrackedChangeAcceptor.accept(word);
            int comments = CommentRemover.remove(word);
            int frozen = FieldNeutralizer.freeze(word);
            int pictures = EmbeddedObjectReducer.reduce(word);
            ControlUnwrapper.unwrap(word);
            int restrictions = EditingRestrictionRemover.remove(word);
            cleaned = word.save();

            add(notices, PreparationNotice.MACROS_REMOVED, normalized.macroParts());
            add(notices, PreparationNotice.SIGNATURE_REMOVED, normalized.signatureParts());
            add(notices, PreparationNotice.LINKED_CONTENT_REMOVED, normalized.links());
            add(notices, PreparationNotice.TRACKED_CHANGES_AND_COMMENTS, changes + comments);
            add(notices, PreparationNotice.FIELDS_FROZEN, frozen);
            add(notices, PreparationNotice.EMBEDDED_FILES_TO_PICTURES, pictures);
            add(notices, PreparationNotice.EDITING_RESTRICTION_REMOVED, restrictions);
        } catch (WorkingCopyPreparationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkingCopyPreparationException(
                    WorkingCopyPreparationException.Reason.DAMAGED, "The file could not be cleaned: " + e.getMessage(), e);
        }
        requireNoMacroOrSignatureParts(cleaned);
        notices.addAll(keptAsIs(cleaned));
        return new PreparedCopy(cleaned, notices);
    }

    private static void add(List<PreparationNotice> notices, String code, int count) {
        if (count > 0) {
            notices.add(new PreparationNotice(code, count, null));
        }
    }

    /** Reads the saved copy afresh, so what is checked is exactly what is handed on. */
    static void requireNoMacroOrSignatureParts(byte[] cleaned) {
        OPCPackage opc;
        try {
            opc = OPCPackage.open(new ByteArrayInputStream(cleaned));
        } catch (IOException | InvalidFormatException | RuntimeException e) {
            throw new WorkingCopyPreparationException(WorkingCopyPreparationException.Reason.NOT_CLEAN, "The copy could not be read back.", e);
        }
        try {
            for (PackagePart part : opc.getParts()) {
                String name = part.getPartName().getName().toLowerCase(Locale.ROOT);
                String contentType = part.getContentType().toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin") || name.startsWith("/word/activex/") || name.startsWith("/_xmlsignatures/")
                        || contentType.contains("vbaproject") || contentType.contains("activex") || contentType.contains("digital-signature")) {
                    throw notClean("The copy still holds " + part.getPartName().getName() + ".");
                }
            }
            for (PackageRelationship relationship : opc.getRelationships()) {
                String type = relationship.getRelationshipType();
                if (type != null && (type.contains("digital-signature") || type.endsWith("/vbaProject"))) {
                    throw notClean("The copy still carries a " + type + " relationship.");
                }
            }
        } catch (InvalidFormatException e) {
            throw new WorkingCopyPreparationException(WorkingCopyPreparationException.Reason.NOT_CLEAN, "The copy could not be read back.", e);
        } finally {
            opc.revert();
        }
    }

    private List<PreparationNotice> keptAsIs(byte[] cleaned) {
        DocxExtractionOutcome outcome;
        try {
            outcome = extractor.extract(new ByteArrayInputStream(cleaned));
        } catch (IOException | DocxParseException e) {
            throw new WorkingCopyPreparationException(
                    WorkingCopyPreparationException.Reason.NOT_CLEAN, "The copy could not be read as a Word document.", e);
        }
        if (!(outcome instanceof DocxExtractionOutcome.Supported supported)) {
            DocxExtractionOutcome.Unsupported unsupported = (DocxExtractionOutcome.Unsupported) outcome;
            throw notClean("The copy still holds " + unsupported.featureReport().refused().getFirst().feature()
                    + " at " + unsupported.featureReport().refused().getFirst().location() + ".");
        }
        Map<UnsupportedDocxFeature, Integer> counts = new LinkedHashMap<>();
        for (DocxFeatureFinding finding : supported.keptAsIs().keptAsIs()) {
            counts.merge(finding.feature(), 1, Integer::sum);
        }
        List<PreparationNotice> notices = new ArrayList<>();
        counts.forEach((feature, count) -> notices.add(new PreparationNotice(PreparationNotice.KEPT_AS_IS, count, feature.name())));
        return notices;
    }

    private static WorkingCopyPreparationException notClean(String message) {
        return new WorkingCopyPreparationException(WorkingCopyPreparationException.Reason.NOT_CLEAN, message);
    }
}
