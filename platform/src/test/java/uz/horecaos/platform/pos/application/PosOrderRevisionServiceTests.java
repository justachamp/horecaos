package uz.horecaos.platform.pos.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderCategory;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.ordering.api.TillRevisionNotices;
import uz.horecaos.platform.ordering.api.TillRevisionNotices.Notice;
import uz.horecaos.platform.pos.FakePosAdapter;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.CapabilitySupport;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.domain.ExportState;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;

/**
 * Telling a till that an order it holds is now worth a different total (ADR 0137, ADR 0011, row 4.2c,
 * capability {@code ORDER_REVISION}), proven against the controlled fake: the send goes through the
 * capability gates, a repeat is the same send, a failed try keeps the notice open with a stable code, and the
 * vendor's own words never reach the notice.
 */
class PosOrderRevisionServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID EXPORT = UUID.randomUUID();
    private static final UUID BINDING = UUID.randomUUID();
    private static final UUID INSTALLATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");

    private final FakePosAdapter till = new FakePosAdapter();
    private final InMemoryNotices notices = new InMemoryNotices();
    private final JdbcPosExportStore exports = mock(JdbcPosExportStore.class);
    private final JdbcPosBindingConfiguration configuration = mock(JdbcPosBindingConfiguration.class);
    private final JdbcPosCapabilityStore capabilities = mock(JdbcPosCapabilityStore.class);
    private final PosAdapterRegistry adapters = new PosAdapterRegistry(List.of(till));
    private PosOrderRevisionService service;

    @BeforeEach
    void wire() {
        service = new PosOrderRevisionService(
                notices, exports, configuration, capabilities, adapters, Clock.fixed(NOW, ZoneOffset.UTC));
        notices.open(215_000, 208_000);
        exported("till-order-77");
        when(configuration.bindingRef(TENANT, BINDING))
                .thenReturn(Optional.of(new BindingRef(
                        BINDING,
                        INSTALLATION,
                        TENANT,
                        ProviderCategory.POS,
                        FakePosAdapter.PROVIDER_TYPE,
                        BRAND,
                        LOCATION)));
        when(configuration.resolve(any(BindingRef.class))).thenReturn(Optional.of(Map.of()));
        enabled(true, true);
    }

    @Test
    @DisplayName("a binding with ORDER_REVISION enabled and usable is sent the revised total under a key built from "
            + "the notice, and the notice closes as told")
    void theRevisedTotalIsSentAndTheNoticeCloses() {
        PosOrderRevisionService.Result result = service.tell(TENANT, ORDER, "operator-1", 2);

        assertThat(result.outcome()).isEqualTo(PosOrderRevisionService.Outcome.TOLD);
        assertThat(till.revisions()).singleElement().satisfies(sent -> {
            assertThat(sent.externalOrderId()).isEqualTo("till-order-77");
            assertThat(sent.revision().tillTotalMinor()).isEqualTo(215_000);
            assertThat(sent.revision().revisedTotalMinor()).isEqualTo(208_000);
            assertThat(sent.revision().currency()).isEqualTo("UZS");
            assertThat(sent.revision().reasonCode()).isEqualTo("CATCHWEIGHT");
            assertThat(sent.revision().idempotencyKey())
                    .isEqualTo("till-revision:" + notices.current().id() + ":2");
        });
        assertThat(notices.current().status()).isEqualTo("TOLD");
        assertThat(result.view().canTell()).as("nothing left to tell").isFalse();
    }

    @Test
    @DisplayName("telling twice is one send: the second finds no open notice and sends nothing")
    void tellingTwiceIsOneSend() {
        service.tell(TENANT, ORDER, "operator-1", 2);
        PosOrderRevisionService.Result again = service.tell(TENANT, ORDER, "operator-2", 2);

        assertThat(again.outcome()).isEqualTo(PosOrderRevisionService.Outcome.NO_OPEN_NOTICE);
        assertThat(till.revisions()).hasSize(1);
    }

    @Test
    @DisplayName(
            "a binding that has not enabled the capability is never asked, and a person is left to correct the till")
    void aBindingWithoutTheCapabilityIsNeverAsked() {
        enabled(false, true);

        PosOrderRevisionService.Result result = service.tell(TENANT, ORDER, "operator-1", 2);

        assertThat(result.outcome()).isEqualTo(PosOrderRevisionService.Outcome.NOT_AVAILABLE);
        assertThat(till.revisions()).isEmpty();
        assertThat(notices.current().status()).isEqualTo("OPEN");
        assertThat(service.view(TENANT, ORDER).canTell()).isFalse();
        assertThat(service.view(TENANT, ORDER).notice()).isNotNull();
    }

    @Test
    @DisplayName("a capability enabled but not usable on the installation is never asked either")
    void anUnusableCapabilityIsNeverAsked() {
        enabled(true, false);

        assertThat(service.tell(TENANT, ORDER, "operator-1", 2).outcome())
                .isEqualTo(PosOrderRevisionService.Outcome.NOT_AVAILABLE);
        assertThat(till.revisions()).isEmpty();
    }

    @Test
    @DisplayName("an order the vendor has not named yet cannot be told")
    void anOrderTheVendorHasNotNamedCannotBeTold() {
        exported(null);

        assertThat(service.tell(TENANT, ORDER, "operator-1", 2).outcome())
                .isEqualTo(PosOrderRevisionService.Outcome.NOT_AVAILABLE);
        assertThat(till.revisions()).isEmpty();
    }

    @Test
    @DisplayName("a refusal keeps the notice open with a stable code and never the vendor's own words")
    void aRefusalKeepsTheNoticeOpenWithAStableCode() {
        till.failNextRevisionWith(
                ProviderOutcome.rejected("CLOPOS_REFUSED", "Customer +998 90 123 45 67 at Navoi 12 cannot be changed"));

        PosOrderRevisionService.Result result = service.tell(TENANT, ORDER, "operator-1", 2);

        assertThat(result.outcome()).isEqualTo(PosOrderRevisionService.Outcome.REJECTED);
        assertThat(result.code()).isEqualTo("CLOPOS_REFUSED");
        Notice notice = notices.current();
        assertThat(notice.status()).isEqualTo("OPEN");
        assertThat(notice.attemptCount()).isEqualTo(1);
        assertThat(notice.lastFailureCode()).isEqualTo("CLOPOS_REFUSED");
        assertThat(notice.toString()).doesNotContain("998").doesNotContain("Navoi");
        assertThat(result.toString()).doesNotContain("998").doesNotContain("Navoi");
    }

    @Test
    @DisplayName("an unreachable till leaves the notice open; trying again after it is back succeeds")
    void anUnreachableTillIsRetriedLater() {
        till.failNextRevisionWith(ProviderOutcome.retryable("CONNECTION_FAILED", "down", null));

        assertThat(service.tell(TENANT, ORDER, "operator-1", 2).outcome())
                .isEqualTo(PosOrderRevisionService.Outcome.RETRYABLE);
        assertThat(notices.current().status()).isEqualTo("OPEN");

        assertThat(service.tell(TENANT, ORDER, "operator-1", 2).outcome())
                .isEqualTo(PosOrderRevisionService.Outcome.TOLD);
        assertThat(till.revisions()).hasSize(1);
    }

    @Test
    @DisplayName("a reply lost after the till applied it is unknown, and sending again is the same send")
    void aLostReplyIsUnknownAndTheRepeatIsTheSameSend() {
        till.loseNextRevisionReply();

        PosOrderRevisionService.Result lost = service.tell(TENANT, ORDER, "operator-1", 2);

        assertThat(lost.outcome()).isEqualTo(PosOrderRevisionService.Outcome.UNCERTAIN);
        assertThat(notices.current().status()).as("not believed closed").isEqualTo("OPEN");
        assertThat(till.revisions()).as("though the till did apply it").hasSize(1);

        PosOrderRevisionService.Result again = service.tell(TENANT, ORDER, "operator-1", 2);

        assertThat(again.outcome()).isEqualTo(PosOrderRevisionService.Outcome.TOLD);
        assertThat(till.revisions())
                .as("the same key: the till recognised the repeat and recorded nothing new")
                .hasSize(1);
    }

    @Test
    @DisplayName("a second weighing while the till is being asked is not closed by the answer: the till was told "
            + "the total of the notice that was read, it holds that now, and the newer notice stays open")
    void aSecondWeighingWhileTheTillIsBeingToldKeepsTheNoticeOpen() {
        till.duringNextRevision(() -> notices.weighAgain(203_000));

        PosOrderRevisionService.Result result = service.tell(TENANT, ORDER, "operator-1", 2);

        assertThat(till.revisions())
                .singleElement()
                .satisfies(sent -> assertThat(sent.revision().revisedTotalMinor())
                        .as("the till was told what the notice said when it was read")
                        .isEqualTo(208_000));
        assertThat(result.outcome()).isEqualTo(PosOrderRevisionService.Outcome.SUPERSEDED);
        Notice open = notices.current();
        assertThat(open.status())
                .as("the notice for 203,000 is a different notice from the one that was told")
                .isEqualTo("OPEN");
        assertThat(open.revision()).isEqualTo(3);
        assertThat(open.revisedTotalMinor()).isEqualTo(203_000);
        assertThat(open.tillTotalMinor())
                .as("the till holds what it was just told, so the difference shown is the real one")
                .isEqualTo(208_000);
        assertThat(result.view().canTell()).as("it can be told the new total").isTrue();
    }

    @Test
    @DisplayName("a second weighing that lands on the very total the till took leaves nothing to correct")
    void aSecondWeighingThatLandsOnTheToldTotalClosesTheNotice() {
        till.duringNextRevision(() -> notices.weighAgain(208_000));

        PosOrderRevisionService.Result result = service.tell(TENANT, ORDER, "operator-1", 2);

        assertThat(result.outcome()).isEqualTo(PosOrderRevisionService.Outcome.TOLD);
        assertThat(notices.current().status()).isEqualTo("TOLD");
    }

    @Test
    @DisplayName("an operator who looked at an older notice sends nothing and closes nothing: the notice moved")
    void aStaleSendIsRefusedBeforeAnythingIsSent() {
        notices.weighAgain(203_000);

        assertThatThrownBy(() -> service.tell(TENANT, ORDER, "operator-1", 2))
                .isInstanceOfSatisfying(PosOrderRevisionService.StaleNoticeException.class, stale -> {
                    assertThat(stale.expectedRevision()).isEqualTo(2);
                    assertThat(stale.actualRevision()).isEqualTo(3);
                });

        assertThat(till.revisions()).as("the till was not asked").isEmpty();
        assertThat(notices.current().status()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("a person who confirmed the till against an older total does not close the newer notice")
    void aStaleAcknowledgementIsRefusedAndLeavesTheNoticeOpen() {
        notices.weighAgain(203_000);

        assertThatThrownBy(() -> service.acknowledge(TENANT, ORDER, "operator-1", 2))
                .isInstanceOf(PosOrderRevisionService.StaleNoticeException.class);

        assertThat(notices.current().status()).isEqualTo("OPEN");
        assertThat(service.acknowledge(TENANT, ORDER, "operator-1", 3)).isTrue();
        assertThat(notices.current().status()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    @DisplayName("a person saying the till was corrected closes the notice, once")
    void acknowledgingClosesTheNoticeOnce() {
        assertThat(service.acknowledge(TENANT, ORDER, "operator-1", 2)).isTrue();
        assertThat(notices.current().status()).isEqualTo("ACKNOWLEDGED");
        assertThat(service.acknowledge(TENANT, ORDER, "operator-2", 2)).isFalse();
        assertThat(till.revisions())
                .as("acknowledging tells the vendor nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("an adapter that does not support revision answers not-supported, which is a refusal and not a fault")
    void anAdapterWithoutTheCapabilityRefuses() {
        // The port's default: what every real adapter inherits until a vendor is assessed.
        PosAdapter bare = mock(PosAdapter.class, CALLS_REAL_METHODS);
        ProviderOutcome outcome = bare.reviseExportedOrder(
                new PosAdapter.PosContext(TENANT, INSTALLATION, BINDING, null, Map.of(), "c"),
                "x",
                new PosAdapter.TillRevision(1, 2, "UZS", "CATCHWEIGHT", "k"));

        assertThat(outcome.status()).isEqualTo(ProviderOutcome.Status.REJECTED);
        assertThat(outcome.errorCode()).isEqualTo("NOT_SUPPORTED");
    }

    // ------------------------------------------------------------------ fixtures

    private void exported(@Nullable String externalOrderId) {
        when(exports.findDetailByOrder(TENANT, ORDER))
                .thenReturn(Optional.of(new JdbcPosExportStore.ExportDetail(
                        EXPORT,
                        ORDER,
                        ExportState.ACCEPTED,
                        1,
                        externalOrderId,
                        NOW,
                        NOW,
                        NOW,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        3)));
        when(exports.find(TENANT, EXPORT))
                .thenReturn(Optional.of(new JdbcPosExportStore.ExportRow(
                        EXPORT,
                        TENANT,
                        ORDER,
                        BINDING,
                        INSTALLATION,
                        ExportState.ACCEPTED,
                        1,
                        "corr",
                        externalOrderId,
                        null,
                        "fingerprint",
                        "phone-hash",
                        "venue-1",
                        NOW,
                        3)));
    }

    private void enabled(boolean onBinding, boolean usable) {
        when(capabilities.enabledCapabilities(TENANT, BINDING))
                .thenReturn(onBinding ? List.of("ORDER_EXPORT", "ORDER_REVISION") : List.of("ORDER_EXPORT"));
        Map<PosCapability, CapabilitySnapshot.Entry> entries = new EnumMap<>(PosCapability.class);
        entries.put(
                PosCapability.ORDER_REVISION,
                new CapabilitySnapshot.Entry(
                        usable ? CapabilitySupport.SUPPORTED : CapabilitySupport.UNSUPPORTED,
                        CapabilitySnapshot.IdempotencyBehaviour.KEYED,
                        false,
                        "fake-1",
                        Map.of(),
                        "test",
                        NOW));
        when(capabilities.readSnapshot(TENANT, INSTALLATION))
                .thenReturn(Optional.of(new CapabilitySnapshot(entries, NOW, "fake-1")));
    }

    /** The notice port, in memory: the real one is proven on the schema in TillRevisionNoticeServiceTests. */
    private static final class InMemoryNotices implements TillRevisionNotices {

        private final List<Notice> history = new CopyOnWriteArrayList<>();

        /** A second weighing before anyone acted: what {@code reviseOpen} does to the open notice. */
        void weighAgain(long revised) {
            Notice n = current();
            replace(new Notice(
                    n.id(),
                    n.tenantId(),
                    n.brandId(),
                    n.locationId(),
                    n.orderId(),
                    n.source(),
                    n.revision() + 1,
                    n.currency(),
                    n.tillTotalMinor(),
                    revised,
                    n.status(),
                    n.openedAt(),
                    n.resolvedAt(),
                    n.attemptCount(),
                    n.lastFailureCode()));
        }

        void open(long till, long revised) {
            history.add(new Notice(
                    UUID.randomUUID(),
                    TENANT,
                    BRAND,
                    LOCATION,
                    ORDER,
                    "CATCHWEIGHT",
                    2,
                    "UZS",
                    till,
                    revised,
                    "OPEN",
                    NOW,
                    null,
                    0,
                    null));
        }

        Notice current() {
            return history.get(history.size() - 1);
        }

        @Override
        public Optional<Notice> openFor(UUID tenantId, UUID orderId) {
            return Optional.of(current()).filter(Notice::open);
        }

        @Override
        public Optional<Notice> latestFor(UUID tenantId, UUID orderId) {
            return Optional.of(current());
        }

        @Override
        public boolean markTold(UUID tenantId, UUID noticeId, int revision, String actor, Instant at) {
            return close("TOLD", noticeId, revision, at);
        }

        @Override
        public boolean markAcknowledged(UUID tenantId, UUID noticeId, int revision, String actor, Instant at) {
            return close("ACKNOWLEDGED", noticeId, revision, at);
        }

        @Override
        public boolean recordTold(
                UUID tenantId, UUID noticeId, int toldRevision, long toldTotalMinor, String actor, Instant at) {
            Notice n = current();
            if (!n.id().equals(noticeId) || !n.open() || n.revision() <= toldRevision) {
                return false;
            }
            boolean agrees = n.revisedTotalMinor() == toldTotalMinor;
            replace(new Notice(
                    n.id(),
                    n.tenantId(),
                    n.brandId(),
                    n.locationId(),
                    n.orderId(),
                    n.source(),
                    n.revision(),
                    n.currency(),
                    agrees ? n.tillTotalMinor() : toldTotalMinor,
                    n.revisedTotalMinor(),
                    agrees ? "TOLD" : n.status(),
                    n.openedAt(),
                    agrees ? at : n.resolvedAt(),
                    n.attemptCount(),
                    n.lastFailureCode()));
            return true;
        }

        @Override
        public void recordFailedAttempt(UUID tenantId, UUID noticeId, String failureCode, Instant at) {
            Notice n = current();
            replace(new Notice(
                    n.id(),
                    n.tenantId(),
                    n.brandId(),
                    n.locationId(),
                    n.orderId(),
                    n.source(),
                    n.revision(),
                    n.currency(),
                    n.tillTotalMinor(),
                    n.revisedTotalMinor(),
                    n.status(),
                    n.openedAt(),
                    n.resolvedAt(),
                    n.attemptCount() + 1,
                    failureCode));
        }

        /** Closes the notice only as the revision the caller acted on, as the real store does. */
        private boolean close(String status, UUID noticeId, int revision, Instant at) {
            Notice n = current();
            if (!n.open() || !n.id().equals(noticeId) || n.revision() != revision) {
                return false;
            }
            replace(new Notice(
                    n.id(),
                    n.tenantId(),
                    n.brandId(),
                    n.locationId(),
                    n.orderId(),
                    n.source(),
                    n.revision(),
                    n.currency(),
                    n.tillTotalMinor(),
                    n.revisedTotalMinor(),
                    status,
                    n.openedAt(),
                    at,
                    n.attemptCount(),
                    n.lastFailureCode()));
            return true;
        }

        private void replace(Notice notice) {
            history.set(history.size() - 1, notice);
        }
    }
}
