package io.github.vihuynh72.brownie.api.template;

import io.github.vihuynh72.brownie.core.document.PdfFontFamily;
import io.github.vihuynh72.brownie.core.document.PdfOverflowPolicy;
import io.github.vihuynh72.brownie.core.document.PdfTextStyle;
import io.github.vihuynh72.brownie.core.rule.RulePayload;
import io.github.vihuynh72.brownie.core.template.CandidateBindingReport;
import io.github.vihuynh72.brownie.core.template.CandidateFieldBinding;
import io.github.vihuynh72.brownie.core.template.FieldBindingTarget;
import io.github.vihuynh72.brownie.core.template.FieldCardinality;
import io.github.vihuynh72.brownie.core.template.FieldDefinition;
import io.github.vihuynh72.brownie.core.template.FieldRequiredness;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.MalformedTemplateRequestException;
import io.github.vihuynh72.brownie.core.template.SpotOrigin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The template API's binding shapes for a PDF's places, without a server:
 * a request becomes the binding it names, with a box's style and overflow
 * defaulted, a malformed one is refused as malformed, and a stored binding
 * answers in the same shape a request uses.
 */
class PdfBindingBodiesTest {

    @Test
    void aFormFieldBindingNamesTheFieldByItsFullName() {
        TemplateController.BindingRequest request =
                new TemplateController.BindingRequest(TemplateController.BindingKind.ACROFORM_FIELD, null, null, null, "applicant.phone", null);

        assertThat(request.toDomain()).isEqualTo(new FieldBindingTarget.AcroFormField("applicant.phone"));
        assertThatThrownBy(() -> new TemplateController.BindingRequest(
                TemplateController.BindingKind.ACROFORM_FIELD, "tag", null, null, "", null).toDomain())
                .isInstanceOf(MalformedTemplateRequestException.class);
    }

    @Test
    void aBoxWithoutAStyleOrOverflowGetsAnOrdinaryStyleAndShrinksToFit() {
        TemplateController.PageBoxBody body = new TemplateController.PageBoxBody(1, 72.0, 90.0, 200.0, 14.0, null, null, null);

        FieldBindingTarget target = new TemplateController.BindingRequest(
                TemplateController.BindingKind.PAGE_BOX, null, null, null, null, body).toDomain();

        assertThat(target).isEqualTo(new FieldBindingTarget.PageBox(1, 72, 90, 200, 14, PdfTextStyle.DEFAULT, false, PdfOverflowPolicy.SHRINK_TO_FIT));
    }

    @Test
    void aBoxKeepsTheStyleAndOverflowAskedFor() {
        TemplateController.PageBoxBody body = new TemplateController.PageBoxBody(2, 72.0, 90.0, 200.0, 30.0,
                new TemplateController.TextStyleBody(PdfFontFamily.MONO, true, 9.5), true, PdfOverflowPolicy.BLOCK);

        assertThat(body.toDomain()).isEqualTo(new FieldBindingTarget.PageBox(
                2, 72, 90, 200, 30, new PdfTextStyle(PdfFontFamily.MONO, true, 9.5), true, PdfOverflowPolicy.BLOCK));
    }

    @Test
    void aBoxMissingPartsOrWithAnImpossibleSizeIsMalformed() {
        List<TemplateController.PageBoxBody> malformed = List.of(
                new TemplateController.PageBoxBody(null, 72.0, 90.0, 200.0, 14.0, null, null, null),
                new TemplateController.PageBoxBody(1, 72.0, null, 200.0, 14.0, null, null, null),
                new TemplateController.PageBoxBody(0, 72.0, 90.0, 200.0, 14.0, null, null, null),
                new TemplateController.PageBoxBody(1, Double.NaN, 90.0, 200.0, 14.0, null, null, null),
                new TemplateController.PageBoxBody(1, 72.0, 90.0, 200.0, 14.0, new TemplateController.TextStyleBody(null, null, 3.0), null, null),
                new TemplateController.PageBoxBody(1, 72.0, 90.0, 200.0, 14.0, new TemplateController.TextStyleBody(null, null, 100.0), null, null));
        for (TemplateController.PageBoxBody body : malformed) {
            assertThatThrownBy(body::toDomain).as(body.toString()).isInstanceOf(MalformedTemplateRequestException.class);
        }
        assertThatThrownBy(() -> new TemplateController.BindingRequest(
                TemplateController.BindingKind.PAGE_BOX, null, null, null, null, null).toDomain())
                .isInstanceOf(MalformedTemplateRequestException.class);
    }

