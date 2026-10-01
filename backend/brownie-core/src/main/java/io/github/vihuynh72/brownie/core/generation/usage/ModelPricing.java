package io.github.vihuynh72.brownie.core.generation.usage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The per-million-token rates a reservation is estimated against and a
 * request is settled at, for one model the application has a price for.
 * The rates are chosen by the model a process is configured to call, so the
 * ledger can never charge one model's requests at another model's price.
 */
public record ModelPricing(BigDecimal inputCostPerMillionTokens, BigDecimal outputCostPerMillionTokens) {

    private static final BigDecimal ONE_MILLION = new BigDecimal(1_000_000);

    /**
     * Every model the application may be configured to call, with the
     * provider's published standard rates in US dollars per million tokens.
     * Both hold below the provider's long-prompt threshold of 272,000 input
     * tokens, far above the 40,000 any one attempt of a run may send, so the
     * higher long-prompt rates never apply.
     *
     * <p>gpt-6-luna's input is priced at its cache-write rate ($0.125), not
     * its plain input rate ($0.10). The provider caches long prompts by
     * itself and bills the first use of a cached prompt at that higher rate,
     * and the usage it reports does not say which input tokens were billed
     * which way. Charging every input token at the most it can cost keeps
     * the ledger from ever counting less than the provider bills.
     *
     * <p>gpt-5.4-mini-2026-03-17 is the model Brownie was first evaluated
     * on, kept so that going back to it is a change of setting alone.
     */
    private static final Map<String, ModelPricing> PRICED_MODELS = Map.of(
            "gpt-6-luna", new ModelPricing(new BigDecimal("0.125"), new BigDecimal("0.50")),
            "gpt-5.4-mini-2026-03-17", new ModelPricing(new BigDecimal("0.75"), new BigDecimal("4.50")));

    public ModelPricing {
        Objects.requireNonNull(inputCostPerMillionTokens, "inputCostPerMillionTokens");
        Objects.requireNonNull(outputCostPerMillionTokens, "outputCostPerMillionTokens");
    }

    /**
     * The rates for {@code modelId}, matched exactly as the provider is sent
     * it. A model with no price here is refused rather than given a guessed
     * one: a process configured to call it stops at startup, before it can
     * spend anything against a price that does not describe it.
     */
    public static ModelPricing forModel(String modelId) {
        ModelPricing pricing = modelId == null ? null : PRICED_MODELS.get(modelId);
        if (pricing == null) {
            throw new IllegalArgumentException("No price is known for the model \"" + modelId + "\", so no request to it could be"
                    + " charged correctly. The models with a price are: " + String.join(", ", new TreeMap<>(PRICED_MODELS).keySet())
                    + ". Configure one of those, or add the new model's published rates to ModelPricing first.");
        }
        return pricing;
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
