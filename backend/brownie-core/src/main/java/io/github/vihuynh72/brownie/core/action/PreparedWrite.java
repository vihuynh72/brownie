package io.github.vihuynh72.brownie.core.action;

import io.github.vihuynh72.brownie.core.connector.UsableConnection;

/** One change, ready to leave: sent at most once, then read back. */
public interface PreparedWrite {

    /** Sends the change. Never throws for anything the provider answers; every answer is sorted into a {@link WriteAnswer}. */
    WriteAnswer send(UsableConnection connection);

    /** Reads back what an answer says was made (or already existed) and compares it with what was approved. */
    ActionOutcome readBack(UsableConnection connection, WriteAnswer answer);
}
