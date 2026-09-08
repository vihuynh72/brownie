package io.github.vihuynh72.brownie.api.config;

import io.github.vihuynh72.brownie.core.config.BrownieEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BrownieEnvironmentListenerTest {

    private final BrownieEnvironmentListener listener = new BrownieEnvironmentListener();

    @Test
    void activatesTheProfileNamedByBrownieEnvironment() {
        StandardEnvironment environment = environmentWith("pilot");

        BrownieEnvironment resolved = listener.resolveAndActivate(environment);

        assertEquals(BrownieEnvironment.PILOT, resolved);
        assertArrayEquals(new String[] {"pilot"}, environment.getActiveProfiles());
    }

    @Test
    void failsFastWhenBrownieEnvironmentIsMissing() {
        StandardEnvironment environment = new StandardEnvironment();

        assertThrows(IllegalArgumentException.class, () -> listener.resolveAndActivate(environment));
        assertArrayEquals(new String[0], environment.getActiveProfiles());
    }

    @Test
    void failsFastWhenBrownieEnvironmentIsUnrecognized() {
        StandardEnvironment environment = environmentWith("staging");

        assertThrows(IllegalArgumentException.class, () -> listener.resolveAndActivate(environment));
    }

    private static StandardEnvironment environmentWith(String brownieEnvironment) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "test", Map.of(BrownieEnvironmentListener.ENVIRONMENT_PROPERTY, brownieEnvironment)));
        return environment;
    }
}
