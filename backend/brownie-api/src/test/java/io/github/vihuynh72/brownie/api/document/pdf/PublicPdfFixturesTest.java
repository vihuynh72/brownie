package io.github.vihuynh72.brownie.api.document.pdf;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The PDFs committed under {@code fixtures/public/pdf/} for the web app's
 * browser tests, which upload them: a fillable application form, the same
 * kind of form with its blanks printed instead, and a scanned page. They
 * are made by the fixture builders here, so they can be made again:
 *
 * <pre>./mvnw -o test -pl brownie-api -Dtest=PublicPdfFixturesTest -Dbrownie.writePublicPdfFixtures=true</pre>
 *
 * <p>A saved PDF carries a fresh identifier each time, so the files are
 * compared by what they hold (pages, text, fields, pictures), not byte for
 * byte: a change to a builder that changes what a committed file holds
 * fails here until the file is made again.
 */
class PublicPdfFixturesTest {

    private interface Builder {
        byte[] build() throws IOException;
    }

    private static final Map<String, Builder> FIXTURES = new LinkedHashMap<>();

    static {
        FIXTURES.put("fillable-application.pdf", PdfFormFixtures::fillableForm);
        FIXTURES.put("flat-application.pdf", PdfFormFixtures::flatForm);
        FIXTURES.put("scanned-note.pdf", () -> PdfFormFixtures.scannedPage(false));
    }

    @Test
    @EnabledIfSystemProperty(named = "brownie.writePublicPdfFixtures", matches = "true")
    void write() throws IOException {
        Path directory = directory();
        Files.createDirectories(directory);
        for (Map.Entry<String, Builder> fixture : FIXTURES.entrySet()) {
            Files.write(directory.resolve(fixture.getKey()), fixture.getValue().build());
        }
    }

    @Test
    void theCommittedFilesHoldWhatTheBuildersMake() throws IOException {
        for (Map.Entry<String, Builder> fixture : FIXTURES.entrySet()) {
            Path committed = directory().resolve(fixture.getKey());
            assertTrue(Files.isRegularFile(committed), "missing " + committed + "; make it with the command in this class's javadoc");

            assertEquals(contentsOf(fixture.getValue().build()), contentsOf(Files.readAllBytes(committed)), fixture.getKey());
        }
    }

    /** What a person or a browser test would notice: pages, their text, the form's fields and where they are, the pictures. */
    private static List<String> contentsOf(byte[] pdf) {
        PdfFormGraph graph = PdfBoxFormFillerTest.graphOf(pdf);
        List<String> contents = new ArrayList<>();
        for (PdfFormGraph.Page page : graph.pages()) {
            contents.add("page " + page.pageNumber() + " " + rect(new PdfRect(page.cropBox().llx(), page.cropBox().lly(),
                    page.cropBox().width(), page.cropBox().height())) + " turned " + page.rotation());
            page.lines().forEach(line -> contents.add("  line " + line.text()));
            contents.add("  " + page.rules().size() + " lines drawn, " + page.rects().size() + " boxes drawn");
            page.images().forEach(image -> contents.add("  picture " + rect(image.box()) + " " + image.filters()));
        }
        for (PdfFormGraph.Field field : graph.acroForm().fields()) {
            StringBuilder line = new StringBuilder("field " + field.fullName() + " " + field.kind());
            if (field.readOnly()) {
                line.append(" read-only");
            }
            if (field.required()) {
                line.append(" required");
            }
            if (field.multiline()) {
                line.append(" multiline");
            }
            if (field.comb()) {
                line.append(" comb");
            }
            line.append(" maxLen=").append(field.maxLen()).append(" tooltip=").append(field.tooltip())
                    .append(" date=").append(field.dateFormat());
            field.widgets().forEach(widget -> line.append(" on ").append(widget.pageNumber()).append(' ').append(rect(widget.box())));
            contents.add(line.toString());
        }
        return contents;
    }

    private static String rect(PdfRect rect) {
        return String.format(Locale.ROOT, "(%.1f %.1f %.1f %.1f)", rect.x(), rect.y(), rect.width(), rect.height());
    }

    private static Path directory() {
        return PdfBoxGeometryVectorsTest.repositoryRoot().resolve("fixtures/public/pdf");
    }
}
