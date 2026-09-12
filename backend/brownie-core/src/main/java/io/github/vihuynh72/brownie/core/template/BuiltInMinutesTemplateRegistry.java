package io.github.vihuynh72.brownie.core.template;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** A fixed catalog of the meeting-minutes fixtures shipped with Brownie. */
public final class BuiltInMinutesTemplateRegistry {

    private static final List<BuiltInMinutesTemplate> TEMPLATES = List.of(
            template(
                    "flowing-meeting-minutes",
                    "Flowing meeting minutes",
                    "fixtures/public/templates/flowing-meeting-minutes.docx",
                    "fixtures/public/expected/flowing-meeting-minutes-sample-filled.docx",
                    "fixtures/public/expected/flowing-meeting-minutes-empty-actions.docx"),
            template(
                    "table-led-meeting-minutes",
                    "Table-led meeting minutes",
                    "fixtures/public/templates/table-led-meeting-minutes.docx",
                    "fixtures/public/expected/table-led-meeting-minutes-sample-filled.docx",
                    "fixtures/public/expected/table-led-meeting-minutes-empty-actions.docx"));

    private static final Map<String, BuiltInMinutesTemplate> BY_ID = TEMPLATES.stream()
            .collect(Collectors.toUnmodifiableMap(BuiltInMinutesTemplate::id, Function.identity()));

    private BuiltInMinutesTemplateRegistry() {
    }

    /** The complete, stable catalog in display order. */
    public static List<BuiltInMinutesTemplate> all() {
        return TEMPLATES;
    }

    /** Finds one built-in template by its stable identifier. */
    public static Optional<BuiltInMinutesTemplate> find(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    private static BuiltInMinutesTemplate template(
            String id,
            String displayName,
            String templateFixturePath,
            String sampleFilledFixturePath,
            String emptyActionsFixturePath) {
        return new BuiltInMinutesTemplate(
                id,
                displayName,
                templateFixturePath,
                meetingMinutesFields(),
                List.of(
                        new BuiltInMinutesTemplate.QualificationArtifact(
                                "sample-filled",
                                sampleFilledFixturePath,
                                List.of(
                                        "Spring Budget Planning",
                                        "Riverside Robotics Club",
                                        "Alex Chen, Priya Rao, José Núñez",
                                        "Reserve the van for the regional competition",
                                        "Confirm sponsor logo placement on the robot"),
                                List.of("[meeting title]", "[task]", "[owner]", "[due date]")),
                        new BuiltInMinutesTemplate.QualificationArtifact(
                                "empty-actions",
                                emptyActionsFixturePath,
                                List.of("Officer Check-in", "March 5, 2026"),
                                List.of("[meeting title]", "[task]", "[owner]", "[due date]"))));
    }

    private static List<FieldDefinition> meetingMinutesFields() {
        return List.of(
                scalar("meeting.title", FieldType.TEXT, FieldRequiredness.REQUIRED),
                scalar("meeting.organization", FieldType.TEXT, FieldRequiredness.OPTIONAL),
                scalar("meeting.date", FieldType.DATE, FieldRequiredness.REQUIRED),
                scalar("meeting.location", FieldType.TEXT, FieldRequiredness.OPTIONAL),
                scalar("meeting.attendees", FieldType.TEXT, FieldRequiredness.OPTIONAL),
                scalar("meeting.decisions", FieldType.TEXT, FieldRequiredness.OPTIONAL),
                repeated("action.item.task", FieldType.TEXT),
                repeated("action.item.owner", FieldType.TEXT),
                repeated("action.item.due", FieldType.DATE));
    }

    private static FieldDefinition scalar(String fieldId, FieldType type, FieldRequiredness requiredness) {
        return new FieldDefinition(
                fieldId,
                type,
                FieldCardinality.SCALAR,
                requiredness,
                new FieldBindingTarget.ContentControlTag(fieldId));
    }

    private static FieldDefinition repeated(String fieldId, FieldType type) {
        return new FieldDefinition(
                fieldId,
                type,
                FieldCardinality.REPEATED,
                FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.ContentControlTag(fieldId));
    }
}
