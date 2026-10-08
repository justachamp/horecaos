package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.application.OrderOutcomeReasonService.StaleReasonException;
import uz.horecaos.platform.ordering.domain.CustomerRefund;
import uz.horecaos.platform.ordering.domain.LiabilityParty;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.domain.OutcomeSystemCategory;
import uz.horecaos.platform.ordering.domain.StockDisposition;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore.ReasonRow;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;

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
        OrderOutcomeReasonService service = new OrderOutcomeReasonService(store, fact -> {}, clock);

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

        assertThatThrownBy(() -> service.reorder(
                        TENANT,
                        OrderOutcomeReasonService.Authorship.of(
                                uz.horecaos.platform.audit.api.ActorRef.user("tester", null)),
                        OutcomeReasonKind.CANCELLATION,
                        List.of(b, a, c),
                        5))
                .as("the list changed underneath the caller (B moved from v1 to v2) even though the "
                        + "list-wide max stayed 5 -- the reorder's own doc promises this is refused")
                .isInstanceOf(StaleReasonException.class);
    }

    @Test
    @DisplayName("a tenant whose brands serve ru and uz-Latn writes a reason in those two, and is not asked for en")
    void aTwoLanguageTenantOwesTwoTexts() {
        JdbcOutcomeReasonStore store = mock(JdbcOutcomeReasonStore.class);
        OrderOutcomeReasonService service = serviceFor(store, twoLanguageTenant());

        UUID created = service.create(TENANT, by(), cancellation(Map.of("ru", "Отменён", "uz-Latn", "Bekor qilindi")));

        assertThat(created).isNotNull();
        verify(store).replaceTexts(created, Map.of("ru", "Отменён", "uz-Latn", "Bekor qilindi"));
    }

    @Test
    @DisplayName("the same two texts are refused for a tenant that serves three, and the missing language is named")
    void aThreeLanguageTenantStillOwesAllThree() {
        JdbcOutcomeReasonStore store = mock(JdbcOutcomeReasonStore.class);
        OrderOutcomeReasonService unconfigured = new OrderOutcomeReasonService(
                store, fact -> {}, Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> unconfigured.create(
                        TENANT, by(), cancellation(Map.of("ru", "Отменён", "uz-Latn", "Bekor qilindi"))))
                .as("a tenant that has configured nothing owes the platform's content tier, as before")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A reason needs customer wording in ru, uz-Latn, en; missing en");
        verify(store, never()).insert(any());
    }

    @Test
    @DisplayName("a tenant that serves en but not ru is refused without en and is not asked for ru")
    void theUnionDecidesNotThePlatformTriple() {
        JdbcOutcomeReasonStore store = mock(JdbcOutcomeReasonStore.class);
        BrandLocaleLookup lookup = lookupWith(
                TenantLocaleSet.union(List.of(new TenantLocaleSet.BrandChoice(List.of("uz-Latn", "en"), "uz-Latn"))));
        OrderOutcomeReasonService service = serviceFor(store, lookup);

        assertThatThrownBy(() -> service.create(TENANT, by(), cancellation(Map.of("uz-Latn", "Bekor qilindi"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageEndingWith("missing en");
        assertThat(service.create(TENANT, by(), cancellation(Map.of("uz-Latn", "Bekor qilindi", "en", "Cancelled"))))
                .isNotNull();
    }

    private static OrderOutcomeReasonService serviceFor(JdbcOutcomeReasonStore store, BrandLocaleLookup lookup) {
        return new OrderOutcomeReasonService(
                store, fact -> {}, Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC), lookup);
    }

    private static BrandLocaleLookup twoLanguageTenant() {
        return lookupWith(
                TenantLocaleSet.union(List.of(new TenantLocaleSet.BrandChoice(List.of("ru", "uz-Latn"), "ru"))));
    }

    private static BrandLocaleLookup lookupWith(TenantLocaleSet set) {
        return new BrandLocaleLookup() {
            @Override
            public Optional<String> brandDefaultLocale(UUID tenantId, UUID brandId) {
                return Optional.of(set.defaultLocale());
            }

            @Override
            public TenantLocaleSet tenantLocaleSet(UUID tenantId) {
                return set;
            }
        };
    }

    private static OrderOutcomeReasonService.Authorship by() {
        return OrderOutcomeReasonService.Authorship.of(uz.horecaos.platform.audit.api.ActorRef.user("tester", null));
    }

    private static OrderOutcomeReasonService.CreateReason cancellation(Map<String, String> texts) {
        return new OrderOutcomeReasonService.CreateReason(
                OutcomeReasonKind.CANCELLATION,
                OutcomeSystemCategory.CUSTOMER_CANCELLED,
                "Customer cancelled",
                StockDisposition.RELEASE,
                LiabilityParty.CUSTOMER,
                CustomerRefund.FULL,
                null,
                texts);
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
