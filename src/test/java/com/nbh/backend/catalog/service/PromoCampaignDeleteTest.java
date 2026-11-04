package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.repository.PromoCampaignRepository;
import com.nbh.backend.common.exception.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Old site-wide campaigns can be deleted; one running right now can't, so a sale can't vanish by a wrong press. */
class PromoCampaignDeleteTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    private final PromoCampaignRepository repository = mock(PromoCampaignRepository.class);
    private final PromoCampaignService service = new PromoCampaignService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID id = UUID.randomUUID();

    private PromoCampaign stored(boolean active, String starts, String ends) {
        PromoCampaign c = new PromoCampaign();
        c.setActive(active);
        c.setStartsAt(Instant.parse(starts));
        c.setEndsAt(Instant.parse(ends));
        when(repository.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void oneTurnedOffIsDeletedEvenInsideItsDates() {
        PromoCampaign c = stored(false, "2026-11-03T00:00:00Z", "2026-11-30T00:00:00Z");
        service.delete(id);
        verify(repository).delete(c);
    }

    @Test
    void oneThatHasEndedIsDeletedEvenIfStillSwitchedOn() {
        PromoCampaign c = stored(true, "2026-09-24T00:00:00Z", "2026-09-25T00:00:00Z");
        service.delete(id);
        verify(repository).delete(c);
    }

    @Test
    void oneRunningNowIsRefused() {
        stored(true, "2026-11-03T00:00:00Z", "2026-11-30T00:00:00Z");
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ConflictException.class).hasMessageContaining("running now");
        verify(repository, never()).delete(any());
    }
}
