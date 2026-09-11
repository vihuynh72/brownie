package io.github.vihuynh72.brownie.api.document.docx;

import io.github.vihuynh72.brownie.core.document.DocxExtractionOutcome;
import io.github.vihuynh72.brownie.core.document.DocxStructuralGraph;
import io.github.vihuynh72.brownie.core.document.StructuralNode;
import io.github.vihuynh72.brownie.core.document.StructuralNodeKind;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplate;
import io.github.vihuynh72.brownie.core.template.BuiltInMinutesTemplateRegistry;
import io.github.vihuynh72.brownie.core.template.TemplateBindingValidator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BuiltInMinutesTemplateQualificationTest {

    private final PoiDocxStructuralExtractor extractor = new PoiDocxStructuralExtractor();

    @Test
    void everyBuiltInBlankFixtureHasSupportedDeclaredBindings() throws IOException {
        for (BuiltInMinutesTemplate template : BuiltInMinutesTemplateRegistry.all()) {
            DocxStructuralGraph graph = extract(fixturePath(template.templateFixturePath()));
            assertTrue(
                    TemplateBindingValidator.validate(graph, template.fields()).isEmpty(),
                    () -> template.id() + " has an unsupported declared binding");
        }
    }

    @Test
    void theTwoLayoutsKeepTheirDistinctActionItemStructures() throws IOException {
        BuiltInMinutesTemplate flowing = BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow();
        BuiltInMinutesTemplate tableLed = BuiltInMinutesTemplateRegistry.find("table-led-meeting-minutes").orElseThrow();

        assertFalse(hasNodeOfKind(extract(fixturePath(flowing.templateFixturePath())), StructuralNodeKind.TABLE));
        assertTrue(hasNodeOfKind(extract(fixturePath(tableLed.templateFixturePath())), StructuralNodeKind.TABLE));
    }

    @Test
    void everyQualificationArtifactContainsItsSyntheticCaseAndNoBindingPlaceholders() throws IOException {
        for (BuiltInMinutesTemplate template : BuiltInMinutesTemplateRegistry.all()) {
            for (BuiltInMinutesTemplate.QualificationArtifact artifact : template.qualificationArtifacts()) {
                String text = textOf(extract(fixturePath(artifact.fixturePath())));
                for (String expected : artifact.expectedText()) {
                    assertTrue(
                            text.contains(expected),
                            () -> template.id() + "/" + artifact.id() + " is missing expected text: " + expected);
                }
                for (String forbidden : artifact.forbiddenText()) {
                    assertTrue(
                            !text.contains(forbidden),
                            () -> template.id() + "/" + artifact.id() + " retains placeholder text: " + forbidden);
                }
            }
        }
    }

    private DocxStructuralGraph extract(Path path) throws IOException {
        DocxExtractionOutcome outcome;
        try (InputStream content = Files.newInputStream(path)) {
            outcome = extractor.extract(content);
        }
        if (outcome instanceof DocxExtractionOutcome.Supported supported) {
            return supported.graph();
        }
        throw new AssertionError(path + " is outside the supported DOCX subset: " + outcome);
    }

    private static String textOf(DocxStructuralGraph graph) {
        StringBuilder text = new StringBuilder();
        for (var part : graph.parts()) {
            appendText(part.root(), text);
        }
        return text.toString();
    }

    private static void appendText(StructuralNode node, StringBuilder text) {
        if (node.text() != null) {
            text.append(node.text());
        }
        for (StructuralNode child : node.children()) {
            appendText(child, text);
        }
    }

    private static boolean hasNodeOfKind(DocxStructuralGraph graph, StructuralNodeKind kind) {
        return graph.parts().stream().anyMatch(part -> hasNodeOfKind(part.root(), kind));
    }

    private static boolean hasNodeOfKind(StructuralNode node, StructuralNodeKind kind) {
        return node.kind() == kind || node.children().stream().anyMatch(child -> hasNodeOfKind(child, kind));
    }

    private static Path fixturePath(String repositoryRelativePath) {
        Path path = repositoryRoot().resolve(repositoryRelativePath);
        assertTrue(Files.isRegularFile(path), () -> "missing fixture: " + path);
        return path;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("fixtures/public/templates"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("could not locate the repository root from the test working directory");
    }
}
