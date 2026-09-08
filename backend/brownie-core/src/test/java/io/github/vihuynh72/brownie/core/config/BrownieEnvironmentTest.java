package io.github.vihuynh72.brownie.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrownieEnvironmentTest {

    @ParameterizedTest
    @EnumSource(BrownieEnvironment.class)
    void resolvesItsOwnConfigValue(BrownieEnvironment environment) {
        assertEquals(environment, BrownieEnvironment.fromValue(environment.configValue()));
    }

    @NullAndEmptySource
    @ValueSource(strings = {"Local", "LOCAL", "prod", "staging", " "})
    @ParameterizedTest
    void rejectsMissingOrUnrecognizedValues(String raw) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> BrownieEnvironment.fromValue(raw));
        assertTrue(failure.getMessage().contains("local, test, pilot, production"));
    }

    @Test
    void trimsIncidentalWhitespace() {
        assertEquals(BrownieEnvironment.PILOT, BrownieEnvironment.fromValue(" pilot "));
    }
}
