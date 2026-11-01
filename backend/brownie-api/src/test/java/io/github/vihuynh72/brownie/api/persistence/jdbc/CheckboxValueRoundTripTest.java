package io.github.vihuynh72.brownie.api.persistence.jdbc;

import tools.jackson.databind.ObjectMapper;
import io.github.vihuynh72.brownie.core.revision.DocumentContent;
import io.github.vihuynh72.brownie.core.revision.FieldValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A stored checkbox comes back as a checkbox. */
class CheckboxValueRoundTripTest {

    @Test
    void aCheckedCheckboxRoundTrips() {
        Map<String, FieldValue> decoded = DocumentContentJson.decode(new ObjectMapper(),
                DocumentContentJson.encode(new ObjectMapper(), Map.of("agree", new FieldValue.CheckboxValue(true))));
        assertEquals(new FieldValue.CheckboxValue(true), decoded.get("agree"));
    }

    @Test
    void anUncheckedCheckboxRoundTrips() {
        Map<String, FieldValue> decoded = DocumentContentJson.decode(new ObjectMapper(),
                DocumentContentJson.encode(new ObjectMapper(), Map.of("agree", new FieldValue.CheckboxValue(false))));
        assertEquals(new FieldValue.CheckboxValue(false), decoded.get("agree"));
    }
}
