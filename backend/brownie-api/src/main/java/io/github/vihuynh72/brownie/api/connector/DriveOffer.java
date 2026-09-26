package io.github.vihuynh72.brownie.api.connector;

/**
 * Whether this deployment offers Google Drive: only when Google is set up, a
 * reader that can actually open Drive files is plugged in, and whoever runs
 * it has switched Drive on ({@code BROWNIE_GOOGLE_DRIVE_OFFERED}). Until
 * then nothing offers it, and starting a pick or a copy is refused before
 * Google is asked anything. Forgetting a file already picked is never
 * refused.
 */
public record DriveOffer(boolean offered) {
}
