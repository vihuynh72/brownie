package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.action.DriveFileWriter;
import io.github.vihuynh72.brownie.core.action.NewDriveFile;
import io.github.vihuynh72.brownie.core.action.SavedDriveFile;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;

import java.util.List;
import java.util.Optional;

/**
 * Stands in for Google Drive where Google is not set up. Nothing is offered
 * there, so none of this is reached; if it were, nothing would leave, and a
 * save would be answered as not made.
 */
final class NotConfiguredDriveWriter implements DriveFileWriter {

    @Override
    public String reserveFileId(String accessToken) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public WriteAnswer createFile(String accessToken, NewDriveFile file) {
        return new WriteAnswer.NotAppliedRetryable(null, List.of(), false);
    }

    @Override
    public Optional<SavedDriveFile> describeSavedFile(String accessToken, String fileId) {
        throw new ConnectorNotConfiguredException();
    }
}
