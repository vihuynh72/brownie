package io.github.vihuynh72.brownie.api.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the hosted profiles promise, checked against the file rather than
 * against memory.
 *
 * <p>These four settings are the difference between a session cookie that is
 * safe behind a proxy and one that is not, and between an application that
 * knows the address a browser used and one that builds its sign-in callback
 * for a container's own name. None of them can be exercised by a test that
 * runs on a laptop over plain HTTP, and all of them are silent when wrong --
 * a missing Secure flag does not fail anything, it just sends the session in
 * the clear the first time a person types the address without a scheme. So
 * what is checked here is that the profile really carries them.
 */
class HostedProfileSettingsTest {

    private void withProfile(String profile, java.util.function.Consumer<Environment> assertions) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertions.accept(context.getEnvironment()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pilot", "production"})
    void aHostedProfileTrustsTheProxyAndProtectsTheSessionCookie(String profile) {
        withProfile(profile, environment -> {
            assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
            assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
            assertThat(environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class)).isTrue();
            assertThat(environment.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        });
    }

    /**
     * The opposite has to hold as well: local development is plain HTTP on
     * loopback, and a Secure cookie there would simply never be stored, which
     * looks exactly like being unable to sign in.
     */
    @Test
    void localDevelopmentDoesNotDemandASecureCookieOrTrustForwardedHeaders() {
        withProfile("local", environment -> {
            assertThat(environment.getProperty("server.forward-headers-strategy")).isNull();
            assertThat(environment.getProperty("server.servlet.session.cookie.secure")).isNull();
        });
    }
}
