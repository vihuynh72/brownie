package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/** The per-million-token uncached text rate a reservation is estimated against. The published list rates for the fixed baseline model when it was chosen: $0.75 input, $4.50 output. */
public record ModelPricing(BigDecimal inputCostPerMillionTokens, BigDecimal outputCostPerMillionTokens) {

    private static final BigDecimal ONE_MILLION = new BigDecimal(1_000_000);

    public ModelPricing {
        Objects.requireNonNull(inputCostPerMillionTokens, "inputCostPerMillionTokens");
        Objects.requireNonNull(outputCostPerMillionTokens, "outputCostPerMillionTokens");
    }

    public static ModelPricing gpt5Mini() {
        return new ModelPricing(new BigDecimal("0.75"), new BigDecimal("4.50"));
    }

    /** The rates a cost was computed under, written beside every ledger row so a later change of price never rewrites what an old request cost. */
    public String rateCard() {
        return "USD per million tokens: input " + inputCostPerMillionTokens.toPlainString()
                + ", output " + outputCostPerMillionTokens.toPlainString();
    }

    public BigDecimal estimateCost(int inputTokens, int outputTokens) {
        BigDecimal inputCost = inputCostPerMillionTokens.multiply(BigDecimal.valueOf(inputTokens)).divide(ONE_MILLION, MathContext.DECIMAL64);
        BigDecimal outputCost = outputCostPerMillionTokens.multiply(BigDecimal.valueOf(outputTokens)).divide(ONE_MILLION, MathContext.DECIMAL64);
        return inputCost.add(outputCost).setScale(6, RoundingMode.CEILING);
    }
}
