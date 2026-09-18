package io.github.vihuynh72.brownie.api.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local profile binds the test-support token from an environment
 * variable with an empty default, so "the variable is not set" reaches the
 * application as an empty string, not as an absent property. That must
 * mean the route does not exist -- and, just as importantly, must not make
 * the application refuse to start. Proven with a real, Docker-free context
 * boot with the property present and blank.
 */
@SpringBootTest
@EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = "brownie.test-support.token=")
class TestSupportRouteAbsentWhenTokenBlankTests {

    @Autowired
    private ApplicationContext context;

    @Test
    void aBlankTokenMeansNoRouteAndAnApplicationThatStillStarts() {
        assertThat(context.containsBean("testSupportAuthController")).isFalse();
    }
}
