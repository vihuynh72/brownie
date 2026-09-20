package io.github.vihuynh72.brownie.core.retention;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeletionArchiverTest {

    private static final OffsetDateTime T = OffsetDateTime.parse("2026-03-01T12:00:00Z");

    @Test
    void anEntryIsWrittenOutsideTheDatabaseBeforeTheDatabaseIsToldItWas() {
        RecordingRepository repository = new RecordingRepository();
        repository.unarchived.add(entry(1, DeletionScope.DOCUMENT, 10));
        repository.unarchived.add(entry(2, DeletionScope.WORKSPACE, 7));
        InMemoryArchive archive = new InMemoryArchive();

        DeletionArchiver.ArchiveResult result = new DeletionArchiver(repository, archive).archiveOnce(10);

        assertEquals(new DeletionArchiver.ArchiveResult(2, 0), result);
        assertEquals(List.of("add 1", "mark 1", "add 2", "mark 2"), merged(archive.log, repository.log));
    }

    @Test
    void anArchiveThatCannotBeWrittenLeavesTheEntryUnmarkedAndTheRestOfTheBatchStillGoes() {
        RecordingRepository repository = new RecordingRepository();
        repository.unarchived.add(entry(1, DeletionScope.DOCUMENT, 10));
        repository.unarchived.add(entry(2, DeletionScope.DOCUMENT, 11));
        InMemoryArchive archive = new InMemoryArchive();
        archive.failFor = 1;

        DeletionArchiver.ArchiveResult result = new DeletionArchiver(repository, archive).archiveOnce(10);

        assertEquals(new DeletionArchiver.ArchiveResult(1, 1), result);
        assertFalse(repository.marked.contains(1L));
        assertTrue(repository.marked.contains(2L));
    }

    @Test
    void replayAppliesEveryEntryAndSaysWhichOnesFoundSomethingToRemove() throws IOException {
        RecordingRepository repository = new RecordingRepository();
        repository.replayOutcomes.put(10L, "REPLAYED");
        repository.replayOutcomes.put(11L, "ABSENT");
        repository.replayOutcomes.put(12L, "JOBS_STILL_STOPPING");
        InMemoryArchive archive = new InMemoryArchive();
        archive.entries.add(entry(1, DeletionScope.DOCUMENT, 10));
        archive.entries.add(entry(2, DeletionScope.DOCUMENT, 11));
        archive.entries.add(entry(3, DeletionScope.DOCUMENT, 12));

        DeletionArchiver.ReplayResult result = new DeletionArchiver(repository, archive).replayAll();

        assertEquals(new DeletionArchiver.ReplayResult(3, 1, 1, 1), result);
        assertFalse(result.complete());
    }

    private static ArchivedDeletion entry(long requestId, DeletionScope scope, long targetId) {
        return new ArchivedDeletion(requestId, 7, scope, targetId, 1, T, T.plusDays(30), T.minusDays(90), "{\"rowsRemoved\":3}");
    }

    /** Both fakes stamp a shared counter, so the two logs can be read back as one sequence. */
    private static final int[] CLOCK = {0};

    private static List<String> merged(List<String[]> a, List<String[]> b) {
        List<String[]> all = new ArrayList<>(a);
        all.addAll(b);
        all.sort((x, y) -> Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0])));
        return all.stream().map(item -> item[1]).toList();
    }

    private static final class RecordingRepository implements DeletionArchiveRepository {

        private final List<ArchivedDeletion> unarchived = new ArrayList<>();
        private final List<Long> marked = new ArrayList<>();
        private final Map<Long, String> replayOutcomes = new HashMap<>();
        private final List<String[]> log = new ArrayList<>();

        @Override
        public List<ArchivedDeletion> collectUnarchived(int limit) {
            return List.copyOf(unarchived);
        }

        @Override
        public boolean markArchived(long requestId) {
            marked.add(requestId);
            log.add(new String[] {String.valueOf(CLOCK[0]++), "mark " + requestId});
            return true;
        }

        @Override
        public String replay(ArchivedDeletion entry) {
            return replayOutcomes.get(entry.targetId());
        }
    }

    private static final class InMemoryArchive implements DeletionLedgerArchive {

        private final List<ArchivedDeletion> entries = new ArrayList<>();
        private final List<String[]> log = new ArrayList<>();
        private long failFor = -1;

        @Override
        public void add(ArchivedDeletion entry) throws IOException {
            if (entry.requestId() == failFor) {
                throw new IOException("archive unavailable");
            }
            entries.add(entry);
            log.add(new String[] {String.valueOf(CLOCK[0]++), "add " + entry.requestId()});
        }

        @Override
        public List<ArchivedDeletion> readAll() {
            return List.copyOf(entries);
        }
    }
}