    @Test
    void aStoredPdfBindingAnswersInTheShapeARequestUses() {
        FieldBindingTarget.PageBox box = new FieldBindingTarget.PageBox(
                1, 130, 88, 300, 14, new PdfTextStyle(PdfFontFamily.SERIF, false, 12), false, PdfOverflowPolicy.SHRINK_TO_FIT);
        TemplateController.FieldDefinitionResponse boxed = TemplateController.FieldDefinitionResponse.from(new FieldDefinition(
                "full.name", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL, box, "Full name",
                SpotOrigin.FOUND_BY_BROWNIE, null, null));
        TemplateController.FieldDefinitionResponse field = TemplateController.FieldDefinitionResponse.from(new FieldDefinition(
                "phone", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.OPTIONAL,
                new FieldBindingTarget.AcroFormField("applicant.phone")));

        assertThat(boxed.bindingKind()).isEqualTo("PAGE_BOX");
        assertThat(boxed.pageBox().toDomain()).isEqualTo(box);
        assertThat(boxed.acroFormField()).isNull();
        assertThat(boxed.tag()).isNull();
        assertThat(field.bindingKind()).isEqualTo("ACROFORM_FIELD");
        assertThat(field.acroFormField()).isEqualTo("applicant.phone");
        assertThat(field.pageBox()).isNull();
        assertThat(TemplateController.BindingRequest.from(box).toDomain()).isEqualTo(box);
    }

    @Test
    void aWordFieldStillAnswersWithoutAnyPdfParts() {
        TemplateController.FieldDefinitionResponse response = TemplateController.FieldDefinitionResponse.from(new FieldDefinition(
                "meeting.title", FieldType.TEXT, FieldCardinality.SCALAR, FieldRequiredness.REQUIRED,
                new FieldBindingTarget.ContentControlTag("meeting.title")));

        assertThat(response.bindingKind()).isEqualTo("CONTENT_CONTROL_TAG");
        assertThat(response.tag()).isEqualTo("meeting.title");
        assertThat(response.acroFormField()).isNull();
        assertThat(response.pageBox()).isNull();
    }

    @Test
    void aCandidateNotBoundByATagIsLeftOutOfTheCandidateReportRatherThanFailingIt() {
        CandidateBindingReport report = new CandidateBindingReport(List.of(
                new CandidateFieldBinding("meeting.title", FieldType.TEXT, FieldCardinality.SCALAR,
                        new FieldBindingTarget.ContentControlTag("meeting.title")),
                new CandidateFieldBinding("phone", FieldType.TEXT, FieldCardinality.SCALAR, new FieldBindingTarget.AcroFormField("phone"))),
                List.of(), 0);

        TemplateController.CandidateBindingReportResponse response = TemplateController.CandidateBindingReportResponse.from(report);

        assertThat(response.candidates()).extracting(TemplateController.CandidateFieldBindingResponse::fieldId).containsExactly("meeting.title");
    }

    @Test
    void aProtectedRegionOnAPdfPlaceIsShownInTheRuleShapeItWasAskedIn() {
        RuleController.RulePayloadRequest request =
                RuleController.RulePayloadRequest.from(new RulePayload.ProtectedRegion(new FieldBindingTarget.AcroFormField("fullName")));

        assertThat(request.protectedRegionTarget().kind()).isEqualTo(TemplateController.BindingKind.ACROFORM_FIELD);
        assertThat(request.protectedRegionTarget().toDomain()).isEqualTo(new FieldBindingTarget.AcroFormField("fullName"));
    }
}
