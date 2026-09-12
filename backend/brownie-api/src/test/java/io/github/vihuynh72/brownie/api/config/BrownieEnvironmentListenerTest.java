package io.github.vihuynh72.brownie.api.config;

import io.github.vihuynh72.brownie.core.config.BrownieEnvironment;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
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
    void productionOverridesAnyProfileAlreadyActiveRatherThanAddingToIt() {
        StandardEnvironment environment = environmentWith("production");
        // Simulates a stray SPRING_PROFILES_ACTIVE=test left set in the
        // process environment -- the one way the test profile's
        // placeholder OIDC credentials could otherwise end up active
        // alongside a real production run.
        environment.addActiveProfile("test");

        listener.resolveAndActivate(environment);

        assertArrayEquals(new String[] {"production"}, environment.getActiveProfiles());
    }

    /**
     * The test above only proves what {@code Environment.getActiveProfiles()}
     * reports immediately after this class's own call -- it does not run
     * Boot's own config-data processing at all, which turned out to matter:
     * that processing re-reads the raw {@code spring.profiles.active}
     * property afterward and merges it back in on its own if it still
     * disagrees with what is active, undoing a same-named profile override
     * that never also corrected that raw property. A real, minimal Spring
     * Boot startup with that exact conflict is what actually exercises the
     * code path this class has to defeat, and is what first caught the gap
     * this test now guards.
     */
    @Test
    void aLeftoverSpringProfilesActivePropertyDoesNotSurviveAlongsideTheResolvedProfileInARealStartup() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(MinimalConfig.class)
                .web(WebApplicationType.NONE)
                .listeners(new BrownieEnvironmentListener())
                .properties(
                        BrownieEnvironmentListener.ENVIRONMENT_PROPERTY + "=local", "spring.profiles.active=test")
                .run();
        try {
            assertArrayEquals(new String[] {"local"}, context.getEnvironment().getActiveProfiles());
        } finally {
            context.close();
        }
    }

    @Configuration
    static class MinimalConfig {
    }

    @Test
    void failsFastWhenBrownieEnvironmentIsMissing() {
        StandardEnvironment environment = cleanEnvironment();

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

    /**
     * A plain {@code new StandardEnvironment()} still reads the real
     * process environment and system properties -- on a machine (or shell
     * session) that happens to have BROWNIE_ENVIRONMENT already set for its
     * own reasons, this test would otherwise see that real value instead of
     * the absence it means to test, and fail to fail.
     */
    private static StandardEnvironment cleanEnvironment() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return environment;
    }
}
