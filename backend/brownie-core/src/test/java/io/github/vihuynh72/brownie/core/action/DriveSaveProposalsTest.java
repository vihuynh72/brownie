package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.export.ExportFormat;
import io.github.vihuynh72.brownie.core.export.ExportReceipt;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.IntStream;

import static io.github.vihuynh72.brownie.core.action.DriveSaveProposals.Kind.GOOGLE_DOC;
import static io.github.vihuynh72.brownie.core.action.DriveSaveProposals.Kind.PDF_FILE;
import static io.github.vihuynh72.brownie.core.action.DriveSaveProposals.Kind.WORD_FILE;
import static io.github.vihuynh72.brownie.core.action.DriveSaveProposals.exportOffers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a save may send is what the export offers to download, under a file
 * name every page shows whole.
 */
class DriveSaveProposalsTest {

    @Test
    void aSaveOffersExactlyTheFilesTheExportOffersToDownload() {
        ExportReceipt both = receipt(ExportFormat.BOTH, true);
        assertTrue(exportOffers(both, WORD_FILE));
        assertTrue(exportOffers(both, PDF_FILE));
        assertTrue(exportOffers(both, GOOGLE_DOC));

        ExportReceipt wordOnly = receipt(ExportFormat.DOCX, false);
        assertTrue(exportOffers(wordOnly, WORD_FILE));
        assertFalse(exportOffers(wordOnly, PDF_FILE));
        assertTrue(exportOffers(wordOnly, GOOGLE_DOC));

        // A PDF export offers the PDF, and not the Word file it was made from.
        ExportReceipt pdfOnly = receipt(ExportFormat.PDF, true);
        assertFalse(exportOffers(pdfOnly, WORD_FILE));
        assertTrue(exportOffers(pdfOnly, PDF_FILE));
        assertFalse(exportOffers(pdfOnly, GOOGLE_DOC));

        // Its PDF could not be made, so the export offers the Word file instead, and so may a save.
        ExportReceipt pdfNotMade = receipt(ExportFormat.PDF, false);
        assertTrue(exportOffers(pdfNotMade, WORD_FILE));
        assertFalse(exportOffers(pdfNotMade, PDF_FILE));
        assertTrue(exportOffers(pdfNotMade, GOOGLE_DOC));

        ExportReceipt bothWithoutPdf = receipt(ExportFormat.BOTH, false);
        assertTrue(exportOffers(bothWithoutPdf, WORD_FILE));
        assertFalse(exportOffers(bothWithoutPdf, PDF_FILE));

        // A PDF form's export is its PDF alone: there is no Word file to save or convert.
        ExportReceipt pdfForm = new ExportReceipt(12, 7, 42, 9, 2, 4, 3, null, null, 14L, "b".repeat(64), ExportFormat.PDF, 3,
                OffsetDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZoneOffset.UTC));
        assertFalse(exportOffers(pdfForm, WORD_FILE));
        assertTrue(exportOffers(pdfForm, PDF_FILE));
        assertFalse(exportOffers(pdfForm, GOOGLE_DOC));
        assertFalse(pdfForm.isCompletePair());
    }

    @Test
    void theValuesLookedForAreCutAtWholeCharactersAndAlwaysFitInAPayload() {
        String longWithEmoji = "a".repeat(1_999) + "\uD83D\uDCB0" + "b".repeat(500);
        String cut = DriveSaveProposals.bounded(List.of(longWithEmoji)).getFirst();
        assertEquals(DriveSaveProposals.MAX_CHECKED_VALUE_LENGTH, cut.codePointCount(0, cut.length()));
        assertTrue(cut.endsWith("\uD83D\uDCB0"), "never half of a character");

        List<String> many = IntStream.range(0, 300).mapToObj(i -> i + "\"" + "x".repeat(2_100)).toList();
        List<String> bounded = DriveSaveProposals.bounded(many);
        assertTrue(bounded.size() < DriveSaveProposals.MAX_CHECKED_VALUES, "the total bounds the list before the count does");
        DriveSavePayload payload = new DriveSavePayload(ActionType.DRIVE_SAVE_AS_GOOGLE_DOC, "n", 3, 7, 40, 11, "t".repeat(300), 5,
                "someone@example.org", 12, 13, "DOCX", "Minutes", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                2_000_000, "a".repeat(64), "b".repeat(32), bounded);
        assertTrue(payload.canonical().codePointCount(0, payload.canonical().length()) < 262_144,
                "the database keeps payloads of up to 262,144 characters");
        assertEquals(List.of("short"), DriveSaveProposals.bounded(List.of("short")));
    }

    @Test
    void aValueIsComposedAndNeverCutBetweenALetterAndItsMarkSoTheConvertedTextStillHoldsIt() {
        String decomposed = "a".repeat(1_999) + "e\u0323\u0302" + " and the rest";
        List<String> bounded = DriveSaveProposals.bounded(List.of(decomposed));
        assertEquals(1, ConversionCheck.count(bounded, "Before. " + decomposed + " After.").found(),
                "a letter and its marks are kept together, so the value is found where Google keeps the text as it was");
        String unmatched = "x".repeat(1_999) + "q\u0301\u0301 more";
        assertEquals(1, ConversionCheck.count(DriveSaveProposals.bounded(List.of(unmatched)), unmatched).found());
    }

    @Test
    void aTitleIsCarriedInPartWhenItIsLongerThanAnyPageShows() {
        String title = "t".repeat(300_000);
        assertEquals(Payloads.MAX_SHOWN_TITLE_LENGTH, Payloads.shownTitle(title).length());
        assertEquals("Minutes", Payloads.shownTitle("Minutes"));
        List<String> many = IntStream.range(0, 300).mapToObj(i -> i + "x".repeat(2_100)).toList();
        DriveSavePayload payload = new DriveSavePayload(ActionType.DRIVE_SAVE_AS_GOOGLE_DOC, "n", 3, 7, 40, 11, Payloads.shownTitle(title), 5,
                "someone@example.org", 12, 13, "DOCX", DriveSaveProposals.fileName(title, null),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 2_000_000, "a".repeat(64), "b".repeat(32),
                DriveSaveProposals.bounded(many));
        assertTrue(payload.canonical().length() < 262_144, "the longest title and the most values still fit");
    }

    @Test
    void aFileNameIsTheTitleOnOneLineBoundedAndNeverEmpty() {
        assertEquals("Minutes of March.docx", DriveSaveProposals.fileName("  Minutes\nof March  ", "docx"));
        assertEquals("Document.pdf", DriveSaveProposals.fileName("   ", "pdf"));
        assertEquals("Minutes", DriveSaveProposals.fileName("Minutes", null));
        // A character outside the basic plane is two chars; a name is cut between characters, never inside one.
        String named = DriveSaveProposals.fileName("\uD83D\uDCC4".repeat(300), "docx");
        assertEquals(DriveSaveProposals.MAX_FILE_NAME_LENGTH, named.codePointCount(0, named.length()));
        assertTrue(named.endsWith(".docx"));
        assertFalse(Character.isHighSurrogate(named.charAt(named.length() - 6)));
    }

    private static ExportReceipt receipt(ExportFormat format, boolean pdfMade) {
        return new ExportReceipt(12, 7, 42, 9, 2, 4, 3, 13L, "a".repeat(64),
                pdfMade ? 14L : null, pdfMade ? "b".repeat(64) : null, format, 3,
                OffsetDateTime.of(2026, 9, 28, 10, 0, 0, 0, ZoneOffset.UTC));
    }
}
