package io.github.vihuynh72.brownie.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Loads the real application context under the "test" profile. Startup
 * itself normally goes through {@link BrownieWorkerApplication#main} so
 * that {@code BROWNIE_ENVIRONMENT} selects the profile; here the profile
 * is set directly since a JUnit-managed context does not go through that
 * entry point.
 */
@SpringBootTest
@ActiveProfiles("test")
class BrownieWorkerApplicationTests {

    @Test
    void contextLoads() {
    }
}
