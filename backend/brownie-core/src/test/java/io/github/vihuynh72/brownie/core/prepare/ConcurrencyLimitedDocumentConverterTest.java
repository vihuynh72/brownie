package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.compile.ConcurrencyLimitedDocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.DocumentRenderer;
import io.github.vihuynh72.brownie.core.compile.RenderCapacityExceededException;
import io.github.vihuynh72.brownie.core.compile.RenderSlots;
import io.github.vihuynh72.brownie.core.compile.RenderedPdf;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversion starts the same container a render does, so the two have to
 * wait for the same turns: with only one turn, a render in progress keeps a
 * conversion waiting, and the other way round.
 */
class ConcurrencyLimitedDocumentConverterTest {

    private static final RenderedPdf RENDERED = new RenderedPdf(new byte[] {1}, "test renderer", "text");
    private static final ConvertedDocument CONVERTED = new ConvertedDocument(new byte[] {2}, "test converter");

    @Test
    void aRenderInProgressKeepsAConversionWaitingForTheSameTurn() throws Exception {
        RenderSlots slots = new RenderSlots(1, Duration.ofMillis(50));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DocumentRenderer renderer = new ConcurrencyLimitedDocumentRenderer(docx -> {
            started.countDown();
            await(release);
            return RENDERED;
        }, slots);
        DocumentConverter converter = new ConcurrencyLimitedDocumentConverter((source, format) -> CONVERTED, slots);
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try {
            Future<RenderedPdf> render = requests.submit(() -> renderer.renderToPdf(new byte[] {0}));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertThrows(RenderCapacityExceededException.class,
                    () -> converter.convertToDocx(new byte[] {0}, ConvertibleFormat.RTF));

            release.countDown();
            render.get(5, TimeUnit.SECONDS);
            assertEquals(CONVERTED, converter.convertToDocx(new byte[] {0}, ConvertibleFormat.RTF));
        } finally {
            requests.shutdownNow();
        }
    }

    @Test
    void aConversionInProgressKeepsARenderWaitingForTheSameTurn() throws Exception {
        RenderSlots slots = new RenderSlots(1, Duration.ofMillis(50));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DocumentConverter converter = new ConcurrencyLimitedDocumentConverter((source, format) -> {
            started.countDown();
            await(release);
            return CONVERTED;
        }, slots);
        DocumentRenderer renderer = new ConcurrencyLimitedDocumentRenderer(docx -> RENDERED, slots);
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try {
            Future<ConvertedDocument> conversion =
                    requests.submit(() -> converter.convertToDocx(new byte[] {0}, ConvertibleFormat.ODT));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertThrows(RenderCapacityExceededException.class, () -> renderer.renderToPdf(new byte[] {0}));

            release.countDown();
            conversion.get(5, TimeUnit.SECONDS);
            assertEquals(RENDERED, renderer.renderToPdf(new byte[] {0}));
        } finally {
            requests.shutdownNow();
        }
    }

    @Test
    void aConversionThatFailsGivesItsTurnBack() {
        RenderSlots slots = new RenderSlots(1, Duration.ofMillis(10));
        DocumentConverter failing = new ConcurrencyLimitedDocumentConverter((source, format) -> {
            throw new DocumentConversionException(DocumentConversionException.Reason.DAMAGED, "boom");
        }, slots);

        assertThrows(DocumentConversionException.class, () -> failing.convertToDocx(new byte[] {0}, ConvertibleFormat.WORD_97));
        // Were the turn lost, this would be refused for capacity rather than fail the same way again.
        assertThrows(DocumentConversionException.class, () -> failing.convertToDocx(new byte[] {0}, ConvertibleFormat.WORD_97));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
