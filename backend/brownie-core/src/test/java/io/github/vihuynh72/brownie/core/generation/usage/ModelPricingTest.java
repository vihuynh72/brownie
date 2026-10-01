package io.github.vihuynh72.brownie.core.generation.usage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The price is the owner's money, so it is looked up by the exact model a
 * process calls and never guessed: a model with no price is refused, and
 * each priced model's rates and rate card are pinned here to the cent.
 */
class ModelPricingTest {

    @Test
    void gpt6LunaChargesEveryInputTokenAtTheCacheWriteRateSoTheLedgerNeverCountsLessThanIsBilled() {
        ModelPricing luna = ModelPricing.forModel("gpt-6-luna");

        assertEquals("USD per million tokens: input 0.125, output 0.50", luna.rateCard());
        assertEquals(new BigDecimal("0.125000"), luna.estimateCost(1_000_000, 0));
        assertEquals(new BigDecimal("0.500000"), luna.estimateCost(0, 1_000_000));
        // 400 tokens in at $0.125 and 120 out at $0.50 per million.
        assertEquals(new BigDecimal("0.000110"), luna.estimateCost(400, 120));
        // The smallest request of all, one token in and an Assist reply's 400 out, rounded up to the next millionth.
        assertEquals(new BigDecimal("0.000201"), luna.estimateCost(1, 400));
    }

    @Test
    void theFirstEvaluatedModelKeepsItsOwnRatesSoGoingBackToItIsASettingAlone() {
        ModelPricing mini = ModelPricing.forModel("gpt-5.4-mini-2026-03-17");

        assertEquals("USD per million tokens: input 0.75, output 4.50", mini.rateCard());
        assertEquals(new BigDecimal("0.000840"), mini.estimateCost(400, 120));
    }

    @Test
    void aModelWithNoPriceIsRefusedWithAMessageThatNamesItAndThePricedOnes() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> ModelPricing.forModel("gpt-test"));

        assertTrue(refused.getMessage().contains("\"gpt-test\""), refused.getMessage());
        assertTrue(refused.getMessage().contains("gpt-5.4-mini-2026-03-17, gpt-6-luna"), refused.getMessage());
    }

    /** The provider is sent the model's name exactly as configured, so the price is looked up exactly as well. */
    @Test
    void aNameThatIsOnlyCloseToAPricedOneIsRefusedToo() {
        assertThrows(IllegalArgumentException.class, () -> ModelPricing.forModel("GPT-6-LUNA"));
        assertThrows(IllegalArgumentException.class, () -> ModelPricing.forModel(" gpt-6-luna"));
        assertThrows(IllegalArgumentException.class, () -> ModelPricing.forModel("gpt-5.4-mini"));
        assertThrows(IllegalArgumentException.class, () -> ModelPricing.forModel(""));
        assertThrows(IllegalArgumentException.class, () -> ModelPricing.forModel(null));
    }
}
