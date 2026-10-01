package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.assist.AssistCommand;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.template.FieldType;
import io.github.vihuynh72.brownie.core.template.PdfTemplateLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where quoted words and the model's choice put a new box on a PDF form,
 * against hand-built pages: each place is a line by page and index and how
 * far into its text the box goes after, and the model's answer is used
 * only when it holds together.
 */
class PdfSpotPlacesTest {

    private static final PdfTemplateLayout PAGES = new PdfTemplateLayout(1L, 2L, "pdf-form-test", 3L, List.of(
            new PdfTemplateLayout.Page(1, 612, 792, 0, true, List.of(
                    line(0, "Membership application"),
                    line(1, "Company: ________ Phone: ________"),
                    line(2, "  "))),
            new PdfTemplateLayout.Page(2, 612, 792, 0, true, List.of(
                    line(0, "Emergency contact:"),
                    line(1, "Company: (if different)"))),
            new PdfTemplateLayout.Page(3, 612, 792, 0, false, List.of())),
            List.of());

    @Test
    void theLinesAreEveryLineWithTextByPageWithIdsTheModelIsShown() {
        List<PdfSpotPlaces.Line> lines = PdfSpotPlaces.linesOf(PAGES);

        assertThat(lines).extracting(PdfSpotPlaces.Line::id).containsExactly("P1L0", "P1L1", "P2L0", "P2L1");
        assertThat(PdfSpotPlaces.forModel(lines).get(2))
                .isEqualTo(new SpotPlaces.Line("P2L0", null, "Emergency contact:", null, "page 2"));
        assertThat(PdfSpotPlaces.lineAt(lines, 2, 1)).map(PdfSpotPlaces.Line::text).contains("Company: (if different)");
        assertThat(PdfSpotPlaces.lineAt(lines, 1, 2)).isEmpty();
    }

    @Test
    void quotedWordsArePlacesAfterThemOnEachLineTheyAreOnAndABlankIsReplacedFromBeforeIt() {
        List<PdfSpotPlaces.Line> lines = PdfSpotPlaces.linesOf(PAGES);

        List<PdfSpotPlaces.Target> company = PdfSpotPlaces.targetsOfQuoted(lines, "company:", AssistCommand.SpotPlacement.AFTER);
        assertThat(company).extracting(target -> target.line().id()).containsExactly("P1L1", "P2L1");
        assertThat(company.getFirst().after()).isEqualTo(8);
        assertThat(company.getFirst().description()).isEqualTo("after \"Company:\"");

        List<PdfSpotPlaces.Target> phone = PdfSpotPlaces.targetsOfQuoted(lines, "PHONE:  ", AssistCommand.SpotPlacement.UNSPECIFIED);
        assertThat(phone).singleElement().satisfies(target -> assertThat(target.after()).isEqualTo(24));

        PdfSpotPlaces.Target blank = PdfSpotPlaces.targetsOfQuoted(lines, "Phone: ________", AssistCommand.SpotPlacement.REPLACE).getFirst();
        assertThat(blank.after()).isEqualTo(18);
        assertThat(blank.description()).startsWith("in place of");
        assertThat(PdfSpotPlaces.targetsOfQuoted(lines, "Emergency", AssistCommand.SpotPlacement.WHOLE_LINE).getFirst().after()).isNull();
        assertThat(PdfSpotPlaces.targetsOfQuoted(lines, "Fax", AssistCommand.SpotPlacement.AFTER)).isEmpty();
    }

    @Test
    void theModelsChoiceIsUsedOnlyForAnOfferedLineAndWordsReallyOnIt() {
        List<PdfSpotPlaces.Line> lines = PdfSpotPlaces.linesOf(PAGES);

        assertThat(PdfSpotPlaces.fromModel(lines, reply("P2L0", "AFTER_TEXT", "contact:")))
                .hasValueSatisfying(target -> {
                    assertThat(target.line().pageNumber()).isEqualTo(2);
                    assertThat(target.line().lineIndex()).isZero();
                    assertThat(target.after()).isEqualTo(18);
                });
        assertThat(PdfSpotPlaces.fromModel(lines, reply("P1L1", "END_OF_LINE", null)))
                .hasValueSatisfying(target -> assertThat(target.after()).isNull());
        assertThat(PdfSpotPlaces.fromModel(lines, reply("P1L1", "AFTER_TEXT", "Email:"))).isEmpty();
        assertThat(PdfSpotPlaces.fromModel(lines, reply("P9L1", "END_OF_LINE", null))).isEmpty();
        assertThat(PdfSpotPlaces.fromModel(lines, reply("L1", "END_OF_LINE", null))).isEmpty();
        assertThat(PdfSpotPlaces.fromModel(lines, reply("P1L1", "SOMEWHERE", null))).isEmpty();
    }

    private static SpotPlacementPrompt.Reply reply(String lineId, String placement, String text) {
        return new SpotPlacementPrompt.Reply(lineId, placement, text, "Company", FieldType.TEXT);
    }

    private static PdfTemplateLayout.Line line(int index, String text) {
        return new PdfTemplateLayout.Line(index, text, new PdfRect(72, 60 + 20 * index, 300, 10));
    }
}
