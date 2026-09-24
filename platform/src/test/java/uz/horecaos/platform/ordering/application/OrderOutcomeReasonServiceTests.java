package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.application.OrderOutcomeReasonService.StaleReasonException;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore.ReasonRow;

/**
 * Batch 10 finding: {@code reorder}'s optimistic-concurrency check compared
 * {@code expectedVersion} only against the <em>highest</em> version among the
 * active reasons, not each row's own version. A concurrent edit to a
 * non-max-version reason (a rename via the ordinary update endpoint, say)
 * bumps that row's version without necessarily changing the list-wide max,
 * so the stale check silently passed even though the list did change
 * underneath the caller — contradicting {@link
 * OrderOutcomeReasonService#reorder}'s own documented guarantee that "a
 * concurrent edit... of any one of them changes that number and the whole
 * reorder is refused".
 */
class OrderOutcomeReasonServiceTests {

    private static final UUID TENANT = UUID.randomUUID();

    @Test
    @DisplayName("reorder is refused when a non-max-version reason changed since the caller last read the list")
    void reorderIsRefusedWhenANonMaxVersionReasonChangedUnderneathTheCaller() {
        JdbcOutcomeReasonStore store = mock(JdbcOutcomeReasonStore.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-23T00:00:00Z"), ZoneOffset.UTC);
        OrderOutcomeReasonService service = new OrderOutcomeReasonService(store, clock);

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();

        // The state the console actually rendered: A=1, B=1, C=5 -- the
        // console computes expectedVersion as the max it can see, 5, and
        // sends that back as If-Match. Between that read and this call,
        // another operator renamed B through the ordinary reason-update
        // endpoint, bumping it to v2 -- still below C's 5.
        when(store.list(TENANT, OutcomeReasonKind.CANCELLATION, true))
                .thenReturn(List.of(
                        row(a, OutcomeReasonKind.CANCELLATION, 1),
                        row(b, OutcomeReasonKind.CANCELLATION, 2),
                        row(c, OutcomeReasonKind.CANCELLATION, 5)));

        assertThatThrownBy(() -> service.reorder(TENANT, OutcomeReasonKind.CANCELLATION, List.of(b, a, c), 5))
                .as("the list changed underneath the caller (B moved from v1 to v2) even though the "
                        + "list-wide max stayed 5 -- the reorder's own doc promises this is refused")
                .isInstanceOf(StaleReasonException.class);
    }

    private static ReasonRow row(UUID id, OutcomeReasonKind kind, int version) {
        return new ReasonRow(
                id,
                TENANT,
                kind,
                "CUSTOMER_CANCELLED",
                "Reason " + id,
                "RELEASE",
                "TENANT",
                "FULL",
                null,
                "ACTIVE",
                version,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
