package io.github.vihuynh72.brownie.api.job;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRequestHasherTest {

    private final CanonicalRequestHasher hasher = new CanonicalRequestHasher(new ObjectMapper());

    @Test
    void nestedObjectPropertyOrderDoesNotChangeTheDigest() {
        var first = hasher.hashJson("""
                {"stage":"render","target":{"version":4,"id":31},"options":{"mode":"safe","limit":2}}
                """);
        var reordered = hasher.hashJson("""
                {"options":{"limit":2,"mode":"safe"},"target":{"id":31,"version":4},"stage":"render"}
                """);

        assertThat(reordered).isEqualTo(first);
    }

    @Test
    void arrayOrderRemainsPartOfTheDigest() {
        var first = hasher.hashJson("{" + "\"steps\":[\"parse\",\"render\"]}");
        var reordered = hasher.hashJson("{" + "\"steps\":[\"render\",\"parse\"]}");

        assertThat(reordered).isNotEqualTo(first);
    }
}
