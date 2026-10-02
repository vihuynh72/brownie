package io.github.vihuynh72.brownie.worker.generation;

import io.github.vihuynh72.brownie.core.generation.ExtractionInputBundle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A bundle staged by an API newer than this worker still reads, whatever it adds. */
class GenerationBundleReadingTest {

    @Test
    void aBundleWithPropertiesThisWorkerDoesNotKnowIsStillRead() throws Exception {
        String json = """
                {"fields":[{"fieldId":"meeting.title","type":"TEXT","cardinality":"SCALAR","requiredness":"REQUIRED",\
                "existingValueText":"Old Title","label":"Meeting title","addedLater":{"any":"thing"}}],\
                "excerpts":[{"spanId":1,"text":"The meeting was called to order.","addedLater":1}],\
                "composableFieldIds":[],"alsoAddedLater":true}""";

        ExtractionInputBundle bundle = GenerationExtractionJobProcessor.bundleMapper().readValue(json, ExtractionInputBundle.class);

        assertThat(bundle.fields()).singleElement().satisfies(field -> {
            assertThat(field.fieldId()).isEqualTo("meeting.title");
            assertThat(field.existingValueText()).isEqualTo("Old Title");
            assertThat(field.label()).isEqualTo("Meeting title");
        });
        assertThat(bundle.excerpts()).singleElement().satisfies(excerpt -> assertThat(excerpt.spanId()).isEqualTo(1));
    }

    @Test
    void aBundleWrittenWithoutEmptyPropertiesReadsThemAsEmpty() throws Exception {
        String json = """
                {"fields":[{"fieldId":"meeting.title","type":"TEXT","cardinality":"SCALAR","requiredness":"OPTIONAL"}],\
                "excerpts":[]}""";

        ExtractionInputBundle bundle = GenerationExtractionJobProcessor.bundleMapper().readValue(json, ExtractionInputBundle.class);

        assertThat(bundle.fields().getFirst().label()).isNull();
        assertThat(bundle.fields().getFirst().existingValueText()).isNull();
        assertThat(bundle.composableFieldIds()).isEmpty();
    }
}
