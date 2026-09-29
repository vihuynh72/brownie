package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.action.GoogleDocContent;
import io.github.vihuynh72.brownie.core.action.GoogleDocs;
import io.github.vihuynh72.brownie.core.action.WriteAnswer;
import io.github.vihuynh72.brownie.core.connector.ConnectorNotConfiguredException;

import java.util.List;
import java.util.Optional;

/**
 * Stands in for Google Docs where Google is not set up: every read is
 * refused with one clear reason, and an addition, never reached since
 * nothing is offered there, would leave nothing and be answered as not made.
 */
final class NotConfiguredDocs implements GoogleDocs {

    @Override
    public Optional<GoogleDocContent> read(String accessToken, String documentId) {
        throw new ConnectorNotConfiguredException();
    }

    @Override
    public WriteAnswer append(String accessToken, String documentId, String text, String requiredRevisionId) {
        return new WriteAnswer.NotAppliedRetryable(null, List.of(), false);
    }
}
