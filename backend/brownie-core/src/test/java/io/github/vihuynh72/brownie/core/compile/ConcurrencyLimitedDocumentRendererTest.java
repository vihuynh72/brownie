package io.github.vihuynh72.brownie.core.compile;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyLimitedDocumentRendererTest {

    private static final RenderedPdf RENDERED = new RenderedPdf(new byte[] {1}, "test renderer", "text");

    @Test
    void neverMoreRendersAtOnceThanAllowedHoweverManyAreAsked() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        DocumentRenderer slow = docx -> {
            int now = running.incrementAndGet();
            mostAtOnce.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return RENDERED;
        };
        DocumentRenderer limited = new ConcurrencyLimitedDocumentRenderer(slow, 2, Duration.ofSeconds(10));
        ExecutorService requests = Executors.newFixedThreadPool(8);
        try {
            Future<?>[] all = new Future<?>[8];
            for (int i = 0; i < all.length; i++) {
                all[i] = requests.submit(() -> limited.renderToPdf(new byte[] {0}));
            }
            for (Future<?> one : all) {
                one.get(20, TimeUnit.SECONDS);
            }
        } finally {
            requests.shutdownNow();
        }

        assertEquals(2, mostAtOnce.get());
    }

    @Test
    void aRequestThatCannotGetATurnInTimeIsToldToTryAgainAndTheSlotIsFreeAfterwards() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DocumentRenderer blocked = docx -> {
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return RENDERED;
        };
        DocumentRenderer limited = new ConcurrencyLimitedDocumentRenderer(blocked, 1, Duration.ofMillis(50));
        ExecutorService requests = Executors.newSingleThreadExecutor();
        try {
            Future<RenderedPdf> first = requests.submit(() -> limited.renderToPdf(new byte[] {0}));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            assertThrows(RenderCapacityExceededException.class, () -> limited.renderToPdf(new byte[] {0}));

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertEquals(RENDERED, limited.renderToPdf(new byte[] {0}));
        } finally {
            requests.shutdownNow();
        }
    }

    @Test
    void aRenderThatFailsGivesItsSlotBack() {
        DocumentRenderer failing = docx -> {
            throw new DocumentRenderException("boom", null);
        };
        DocumentRenderer limited = new ConcurrencyLimitedDocumentRenderer(failing, 1, Duration.ofMillis(10));

        assertThrows(DocumentRenderException.class, () -> limited.renderToPdf(new byte[] {0}));
        // Were the slot lost, this would be refused for capacity rather than fail the same way again.
        assertThrows(DocumentRenderException.class, () -> limited.renderToPdf(new byte[] {0}));
    }
}
