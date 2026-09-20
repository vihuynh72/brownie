package io.github.vihuynh72.brownie.api.capabilities;

import io.github.vihuynh72.brownie.core.retention.DeletionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * What this deployment does with what people give it, as numbers and
 * names the privacy page can show instead of promising: how long each kind
 * of thing is kept, which outside services see any of it, and whom to ask.
 *
 * <p>Every period here is read from the same setting the worker's sweeps
 * run on (one environment variable feeds both), so what the page says is
 * what is done, and changing one changes the other. The support contact
 * is whatever whoever runs this deployment published, and absent until
 * they have: a privacy page with an invented address would be worse than
 * one that says nobody has been named yet.
 */
@RestController
@RequestMapping("/api/v1/data-practices")
class DataPracticesController {

    private final DataPracticesResponse response;

    DataPracticesController(
            DeletionService deletionService,
            @Value("${brownie.retention.published.abandoned-upload:PT24H}") Duration abandonedUpload,
            @Value("${brownie.retention.published.refused-file:PT24H}") Duration refusedFile,
            @Value("${brownie.retention.published.unused-file:PT24H}") Duration unusedFile,
            @Value("${brownie.retention.published.audit-record:P90D}") Duration auditRecord,
            @Value("${brownie.ai.openai.model}") String modelName,
            @Value("${brownie.support.contact:}") String supportContact) {
        this.response = new DataPracticesResponse(
                deletionService.trashRetentionDays(),
                hoursRoundedUp(abandonedUpload),
                hoursRoundedUp(refusedFile),
                hoursRoundedUp(unusedFile),
                daysRoundedUp(auditRecord),
                "OpenAI",
                modelName,
                supportContact == null || supportContact.isBlank() ? null : supportContact.trim());
    }

    @GetMapping
    DataPracticesResponse dataPractices() {
        return response;
    }

    /**
     * The page says something is gone after this long, so a part of an hour
     * counts as a whole one: ninety minutes read as one hour would promise
     * half an hour less than the worker actually keeps it.
     */
    static int hoursRoundedUp(Duration period) {
        return wholeUnitsRoundedUp(period, Duration.ofHours(1));
    }

    /** The same for days, which also keeps a setting under a day from reading as the zero days the contract rules out. */
    static int daysRoundedUp(Duration period) {
        return wholeUnitsRoundedUp(period, Duration.ofDays(1));
    }

    private static int wholeUnitsRoundedUp(Duration period, Duration unit) {
        long whole = period.dividedBy(unit);
        if (period.compareTo(unit.multipliedBy(whole)) > 0) {
            whole++;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, whole));
    }

    record DataPracticesResponse(
            int trashRetentionDays,
            int abandonedUploadHours,
            int refusedFileHours,
            int unusedFileHours,
            int auditRecordDays,
            String modelProvider,
            String modelName,
            String supportContact) {
    }
}
