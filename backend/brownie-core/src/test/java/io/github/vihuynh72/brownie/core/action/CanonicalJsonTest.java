package io.github.vihuynh72.brownie.core.action;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A person's approval is bound to the hash of this text, so the text must be
 * the same for the same payload however it was built, different for any
 * different payload, and impossible to spell a second way.
 */
class CanonicalJsonTest {

    @Test
    void membersAreWrittenInCodeUnitOrderWithNoWhitespaceWhateverOrderTheyWereGivenIn() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("b", 2L);
        first.put("a", "x");
        first.put("B", true);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("B", true);
        second.put("a", "x");
        second.put("b", 2);

        assertEquals("{\"B\":true,\"a\":\"x\",\"b\":2}", CanonicalJson.write(first));
        assertEquals(CanonicalJson.write(first), CanonicalJson.write(second));
    }

    @Test
    void namesAreOrderedByUtf16CodeUnitsAsRfc8785Requires() {
        // RFC 8785's own ordering example: a character outside the basic plane sorts by its surrogates.
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("\u20ac", "Euro Sign");
        map.put("\r", "Carriage Return");
        map.put("\ufb33", "Hebrew Letter Dalet With Dagesh");
        map.put("1", "One");
        map.put("\ud83d\ude00", "Emoji: Grinning Face");
        map.put("\u0080", "Control");
        map.put("\u00f6", "Latin Small Letter O With Diaeresis");

        assertEquals("{\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\u0080\":\"Control\",\"\u00f6\":\"Latin Small Letter O With Diaeresis\","
                + "\"\u20ac\":\"Euro Sign\",\"\ud83d\ude00\":\"Emoji: Grinning Face\",\"\ufb33\":\"Hebrew Letter Dalet With Dagesh\"}",
                CanonicalJson.write(map));
    }

    @Test
    void stringsEscapeOnlyWhatJsonRequiresAndKeepEverythingElseAsItIs() {
        assertEquals("\"quote \\\" back \\\\ tab \\t nl \\n cr \\r bs \\b ff \\f nul \\u0000 us \\u001f del \u007f\"",
                CanonicalJson.write("quote \" back \\ tab \t nl \n cr \r bs \b ff \f nul \u0000 us \u001f del \u007f"));
        assertEquals("\"caf\u00e9 \u2028 \ud83d\ude00 </script>\"", CanonicalJson.write("caf\u00e9 \u2028 \ud83d\ude00 </script>"));
    }

    @Test
    void listsKeepTheirOrderAndNestedValuesAreCanonicalToo() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("z", null);
        inner.put("y", List.of(3L, -4L, 0L));
        assertEquals("[{\"y\":[3,-4,0],\"z\":null},\"a\",false]", CanonicalJson.write(Arrays.asList(inner, "a", false)));
    }

    @Test
    void valuesAPayloadNeverHoldsAreRefusedRatherThanGivenASpelling() {
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(1.5d));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of("when", java.time.Instant.EPOCH)));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write(Map.of(1, "number as a name")));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write("half \ud83d of a character"));
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.write("\ude00 the other half first"));
    }

    @Test
    void theHashIsLowercaseSha256OfTheUtf8TextAndChangesWithAnyCharacter() {
        String text = CanonicalJson.write(Map.of("a", "caf\u00e9"));
        assertEquals("{\"a\":\"caf\u00e9\"}", text);
        assertEquals(64, CanonicalJson.sha256Hex(text).length());
        assertEquals(CanonicalJson.sha256Hex(text), CanonicalJson.sha256Hex(CanonicalJson.write(Map.of("a", "caf\u00e9"))));
        assertNotEquals(CanonicalJson.sha256Hex(text), CanonicalJson.sha256Hex(CanonicalJson.write(Map.of("a", "cafe"))));
        // SHA-256 of the three bytes "abc", from FIPS 180-2.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CanonicalJson.sha256Hex("abc"));
    }

    @Test
    void canonicalTextReadsBackToTheSameValueAndWritesOutIdentically() {
        String text = "{\"a\":[1,-2,{\"b\":null}],\"c\":\"line\\nbreak \\u0001 \u00e9\",\"d\":true,\"e\":9223372036854775807}";
        Object value = CanonicalJson.read(text);
        assertEquals(text, CanonicalJson.write(value));
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        assertEquals(List.of("a", "c", "d", "e"), List.copyOf(map.keySet()));
        assertEquals(Long.MAX_VALUE, map.get("e"));
        assertEquals("line\nbreak \u0001 \u00e9", map.get("c"));
    }

    @Test
    void anythingThatIsNotAlreadyCanonicalIsRefusedWhenRead() {
        List<String> notCanonical = List.of(
                "{\"b\":1,\"a\":2}",            // members out of order
                "{ \"a\":1}",                    // whitespace
                "{\"a\":01}",                    // a leading zero
                "{\"a\":-0}",                    // minus zero
                "{\"a\":1.0}",                   // a fraction
                "{\"a\":1e2}",                   // an exponent
                "{\"a\":\"\\u0041\"}",          // an escape the writer never uses
                "{\"a\":\"\\/\"}",              // an escaped solidus
                "{\"a\":\"\u0001\"}",            // a raw control character
                "{\"a\":1,\"a\":1}",             // a member named twice
                "{\"a\":tru}",                   // a broken literal
                "{\"a\":1}x",                    // text after the value
                "{\"a\":\"open",                 // cut short
                "{\"a\":\"\\u00",                // cut short inside an escape
                "");
        for (String text : notCanonical) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalJson.read(text), text);
        }
    }

    @Test
    void readingRefusesTextNestedDeeperThanAnyPayload() {
        String deep = "[".repeat(40) + "]".repeat(40);
        assertThrows(IllegalArgumentException.class, () -> CanonicalJson.read(deep));
    }
}
