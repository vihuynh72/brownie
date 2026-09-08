package io.github.vihuynh72.brownie.worker.config;

import io.github.vihuynh72.brownie.core.config.BrownieEnvironment;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

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
 */
public class BrownieEnvironmentListener
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final String ENVIRONMENT_PROPERTY = "BROWNIE_ENVIRONMENT";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        resolveAndActivate(event.getEnvironment());
    }

    BrownieEnvironment resolveAndActivate(ConfigurableEnvironment environment) {
        BrownieEnvironment resolved = BrownieEnvironment.fromValue(environment.getProperty(ENVIRONMENT_PROPERTY));
        environment.setActiveProfiles(resolved.configValue());
        return resolved;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
