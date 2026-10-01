package io.github.vihuynh72.brownie.worker.testinfra;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test class that needs Docker: one that uses
 * {@link SharedContainers}, starts a container of its own, or runs
 * {@code docker} itself. A quick build on a machine without Docker, or in a
 * hurry, leaves them out with {@code ./mvnw -o -B test -DexcludedGroups=docker};
 * an ordinary build, and CI, runs every one.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("docker")
public @interface DockerTest {
}
