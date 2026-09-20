package io.github.vihuynh72.brownie.core.support;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportGrantServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-03-01T12:00:00Z");

    @Test
    void aGrantLastsBetweenOneAndSevenDaysAndNothingIsStoredForAnyOtherLength() {
        RecordingRepository repository = new RecordingRepository();
        SupportGrantService service = new SupportGrantService(repository);

        assertThrows(IllegalArgumentException.class, () -> service.grant(7, 1, SupportGrantScope.CONTENT, 0));
        assertThrows(IllegalArgumentException.class, () -> service.grant(7, 1, SupportGrantScope.CONTENT, 8));
        assertTrue(repository.created.isEmpty());

        assertEquals(SupportGrantScope.METADATA, service.grant(7, 1, SupportGrantScope.METADATA, 1).scope());
        assertEquals(SupportGrantScope.CONTENT, service.grant(7, 1, SupportGrantScope.CONTENT, 7).scope());
        assertEquals(List.of(1, 7), repository.created);
    }

    @Test
    void aSecondOpenGrantOfTheSameScopeIsAConflictNotASecondGrant() {
        RecordingRepository repository = new RecordingRepository();
        repository.alreadyOpen = true;
        SupportGrantService service = new SupportGrantService(repository);

        assertThrows(SupportGrantConflictException.class, () -> service.grant(7, 1, SupportGrantScope.CONTENT, 3));
    }

    @Test
    void revokingWhatIsNotOpenIsNotFound() {
        RecordingRepository repository = new RecordingRepository();
        SupportGrantService service = new SupportGrantService(repository);

        assertThrows(SupportGrantNotFoundException.class, () -> service.revoke(7, 1, 99));

        repository.open = new SupportGrant(99, 7, 1, SupportGrantScope.CONTENT, NOW, NOW.plusDays(2), null);
        SupportGrant revoked = service.revoke(7, 1, 99);
        assertFalse(revoked.isActiveAt(NOW.plusHours(1)));
    }

    @Test
    void aGrantIsActiveOnlyUntilItExpiresOrIsRevoked() {
        SupportGrant open = new SupportGrant(1, 7, 1, SupportGrantScope.METADATA, NOW, NOW.plusDays(1), null);
        assertTrue(open.isActiveAt(NOW.plusHours(23)));
        assertFalse(open.isActiveAt(NOW.plusDays(1)));

        SupportGrant revoked = new SupportGrant(1, 7, 1, SupportGrantScope.METADATA, NOW, NOW.plusDays(1), NOW.plusHours(2));
        assertFalse(revoked.isActiveAt(NOW.plusHours(3)));
    }

    private static final class RecordingRepository implements SupportGrantRepository {

        private final List<Integer> created = new ArrayList<>();
        private boolean alreadyOpen;
        private SupportGrant open;

        @Override
        public Optional<SupportGrant> create(long workspaceId, long userId, SupportGrantScope scope, int days) {
            if (alreadyOpen) {
                return Optional.empty();
            }
            created.add(days);
            return Optional.of(new SupportGrant(created.size(), workspaceId, userId, scope, NOW, NOW.plusDays(days), null));
        }

        @Override
        public List<SupportGrant> findAll(long workspaceId, long userId) {
            return List.of();
        }

        @Override
        public Optional<SupportGrant> revoke(long workspaceId, long userId, long grantId) {
            if (open == null || open.id() != grantId) {
                return Optional.empty();
            }
            SupportGrant revoked = new SupportGrant(
                    open.id(), open.workspaceId(), open.grantedByUserId(), open.scope(), open.grantedAt(), open.expiresAt(), NOW);
            open = null;
            return Optional.of(revoked);
        }
    }
}
