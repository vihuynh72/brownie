package io.github.vihuynh72.brownie.core.compile;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a PDF template's sample fill writes into each place, which decides what a place must have room for. */
class TemplateQualificationSampleTest {

    @Test
    void aPdfDateIsSampledAtTheLengthTheLongestRealDateIsWrittenIn() {
        FieldDefinition date = new FieldDefinition("meeting.date", FieldType.DATE, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.PageBox(1, 100, 100, 120, 16, PdfTextStyle.DEFAULT, false, PdfOverflowPolicy.BLOCK));
        PdfFormGraph graph = new PdfFormGraph("test", List.of(), PdfFormGraph.AcroForm.absent(), new PdfFormGraph.Risks(false, false, false));

        DocumentContent sample = TemplateQualificationService.pdfSampleContent(List.of(date), graph);

        LocalDate sampled = ((FieldValue.DateValue) sample.fields().get("meeting.date")).value();
        DateTimeFormatter written = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);
        int longest = 0;
        for (LocalDate day = LocalDate.of(2020, 1, 1); day.getYear() == 2020; day = day.plusDays(1)) {
            longest = Math.max(longest, written.format(day).length());
        }
        assertEquals(longest, written.format(sampled).length(), written.format(sampled));
        assertTrue(sampled.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH).length() >= 9);
    }
}
