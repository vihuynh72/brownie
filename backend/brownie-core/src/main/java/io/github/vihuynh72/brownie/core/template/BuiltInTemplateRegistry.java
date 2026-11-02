package io.github.vihuynh72.brownie.core.template;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The fixed catalog of real forms a brand-new workspace starts with,
 * packaged under {@code builtin-templates/} in the API module. New entries
 * land in front of new workspaces only: a workspace that already has at
 * least one template is never given the missing ones, so changing this
 * catalog does not touch anyone's existing templates (see
 * {@code BuiltInTemplateProvisioningService}).
 */
public final class BuiltInTemplateRegistry {

    private static final String W9 = "topmostSubform[0].Page1[0].";
    private static final String W9_ADDRESS = W9 + "Address_ReadOrder[0].";

    private static final List<BuiltInTemplate> TEMPLATES = List.of(
            new BuiltInTemplate(
                    "irs-w9",
                    "IRS Form W-9",
                    "pdf",
                    List.of(
                            acro("person.name", "Name", W9 + "f1_01[0]"),
                            acro("person.business.name", "Business name", W9 + "f1_02[0]"),
                            acro("person.exemptions.payeeCode", "Exempt payee code", W9 + "f1_05[0]"),
                            acro("person.exemptions.fatcaCode", "Exemption from FATCA reporting code", W9 + "f1_06[0]"),
                            acro("person.address.street", "Address (number, street, and apt. or suite no.)", W9_ADDRESS + "f1_07[0]"),
                            acro("person.address.cityStateZip", "City, state, and ZIP code", W9_ADDRESS + "f1_08[0]"),
                            acro("requester.nameAddress", "Requester's name and address", W9 + "f1_09[0]"),
                            acro("person.accountNumbers", "Account number(s)", W9 + "f1_10[0]"),
                            acro("person.ssn.1", "Social security number (part 1)", W9 + "f1_11[0]"),
                            acro("person.ssn.2", "Social security number (part 2)", W9 + "f1_12[0]"),
                            acro("person.ssn.3", "Social security number (part 3)", W9 + "f1_13[0]"),
                            acro("person.ein.1", "Employer identification number (part 1)", W9 + "f1_14[0]"),
                            acro("person.ein.2", "Employer identification number (part 2)", W9 + "f1_15[0]"))),
            new BuiltInTemplate(
                    "job-application",
                    "Job application",
                    "docx",
                    List.of(
                            tag("applicant.name", "Full name", FieldRequiredness.REQUIRED),
                            tag("applicant.email", "Email", FieldRequiredness.OPTIONAL),
                            tag("applicant.phone", "Phone", FieldRequiredness.OPTIONAL),
                            tag("applicant.address", "Address", FieldRequiredness.OPTIONAL),
                            tag("applicant.position", "Position applied for", FieldRequiredness.REQUIRED),
                            tag("applicant.startDate", "Available start date", FieldType.DATE, FieldRequiredness.OPTIONAL),
                            tag("applicant.employer", "Most recent employer", FieldRequiredness.OPTIONAL),
                            tag("applicant.jobTitle", "Job title", FieldRequiredness.OPTIONAL),
                            tag("applicant.datesEmployed", "Dates employed", FieldRequiredness.OPTIONAL),
                            tag("applicant.education", "Highest education", FieldRequiredness.OPTIONAL))));

    private static final Map<String, BuiltInTemplate> BY_ID =
            TEMPLATES.stream().collect(Collectors.toUnmodifiableMap(BuiltInTemplate::id, Function.identity()));

    private BuiltInTemplateRegistry() {
    }

    /** The complete catalog in display order. */
    public static List<BuiltInTemplate> all() {
        return TEMPLATES;
    }

    /** Finds one built-in template by its stable identifier. */
    public static Optional<BuiltInTemplate> find(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    private static FieldDefinition acro(String fieldId, String label, String acroName) {
        return new FieldDefinition(
                fieldId,
                FieldType.TEXT,
                FieldCardinality.SCALAR,
                FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.AcroFormField(acroName),
                label,
                null,
                null,
                null);
    }

    private static FieldDefinition tag(String fieldId, String label, FieldRequiredness requiredness) {
        return tag(fieldId, label, FieldType.TEXT, requiredness);
    }

    private static FieldDefinition tag(String fieldId, String label, FieldType type, FieldRequiredness requiredness) {
        return new FieldDefinition(
                fieldId,
                type,
                FieldCardinality.SCALAR,
                requiredness,
                new FieldBindingTarget.ContentControlTag(fieldId),
                label,
                null,
                null,
                null);
    }
}
