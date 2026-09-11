package io.github.vihuynh72.brownie.core.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInMinutesTemplateRegistryTest {

    @Test
    void exposesTheTwoBuiltInMinutesLayoutsWithStableIdentifiers() {
        List<BuiltInMinutesTemplate> templates = BuiltInMinutesTemplateRegistry.all();

        assertEquals(
                List.of("flowing-meeting-minutes", "table-led-meeting-minutes"),
                templates.stream().map(BuiltInMinutesTemplate::id).toList());
        assertEquals(
                "Flowing meeting minutes",
                BuiltInMinutesTemplateRegistry.find("flowing-meeting-minutes").orElseThrow().displayName());
        assertTrue(BuiltInMinutesTemplateRegistry.find("does-not-exist").isEmpty());
    }

    @Test
    void eachLayoutDeclaresTheCompleteMinutesSchemaAndQualificationCases() {
        for (BuiltInMinutesTemplate template : BuiltInMinutesTemplateRegistry.all()) {
            assertEquals(
                    List.of(
                            "meeting.title",
                            "meeting.organization",
                            "meeting.date",
                            "meeting.location",
                            "meeting.attendees",
                            "meeting.decisions",
                            "action.item.task",
                            "action.item.owner",
                            "action.item.due"),
                    template.fields().stream().map(FieldDefinition::fieldId).toList());
            assertEquals(FieldRequiredness.REQUIRED, template.fields().get(0).requiredness());
            assertEquals(FieldType.DATE, template.fields().get(2).type());
            assertEquals(
                    List.of(FieldCardinality.REPEATED, FieldCardinality.REPEATED, FieldCardinality.REPEATED),
                    template.fields().subList(6, 9).stream().map(FieldDefinition::cardinality).toList());
            assertEquals(
                    List.of("sample-filled", "empty-actions"),
                    template.qualificationArtifacts().stream()
                            .map(BuiltInMinutesTemplate.QualificationArtifact::id)
                            .toList());
        }
    }
}
