package io.github.vihuynh72.brownie.api.generation;

import io.github.vihuynh72.brownie.core.prepare.NamingSource;

import java.util.Locale;

/**
 * Whether the model reads an uploaded form to decide which places are
 * meant for filling in and to name them ({@code brownie.fill-spots.model}).
 * One reading of the setting serves both the namer that runs and what the
 * capabilities and data-practices routes tell people, so what a person is
 * told about their form's text is what happens to it. Anything but
 * {@code enabled} or {@code disabled} stops the application at startup.
 */
public record FillSpotNamingSetting(boolean usesModel) {

    static FillSpotNamingSetting fromSetting(String value) {
        return switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
            case "enabled" -> new FillSpotNamingSetting(true);
            case "disabled" -> new FillSpotNamingSetting(false);
            default -> throw new IllegalStateException(
                    "brownie.fill-spots.model must be \"enabled\" or \"disabled\", not \"" + value + "\".");
        };
    }

    /** {@code MODEL} or {@code RULES}: who names the places found in an uploaded form here. */
    public String naming() {
        return (usesModel ? NamingSource.MODEL : NamingSource.RULES).name();
    }
}
