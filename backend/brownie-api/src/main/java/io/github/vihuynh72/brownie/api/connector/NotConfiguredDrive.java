package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;
import io.github.vihuynh72.brownie.core.connector.DriveFile;
import io.github.vihuynh72.brownie.core.connector.DriveFileReader;

/**
 * Stands in for Google Drive wherever nothing reads from it: a deployment
 * without Google, and every deployment until a Drive reader is plugged in.
 * Every read is refused with one clear reason, and nothing offers Drive.
 */
final class NotConfiguredDrive implements DriveFileReader {

    @Override
    public DriveFile describeFile(String accessToken, String fileId) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public byte[] readGoogleDocAsText(String accessToken, String fileId, int maxBytes) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public byte[] readTextFile(String accessToken, String fileId, int maxBytes) {
        throw new ConnectorNotConfiguredException();
    }
}
