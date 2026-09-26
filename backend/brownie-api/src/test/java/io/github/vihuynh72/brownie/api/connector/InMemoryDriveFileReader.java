package io.github.vihuynh72.brownie.api.connector;

import io.github.vihuynh72.brownie.core.connector.ConnectorAccess;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceTooLargeException;
import io.github.vihuynh72.brownie.core.connector.ConnectorResourceUnavailableException;
import io.github.vihuynh72.brownie.core.connector.DriveFile;
import io.github.vihuynh72.brownie.core.connector.DriveFileReader;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A pretend Google Drive that behaves as {@link DriveFileReader} says, for
 * tests of everything that uses it. It talks to nothing: it holds files in
 * memory, records every call, can be told to refuse any call in any of the
 * ways the contract allows, and can hold a read until a test lets it go.
 */
final class InMemoryDriveFileReader implements DriveFileReader {

    /** One call as it was made. {@code maxBytes} is null for a description. */
    record Call(String method, String fileId, Integer maxBytes) {
    }

    private record Stored(DriveFile file, byte[] content) {
    }

    private final Map<String, Stored> files = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> refusals = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<String> tokens = new CopyOnWriteArrayList<>();
    private final AtomicReference<CountDownLatch> readStarted = new AtomicReference<>();
    private final AtomicReference<CountDownLatch> readMayFinish = new AtomicReference<>();

    /** Puts a file in the pretend Drive, replacing one with the same id. */
    void put(DriveFile file, byte[] content) {
        files.put(file.id(), new Stored(file, content.clone()));
    }

    void remove(String fileId) {
        files.remove(fileId);
    }

    /** Every later call of {@code method} ("describeFile", "readGoogleDocAsText", "readTextFile") for this file throws it. */
    void refuse(String method, String fileId, RuntimeException refusal) {
        refusals.put(method + ":" + fileId, refusal);
    }

    void stopRefusing() {
        refusals.clear();
    }

    /** The next content reads count {@code started} down, then wait for {@code mayFinish}. */
    void holdReads(CountDownLatch started, CountDownLatch mayFinish) {
        readStarted.set(started);
        readMayFinish.set(mayFinish);
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    /** The access token each call was made with, in the same order as {@link #calls()}. */
    List<String> tokensUsed() {
        return List.copyOf(tokens);
    }

    /** Forgets every file, refusal and call, for a test that shares this stand-in with others. */
    void clear() {
        files.clear();
        refusals.clear();
        calls.clear();
        tokens.clear();
        readStarted.set(null);
        readMayFinish.set(null);
    }

    @Override
    public DriveFile describeFile(String accessToken, String fileId) {
        calls.add(new Call("describeFile", fileId, null));
        tokens.add(accessToken);
        refuseIfTold("describeFile", fileId);
        return stored(fileId).file();
    }

    @Override
    public byte[] readGoogleDocAsText(String accessToken, String fileId, int maxBytes) {
        return read(accessToken, "readGoogleDocAsText", fileId, maxBytes, true);
    }

    @Override
    public byte[] readTextFile(String accessToken, String fileId, int maxBytes) {
        return read(accessToken, "readTextFile", fileId, maxBytes, false);
    }

    private byte[] read(String accessToken, String method, String fileId, int maxBytes, boolean googleDoc) {
        calls.add(new Call(method, fileId, maxBytes));
        tokens.add(accessToken);
        refuseIfTold(method, fileId);
        Stored stored = stored(fileId);
        if (stored.file().isGoogleDoc() != googleDoc || !(googleDoc || stored.file().isPlainText())) {
            throw new IllegalStateException("The caller broke the contract: " + method + " for a file of type " + stored.file().mimeType());
        }
        hold();
        if (stored.content().length > maxBytes) {
            throw new ConnectorResourceTooLargeException();
        }
        return Arrays.copyOf(stored.content(), stored.content().length);
    }

    private Stored stored(String fileId) {
        Stored stored = files.get(fileId);
        if (stored == null) {
            throw new ConnectorResourceUnavailableException(ConnectorResourceUnavailableException.Reason.GONE, ConnectorAccess.DRIVE_FILES);
        }
        return stored;
    }

    private void refuseIfTold(String method, String fileId) {
        RuntimeException refusal = refusals.get(method + ":" + fileId);
        if (refusal != null) {
            throw refusal;
        }
    }

    private void hold() {
        CountDownLatch started = readStarted.getAndSet(null);
        CountDownLatch mayFinish = readMayFinish.getAndSet(null);
        if (started == null || mayFinish == null) {
            return;
        }
        started.countDown();
        try {
            if (!mayFinish.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("A held read was never let go.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
