package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.api.document.pdf.PdfBoxFormReader;
import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfFormReading;
import io.github.vihuynh72.brownie.core.template.FieldType;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class W9FieldNamingTest {
    @Test
    void fieldCaptionsUseTheCorrectColumnAndCheckboxOptionsStopAtTheNextBox() throws Exception {
        PdfFormGraph graph = ((PdfFormReading.Supported) new PdfBoxFormReader()
                .read(Files.readAllBytes(Path.of("src/test/resources/w9.pdf")))).graph();
        var found = PdfFormPreparationService.candidates(graph);
        var requester = found.candidates().stream().filter(c -> c.formFieldName() != null && c.formFieldName().endsWith("f1_09[0]"))
                .findFirst().orElseThrow();
        var city = found.candidates().stream().filter(c -> c.formFieldName() != null && c.formFieldName().endsWith("f1_08[0]"))
                .findFirst().orElseThrow();
        assertEquals("Requester’s name and address (optional)", requester.naming().rulesLabel());
        assertEquals("6 City, state, and ZIP code", city.naming().rulesLabel());
        assertEquals("Individual/sole proprietor", label(found, "c1_1[0]"));
        assertEquals("C corporation", label(found, "c1_1[1]"));
        assertEquals("S corporation", label(found, "c1_1[2]"));
        assertEquals("Partnership", label(found, "c1_1[3]"));
        assertEquals("Trust/estate", label(found, "c1_1[4]"));
        assertEquals("Social security number", label(found, "f1_11[0]"));
        assertEquals("Social security number", label(found, "f1_12[0]"));
        assertEquals("Social security number", label(found, "f1_13[0]"));
        assertEquals("Employer identification number", label(found, "f1_14[0]"));
        assertEquals("Employer identification number", label(found, "f1_15[0]"));

        var outline = PdfFormPreparationService.outline(graph, found.places());
        var cityLine = outline.stream().filter(line -> line.text().contains("City, state, and ZIP code")).findFirst().orElseThrow();
        assertFalse(cityLine.text().contains("[[" + requester.naming().id() + "]]"));
        var wrongRequester = new NamedSpot(requester.naming().id(), true, "City, state, and ZIP code", FieldType.TEXT, "TEXT", false, true);
        var namedCity = new NamedSpot(city.naming().id(), true, "City, state, and ZIP code", FieldType.TEXT, "TEXT", false, true);
        var names = Map.of(requester.naming().id(), wrongRequester, city.naming().id(), namedCity);
        assertEquals(requester.naming().rulesLabel(), PdfFormPreparationService.disambiguateLabel(graph, found, names, requester, wrongRequester).label());
        assertEquals(namedCity, PdfFormPreparationService.disambiguateLabel(graph, found, names, city, namedCity));

        var checkbox = found.candidates().stream().filter(c -> c.formFieldName() != null && c.formFieldName().endsWith("c1_1[1]"))
                .findFirst().orElseThrow();
        var otherCheckbox = found.candidates().stream().filter(c -> c.formFieldName() != null && c.formFieldName().endsWith("c1_1[2]"))
                .findFirst().orElseThrow();
        var wrongOption = new NamedSpot(checkbox.naming().id(), true, "Tax classification", FieldType.CHECKBOX, "CHECKBOX", false, true);
        var wrongOther = new NamedSpot(otherCheckbox.naming().id(), true, "Tax classification", FieldType.CHECKBOX, "CHECKBOX", false, true);
        assertEquals("C corporation", PdfFormPreparationService.disambiguateLabel(graph, found,
                Map.of(checkbox.naming().id(), wrongOption, otherCheckbox.naming().id(), wrongOther), checkbox, wrongOption).label());
    }

    private static String label(PdfFormPreparationService.Found found, String name) {
        return found.candidates().stream().filter(c -> c.formFieldName() != null && c.formFieldName().endsWith(name))
                .findFirst().orElseThrow().naming().rulesLabel();
    }
}
