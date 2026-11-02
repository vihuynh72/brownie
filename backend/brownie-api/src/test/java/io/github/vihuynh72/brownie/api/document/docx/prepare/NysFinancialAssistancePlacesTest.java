package io.github.vihuynh72.brownie.api.document.docx.prepare;

import io.github.vihuynh72.brownie.api.document.docx.PoiDocxStructuralExtractor;
import io.github.vihuynh72.brownie.core.prepare.FillSpotCandidateFinder;
import io.github.vihuynh72.brownie.core.prepare.FormOutline;
import io.github.vihuynh72.brownie.core.prepare.FoundSpots;
import io.github.vihuynh72.brownie.core.prepare.PreparationMode;
import io.github.vihuynh72.brownie.core.prepare.SpotCandidate;
import io.github.vihuynh72.brownie.core.prepare.WordForms;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** The real NYS financial-assistance application: its fill-in table cells all become places. */
class NysFinancialAssistancePlacesTest {

    private static final PoiDocxStructuralExtractor EXTRACTOR = new PoiDocxStructuralExtractor();

    @Test
    void theMainSectionCellsBecomePlaces() throws Exception {
        WordForms.ReadForm read = new PoiWordForms(EXTRACTOR.parserVersion())
                .read(new PoiWorkingCopyPreparer()
                        .prepare(PoiWordFormsTest.sampleForm("nys-financial-assistance-2026.docx"),
                                PreparationMode.UPLOAD).docxBytes());
        FoundSpots spots = FillSpotCandidateFinder.find(read.outline());
        List<String> labels = spots.candidates().stream().map(SpotCandidate::rulesLabel).toList();
        System.err.println("NYS-PLACES=" + labels);

        for (String want : List.of(
                "Patient Name", "Date of Birth", "Address", "Apartment/Unit", "City", "State", "Zip",
                "Contact Phone", "Parent/Guardian", "Email Address", "Print Name", "Relationship to Patient", "Date")) {
            assertTrue(labels.stream().anyMatch(got -> got != null && got.toLowerCase().contains(want.toLowerCase())),
                    "no place labelled like \"" + want + "\"; places: " + labels);
        }

        // The one-cell "$" table is a place right after the sign.
        String dollarParagraph = read.outline().paragraphs().stream()
                .filter(p -> p.cell() != null && p.cell().table() == 3 && "$".equals(p.anchorText().trim()))
                .map(FormOutline.Paragraph::key).findFirst().orElse(null);
        assertTrue(dollarParagraph != null && spots.candidates().stream()
                        .anyMatch(c -> c.paragraphKey().equals(dollarParagraph)),
                "no place in the '$' cell; places: " + labels);
    }
}
