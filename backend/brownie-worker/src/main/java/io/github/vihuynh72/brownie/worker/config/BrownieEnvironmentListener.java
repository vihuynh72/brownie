package io.github.vihuynh72.brownie.worker.config;

import io.github.vihuynh72.brownie.core.config.BrownieEnvironment;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Resolves {@code BROWNIE_ENVIRONMENT} before any bean is created and
 * activates the matching Spring profile. Fails fast, with the message from
 * {@link BrownieEnvironment#fromValue(String)}, when the value is missing
 * or not one of the validated environments.
 *
 * <p>Must run strictly before Boot's own config-data loading
 * ({@code EnvironmentPostProcessorApplicationListener} /
 * {@code ConfigDataEnvironmentPostProcessor}, both ordered at {@code
 * Ordered.HIGHEST_PRECEDENCE + 10}), or {@code application.yml} blocks
 * gated by {@code spring.config.activate.on-profile} are resolved while no
 * profile is active yet and are silently never applied -- the active
 * profile still reports correctly afterward, which is what made this easy
 * to miss. {@link Ordered#HIGHEST_PRECEDENCE} itself (rather than +10, which
 * ties and loses to that listener) is what actually guarantees the
 * ordering.
 *
 * <p>Setting the active profiles alone is not the end of the story either:
 * Boot's own config-data processing separately re-reads the raw {@code
 * spring.profiles.active} property afterward, and if that still disagrees
 * with what is now active, it merges the two rather than deferring to
 * whichever profile this class resolved -- so a leftover {@code
 * SPRING_PROFILES_ACTIVE} left set in the process environment (a stray
 * value from a copied script, an inherited shell session) would otherwise
 * still end up activating its own profile's settings alongside a real one.
 * Overriding that raw property to match, not only the active-profiles list
 * itself, is what actually closes that gap -- confirmed by starting a real
 * Spring Boot application with a conflicting property set and checking
 * which profiles actually end up active, not assumed from reading the
 * override call alone.
 */
public class BrownieEnvironmentListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final String ENVIRONMENT_PROPERTY = "BROWNIE_ENVIRONMENT";
    /** Also how {@code BROWNIE_WORKER_MODE} is read: an environment variable answers to its property's name. */
    static final String MODE_PROPERTY = "brownie.worker.mode";
    private static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        resolveAndActivate(event.getEnvironment());
    }

    BrownieEnvironment resolveAndActivate(ConfigurableEnvironment environment) {
        requireKnownMode(environment.getProperty(MODE_PROPERTY));
        BrownieEnvironment resolved = BrownieEnvironment.fromValue(environment.getProperty(ENVIRONMENT_PROPERTY));
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "brownieEnvironment", Map.of(ACTIVE_PROFILES_PROPERTY, resolved.configValue())));
        environment.setActiveProfiles(resolved.configValue());
        return resolved;
    }

    /**
     * The mode decides which beans exist, and the conditions that decide it
     * compare without regard to case, so by the time anything could look at
     * the value again a worker given {@code SERVE} would already be serving
     * and one given {@code Replay-Deletions} already replaying. It is
     * therefore settled here, before there are any beans: exactly one of
     * the two spellings, or nothing starts. Unset means serve.
     */
    static void requireKnownMode(String mode) {
        if (mode != null && !mode.equals("serve") && !mode.equals("replay-deletions")) {
            throw new IllegalStateException(
                    MODE_PROPERTY + " must be exactly \"serve\" or \"replay-deletions\", not \"" + mode + "\". Nothing was started.");
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
