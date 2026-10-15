package io.github.vihuynh72.brownie.api.config;

import io.github.vihuynh72.brownie.api.testinfra.DockerTest;
import io.github.vihuynh72.brownie.api.testinfra.SharedContainers;
import io.github.vihuynh72.brownie.api.testinfra.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Two threads can commit the same session at the same time -- the one
 * case worth proving is the one that actually happened: an async stream
 * flushing its response buffer commits the session from inside {@code
 * out.write} while the request thread commits it again at the end of the
 * initial dispatch. Both saves see an attribute written since either
 * loaded the session as new, and both insert it into
 * spring_session_attributes. Without the upsert the second insert is a
 * primary-key violation thrown from inside the stream's write call, and
 * the response chunk it was carrying never reaches the wire.
 *
 * <p>Two copies of one session standing in for the two threads makes the
 * collision deterministic: each was loaded before the attribute existed,
 * so each save inserts it as new.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.autoconfigure.exclude=")
@DockerTest
class SessionAttributeConcurrencyIntegrationTest {

    private static final String API_PASSWORD = "brownie_api_local_only";
    private static final String MIGRATION_PASSWORD = "brownie_migration_local_only";

    static final TestDatabase DB = SharedContainers.newDatabase();

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> "brownie_api");
        registry.add("spring.datasource.password", () -> API_PASSWORD);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("spring.flyway.user", () -> "brownie_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    @Autowired
    private FindByIndexNameSessionRepository<? extends Session> sessionRepository;

    @Test
    void twoSavesThatBothSeeAnAttributeAsNewDoNotCollide() {
        assertTheRaceBetweenTwoCopiesOfOneSessionIsSurvivable(sessionRepository);
    }

    // The repository bean is typed on a package-private session class, so the
    // wildcards above and here stand in for it -- a generic parameter is the
    // only way the compiler lets the captured type call save().
    private static <S extends Session> void assertTheRaceBetweenTwoCopiesOfOneSessionIsSurvivable(
            FindByIndexNameSessionRepository<S> repository) {
        S stored = repository.createSession();
        stored.setAttribute("existing", "first");
        repository.save(stored);

        S one = repository.findById(stored.getId());
        S two = repository.findById(stored.getId());
        one.setAttribute("added-under-race", "value-one");
        two.setAttribute("added-under-race", "value-two");

        assertThatCode(() -> repository.save(one)).doesNotThrowAnyException();
        assertThatCode(() -> repository.save(two)).doesNotThrowAnyException();

        assertThat(repository.findById(stored.getId()).<String>getAttribute("added-under-race"))
                .isEqualTo("value-two");
    }
}
