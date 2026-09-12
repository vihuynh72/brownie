package io.github.vihuynh72.brownie.core.config;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The only deployment environments Brownie recognizes. Every Brownie
 * process resolves exactly one of these from {@code BROWNIE_ENVIRONMENT}
 * at startup and refuses to start on anything else.
 */
public enum BrownieEnvironment {

    LOCAL("local"),
    TEST("test"),
    PILOT("pilot"),
    PRODUCTION("production");

    private final String configValue;

    BrownieEnvironment(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static BrownieEnvironment fromValue(String raw) {
        String candidate = raw == null ? "" : raw.strip();
        return Arrays.stream(values())
                .filter(environment -> environment.configValue.equals(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "BROWNIE_ENVIRONMENT must be one of "
                                + Arrays.stream(values())
                                        .map(BrownieEnvironment::configValue)
                                        .collect(Collectors.joining(", "))
                                + " but was "
                                + (raw == null ? "unset" : "'" + raw + "'")));
    }
}
