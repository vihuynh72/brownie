package io.github.vihuynh72.brownie.core.prepare;

import io.github.vihuynh72.brownie.core.document.PdfFormGraph;
import io.github.vihuynh72.brownie.core.document.PdfRect;
import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.generation.usage.ModelPricing;
import io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageLimits;
import io.github.vihuynh72.brownie.core.generation.usage.UsageReservationOutcome;
import io.github.vihuynh72.brownie.core.model.JsonSchema;
import io.github.vihuynh72.brownie.core.model.ModelCompletion;
import io.github.vihuynh72.brownie.core.model.ModelGateway;
import io.github.vihuynh72.brownie.core.model.ModelImage;
import io.github.vihuynh72.brownie.core.model.ModelMessage;
import io.github.vihuynh72.brownie.core.model.ModelMessageRole;
import io.github.vihuynh72.brownie.core.model.ModelRequest;
import io.github.vihuynh72.brownie.core.model.ModelTransportException;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.template.FieldIds;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The places a vision model finds on a scanned page, converted to the boxes Brownie keeps. */
class VisionSpotFinderTest {

    private static final long WORKSPACE = 3L;
    private static final long USER = 7L;
    private static final int PAGE_WIDTH = 1650;
    private static final int PAGE_HEIGHT = 2200;

    private final ModelPricing pricing = new ModelPricing(new BigDecimal("1"), new BigDecimal("2"));
    private final MemberUsageRepository ledger = new MemberUsageRepository() {
        @Override
        public UsageReservationOutcome reserve(long workspaceId, long userId, String modelName, String promptVersion,
                String rateCard, int estimatedInputTokens, int estimatedMaxOutputTokens, BigDecimal estimatedCostUsd,
                BigDecimal runLimitUsd, MonthlyUsageLimits monthlyLimits, String purpose) {
            return new UsageReservationOutcome("RESERVED", 1L);
        }
        @Override
        public boolean settle(long workspaceId, long userId, long usageId, int inputTokens, int outputTokens,
                BigDecimal actualCostUsd, String purpose) {
            return true;
        }
        @Override
        public boolean retain(long workspaceId, long userId, long usageId, String purpose) {
            return true;
        }
        @Override
        public java.util.Optional<io.github.vihuynh72.brownie.core.generation.usage.UsageSummary> summary(
                long workspaceId, long userId, BigDecimal globalMonthLimitUsd, BigDecimal nextRequestUsd) {
            return java.util.Optional.empty();
        }
    };
    private final UsageLimits limits = new UsageLimits(50, 100_000, 20_000, new BigDecimal("1"));
    private final MonthlyUsageLimits monthly = new MonthlyUsageLimits(new BigDecimal("10"), new BigDecimal("100"));

    private final ScanPageRasterizer rasterizer = new ScanPageRasterizer() {
        @Override
        public PageJpeg jpegOf(byte[] pdf, int pageIndex) throws IOException {
            byte[] pixels = new byte[PAGE_WIDTH * PAGE_HEIGHT];
            java.util.Arrays.fill(pixels, (byte) 255);
            // A drawn line: row 1,320 (60% down the page) is dark across the middle half.
            int lineRow = (int) (0.60 * PAGE_HEIGHT);
            for (int x = (int) (0.25 * PAGE_WIDTH); x < (int) (0.75 * PAGE_WIDTH); x++) {
                pixels[lineRow * PAGE_WIDTH + x] = 20;
            }
            return new PageJpeg(new byte[]{1, 2, 3}, PAGE_WIDTH, PAGE_HEIGHT, pixels);
        }
    };

    private final ModelGateway gateway = request -> new ModelCompletion.Success(
            "{\"places\":[{\"label\":\"Legal name\",\"type\":\"TEXT\",\"box\":[250,550,750,580]}]}", new ModelUsage(100, 50));

    private final VisionPlacesParser parser = (pageNumber, content) -> List.of(
            new VisionSpotFinder.FoundBox("Legal name", "TEXT", 0.250, 0.550, 0.750, 0.580, pageNumber));

    private final VisionSpotFinder finder = new VisionSpotFinder(rasterizer, parser, gateway, ledger, pricing, limits, monthly, "test-model");

    @Test
    void aTextPlaceAboveADrawnLineIsKeptAndSnappedToIt() throws IOException {
        VisionSpotFinder.Result result = finder.find(WORKSPACE, USER, new byte[]{1}, graph());

        assertEquals(1, result.boxes().size());
        VisionSpotFinder.FoundBox found = result.boxes().get(0);
        assertEquals("Legal name", found.label());
        assertEquals("TEXT", found.type());
        assertEquals(0.250, found.left(), 1e-6);
        assertEquals(0.550, found.top(), 1e-6);
        // The bottom moved down onto the drawn line at row 60% of the page.
        assertEquals(0.60, found.bottom(), 0.002);
    }

    @Test
    void aSignatureAndACheckboxAreCountedNotKept() {
        VisionPlacesParser kinds = (pageNumber, content) -> List.of(
                new VisionSpotFinder.FoundBox("Signature", "SIGNATURE", 0.1, 0.9, 0.5, 0.95, pageNumber),
                new VisionSpotFinder.FoundBox("Subscribe", "CHECKBOX", 0.1, 0.8, 0.3, 0.85, pageNumber),
                new VisionSpotFinder.FoundBox("Legal name", "TEXT", 0.25, 0.55, 0.75, 0.58, pageNumber));
        VisionSpotFinder counted = new VisionSpotFinder(rasterizer, kinds, gateway, ledger, pricing, limits, monthly, "test-model");

        VisionSpotFinder.Result result;
        try {
            result = counted.find(WORKSPACE, USER, new byte[]{1}, graph());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertEquals(1, result.signatureCount());
        assertEquals(1, result.checkboxCount());
        assertEquals(1, result.boxes().size());
    }

    @Test
    void aPageTheModelCannotReadIsSkipped() {
        ModelGateway refuses = request -> new ModelCompletion.Refusal("No.", new ModelUsage(0, 0));
        VisionSpotFinder skipped = new VisionSpotFinder(rasterizer, parser, refuses, ledger, pricing, limits, monthly, "test-model");
        VisionSpotFinder.Result result;
        try {
            result = skipped.find(WORKSPACE, USER, new byte[]{1}, graph());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertTrue(result.boxes().isEmpty());
    }

    private static PdfFormGraph graph() {
        PdfFormGraph.CropBox crop = new PdfFormGraph.CropBox(0, 0, 612, 792);
        PdfFormGraph.Page page = new PdfFormGraph.Page(1, crop, 0, 1, false, List.of(), List.of(), List.of(), List.of());
        PdfFormGraph.AcroForm form = new PdfFormGraph.AcroForm(true, PdfFormGraph.XfaKind.NONE, false, List.of(), 0);
        return new PdfFormGraph("scan", List.of(page), form, new PdfFormGraph.Risks(false, false, false));
    }
}
