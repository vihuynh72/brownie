package io.github.vihuynh72.brownie.api.capabilities;

import io.github.vihuynh72.brownie.core.retention.DeletionRepository;
import io.github.vihuynh72.brownie.core.retention.DeletionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A period on the privacy page is a promise that something is gone after
 * that long. Reading a setting down to the whole hour or day below it would
 * promise less time than the worker's sweeps really keep things, so every
 * part of an hour or a day counts as a whole one.
 */
class DataPracticesControllerTest {

    @Test
    void aPartOfAnHourIsSaidAsTheWholeHourAfterIt() {
        DataPracticesController.DataPracticesResponse said =
                controller(Duration.parse("PT1H30M"), Duration.parse("PT36H30M"), Duration.ofSeconds(1), Duration.ofDays(90)).dataPractices();

        assertThat(said.abandonedUploadHours()).isEqualTo(2);
        assertThat(said.refusedFileHours()).isEqualTo(37);
        assertThat(said.unusedFileHours()).isEqualTo(1);
    }

    @Test
    void aPartOfADayIsSaidAsTheWholeDayAfterItAndNeverAsNoDaysAtAll() {
        assertThat(controller(Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(12))
                .dataPractices().auditRecordDays()).isEqualTo(1);
        assertThat(controller(Duration.ofHours(24), Duration.ofHours(24), Duration.ofHours(24), Duration.parse("P1DT1M"))
                .dataPractices().auditRecordDays()).isEqualTo(2);
    }

    @Test
    void aWholeNumberOfHoursOrDaysIsSaidExactly() {
        DataPracticesController.DataPracticesResponse said =
                controller(Duration.ofHours(24), Duration.ofHours(48), Duration.ofHours(6), Duration.ofDays(90)).dataPractices();

        assertThat(said.abandonedUploadHours()).isEqualTo(24);
        assertThat(said.refusedFileHours()).isEqualTo(48);
        assertThat(said.unusedFileHours()).isEqualTo(6);
        assertThat(said.auditRecordDays()).isEqualTo(90);
        assertThat(said.trashRetentionDays()).isEqualTo(14);
    }

    /** The contract says at least one of each, and "removed after 0 hours" would be a promise nobody can keep. */
    @Test
    void aPeriodOfNothingIsNeverSaidAsLessThanOne() {
        assertThat(DataPracticesController.hoursRoundedUp(Duration.ZERO)).isEqualTo(1);
        assertThat(DataPracticesController.daysRoundedUp(Duration.ZERO)).isEqualTo(1);
        assertThat(DataPracticesController.hoursRoundedUp(Duration.ofNanos(1))).isEqualTo(1);
        assertThat(DataPracticesController.hoursRoundedUp(Duration.ofHours(1).plusNanos(1))).isEqualTo(2);
    }

    private static DataPracticesController controller(Duration abandonedUpload, Duration refusedFile, Duration unusedFile, Duration auditRecord) {
        return new DataPracticesController(
                new DeletionService(unusedRepository(), 14), abandonedUpload, refusedFile, unusedFile, auditRecord, "gpt-test", " ");
    }

    /** Reading the trash retention asks nothing of the repository, so any call reaching it is a mistake in this test. */
    private static DeletionRepository unusedRepository() {
        return (DeletionRepository) Proxy.newProxyInstance(
                DeletionRepository.class.getClassLoader(),
                new Class<?>[] {DeletionRepository.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
