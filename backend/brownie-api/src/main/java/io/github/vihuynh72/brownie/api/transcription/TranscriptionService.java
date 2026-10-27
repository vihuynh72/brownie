package io.github.vihuynh72.brownie.api.transcription;

import io.github.vihuynh72.brownie.core.generation.usage.MemberUsageRepository;
import io.github.vihuynh72.brownie.core.model.ModelUsage;
import io.github.vihuynh72.brownie.core.model.Transcription;
import io.github.vihuynh72.brownie.core.model.TranscriptionGateway;
import java.math.BigDecimal;

/**
 * The member route's spend wrapper: every transcription is reserved in the
 * same usage ledger the other model calls write to, under purpose
 * {@code TRANSCRIPTION}, and settled at what the call actually cost. The
 * audio itself never touches this class' bookkeeping -- it goes in, words
 * come out, the ledger only ever sees numbers.
 */
class TranscriptionService {

    /** Charged per audio second at the configured transcription model's rate; reserved at the two-minute cap. */
    static final String PROMPT_VERSION = "transcription-v1";
    private static final BigDecimal COST_PER_SECOND_USD = new BigDecimal("0.0001");
    private static final BigDecimal RESERVE_USD = COST_PER_SECOND_USD.multiply(new BigDecimal("120"));
    private static final String PURPOSE = "TRANSCRIPTION";

    private final MemberUsageRepository usageRepository;
    private final TranscriptionGateway gateway;
    private final String modelName;
    private final io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits monthlyUsageLimits;

    TranscriptionService(
            MemberUsageRepository usageRepository,
            TranscriptionGateway gateway,
            String modelName,
            io.github.vihuynh72.brownie.core.generation.usage.MonthlyUsageLimits monthlyUsageLimits) {
        this.usageRepository = usageRepository;
        this.gateway = gateway;
        this.modelName = modelName;
        this.monthlyUsageLimits = monthlyUsageLimits;
    }

    Transcription transcribe(long workspaceId, long userId, byte[] audio, String fileName) {
        var reserved = usageRepository.reserve(
                workspaceId, userId, modelName, PROMPT_VERSION, "per-second",
                0, 0, RESERVE_USD, RESERVE_USD,
                monthlyUsageLimits,
                PURPOSE);
        long usageId;
        try {
            usageId = reserved.requireReserved(
                    io.github.vihuynh72.brownie.core.generation.usage.UsageLimits.defaultRunLimits(), monthlyUsageLimits);
        } catch (io.github.vihuynh72.brownie.core.generation.usage.BudgetExceededException spent) {
            throw new TranscriptionRefusal(spent.getMessage());
        }

        try {
            Transcription answer = gateway.transcribe(audio, fileName);
            BigDecimal cost = answer.durationSeconds() != null
                    ? COST_PER_SECOND_USD.multiply(BigDecimal.valueOf(answer.durationSeconds()))
                    : RESERVE_USD;
            usageRepository.settle(workspaceId, userId, usageId, 0, 0, cost, PURPOSE);
            return answer;
        } catch (io.github.vihuynh72.brownie.core.model.ModelTransportException failure) {
            usageRepository.retain(workspaceId, userId, usageId, PURPOSE);
            throw new TranscriptionRefusal(failure.getMessage());
        } catch (RuntimeException failure) {
            usageRepository.retain(workspaceId, userId, usageId, PURPOSE);
            throw failure;
        }
    }

    static final class TranscriptionRefusal extends RuntimeException {
        TranscriptionRefusal(String message) {
            super(message);
        }
    }
}
