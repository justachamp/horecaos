package uz.horecaos.platform.pos.application;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.integration.api.provider.BindingRef;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.ordering.api.TillRevisionNotices;
import uz.horecaos.platform.ordering.api.TillRevisionNotices.Notice;
import uz.horecaos.platform.pos.api.CapabilitySnapshot;
import uz.horecaos.platform.pos.api.PosCapability;
import uz.horecaos.platform.pos.application.port.PosAdapter;
import uz.horecaos.platform.pos.application.port.PosAdapter.PosContext;
import uz.horecaos.platform.pos.application.port.PosAdapter.TillRevision;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosBindingConfiguration;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosCapabilityStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosExportStore;

/**
 * Telling a till that an order it holds is now worth a different total (ADR 0137, ADR 0011, row
 * {@code 4.2c}, capability {@link PosCapability#ORDER_REVISION}).
 *
 * <p>The notice is {@code ordering}'s: a captured weight moved the total of an order that was exported
 * first. What the platform can do about it is a fact about the vendor. Where the binding that holds the order
 * has {@code ORDER_REVISION} enabled and usable, this sends the revised total under a key built from the
 * notice, so a repeat is the same send; everywhere else a person corrects the till and says so
 * ({@link #acknowledge}). No real vendor is claimed: every vendor starts unassessed, an unassessed capability
 * cannot be enabled on a binding, and Clopos is recorded as unable (V0665).
 *
 * <p>The vendor call is outside any transaction (ADR 0019): each write the notice takes is its own short
 * transaction in {@link TillRevisionNotices}. The vendor's own words are never stored or returned; a failed
 * try keeps a stable code (ADR 0029).
 */
@Service
public class PosOrderRevisionService {

    /** The reason code the audit trail and the vendor see for a weight-driven revision. */
    static final String REASON_CATCHWEIGHT = "CATCHWEIGHT";

    private final TillRevisionNotices notices;
    private final JdbcPosExportStore exports;
    private final JdbcPosBindingConfiguration configuration;
    private final JdbcPosCapabilityStore capabilities;
    private final PosAdapterRegistry adapters;
    private final Clock clock;

    public PosOrderRevisionService(
            TillRevisionNotices notices,
            JdbcPosExportStore exports,
            JdbcPosBindingConfiguration configuration,
            JdbcPosCapabilityStore capabilities,
            PosAdapterRegistry adapters,
            Clock clock) {
        this.notices = notices;
        this.exports = exports;
        this.configuration = configuration;
        this.capabilities = capabilities;
        this.adapters = adapters;
        this.clock = clock;
    }

    /** What the console shows about the order's notice, and whether the platform can tell the till itself. */
    public record View(@Nullable Notice notice, boolean canTell) {}

    public enum Outcome {
        /** The vendor accepted the revised total; the notice is closed as told. */
        TOLD,
        /** There is no open notice: nothing to tell, or another request already closed it. */
        NO_OPEN_NOTICE,
        /** This binding cannot be told: the capability is not enabled, not usable, or the order has no vendor id yet. */
        NOT_AVAILABLE,
        /** The vendor refused. The notice stays open, with the stable code. */
        REJECTED,
        /** The vendor could not be reached. The notice stays open. */
        RETRYABLE,
        /** The vendor may or may not have applied it. The notice stays open; sending again is the same send. */
        UNCERTAIN,
        /**
         * The vendor took the total the operator saw, but the order was weighed again while it was being asked.
         * The till holds that total now and the notice stays open for the newer one: telling the till again is a
         * new send of the new total, and nothing was closed on the strength of the old one.
         */
        SUPERSEDED
    }

    /**
     * The notice the operator acted on is not the one that is open now: the order was weighed again between
     * their look and their click. Nothing was sent and nothing was closed. The console answers {@code 409
     * STALE_VERSION} so the screen reads the notice again.
     */
    public static final class StaleNoticeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int expectedRevision;
        private final int actualRevision;

        public StaleNoticeException(int expectedRevision, int actualRevision) {
            super("The till revision moved from revision %d to %d".formatted(expectedRevision, actualRevision));
            this.expectedRevision = expectedRevision;
            this.actualRevision = actualRevision;
        }

        public int expectedRevision() {
            return expectedRevision;
        }

        public int actualRevision() {
            return actualRevision;
        }
    }

    public record Result(Outcome outcome, @Nullable String code, View view) {}

    /** The order's latest notice: open, or the one that was just closed, for the console to show what happened. */
    public View view(UUID tenantId, UUID orderId) {
        Optional<Notice> open = notices.openFor(tenantId, orderId);
        Notice notice =
                open.orElseGet(() -> notices.latestFor(tenantId, orderId).orElse(null));
        return new View(
                notice, open.isPresent() && resolveTarget(tenantId, orderId).isPresent());
    }

    /**
     * Sends the revised total of the notice the operator saw to the till, where the binding can take one.
     *
     * @param expectedRevision the revision of the notice the operator looked at; a notice that has moved since
     *     is refused with {@link StaleNoticeException} before anything is sent
     */
    public Result tell(UUID tenantId, UUID orderId, String actor, int expectedRevision) {
        Optional<Notice> open = notices.openFor(tenantId, orderId);
        if (open.isEmpty()) {
            return new Result(Outcome.NO_OPEN_NOTICE, null, view(tenantId, orderId));
        }
        Notice notice = open.get();
        requireRevision(expectedRevision, notice);
        Optional<Target> target = resolveTarget(tenantId, orderId);
        if (target.isEmpty()) {
            return new Result(Outcome.NOT_AVAILABLE, "ORDER_REVISION_NOT_AVAILABLE", view(tenantId, orderId));
        }

        TillRevision revision = new TillRevision(
                notice.tillTotalMinor(),
                notice.revisedTotalMinor(),
                notice.currency(),
                REASON_CATCHWEIGHT,
                "till-revision:%s:%d".formatted(notice.id(), notice.revision()));
        ProviderOutcome outcome = target.get()
                .adapter()
                .reviseExportedOrder(target.get().context(), target.get().externalOrderId(), revision);

        switch (outcome.status()) {
            case SUCCESS -> {
                return told(tenantId, orderId, notice, actor);
            }
            case REJECTED -> {
                String code = codeOf(outcome);
                notices.recordFailedAttempt(tenantId, notice.id(), code, clock.instant());
                return new Result(Outcome.REJECTED, code, view(tenantId, orderId));
            }
            case RETRYABLE -> {
                String code = codeOf(outcome);
                notices.recordFailedAttempt(tenantId, notice.id(), code, clock.instant());
                return new Result(Outcome.RETRYABLE, code, view(tenantId, orderId));
            }
            case UNCERTAIN -> {
                notices.recordFailedAttempt(tenantId, notice.id(), "UNCERTAIN", clock.instant());
                return new Result(Outcome.UNCERTAIN, "UNCERTAIN", view(tenantId, orderId));
            }
        }
        throw new IllegalStateException("Unreachable: every provider outcome status is handled");
    }

    /**
     * The vendor accepted the total of {@code sent}. Closes that notice as it stood when it was read. If it
     * has moved on in the meantime, the vendor still took the old total, which is what the till holds now;
     * the newer notice stays open and carries it.
     */
    private Result told(UUID tenantId, UUID orderId, Notice sent, String actor) {
        if (notices.markTold(tenantId, sent.id(), sent.revision(), actor, clock.instant())) {
            return new Result(Outcome.TOLD, null, view(tenantId, orderId));
        }
        notices.recordTold(tenantId, sent.id(), sent.revision(), sent.revisedTotalMinor(), actor, clock.instant());
        View after = view(tenantId, orderId);
        Notice latest = after.notice();
        if (latest != null && latest.open()) {
            return new Result(Outcome.SUPERSEDED, "NOTICE_MOVED", after);
        }
        // Closed by somebody else while the till was being asked, or the newer total turned out to be the very
        // amount the till took: nothing is left to tell.
        return new Result(
                latest != null && "TOLD".equals(latest.status()) ? Outcome.TOLD : Outcome.NO_OPEN_NOTICE, null, after);
    }

    /**
     * A person says the till now agrees with the notice they saw. False when no notice is open.
     *
     * @param expectedRevision the revision of the notice the person looked at; a notice that has moved since is
     *     refused with {@link StaleNoticeException} and left open, because what they confirmed was another total
     */
    public boolean acknowledge(UUID tenantId, UUID orderId, String actor, int expectedRevision) {
        Optional<Notice> open = notices.openFor(tenantId, orderId);
        if (open.isEmpty()) {
            return false;
        }
        Notice notice = open.get();
        requireRevision(expectedRevision, notice);
        if (notices.markAcknowledged(tenantId, notice.id(), notice.revision(), actor, clock.instant())) {
            return true;
        }
        // Lost a race between the read and the write: either somebody closed it, or it moved on.
        Optional<Notice> moved = notices.openFor(tenantId, orderId);
        if (moved.isPresent()) {
            throw new StaleNoticeException(expectedRevision, moved.get().revision());
        }
        return false;
    }

    private static void requireRevision(int expectedRevision, Notice open) {
        if (open.revision() != expectedRevision) {
            throw new StaleNoticeException(expectedRevision, open.revision());
        }
    }

    // ------------------------------------------------------------------ plumbing

    private record Target(PosAdapter adapter, PosContext context, String externalOrderId) {}

    /**
     * The adapter, context and vendor order id to send through, or empty when this order cannot be told: it
     * was never exported, the vendor has not named its order yet, the binding it was exported through has
     * not enabled {@code ORDER_REVISION}, the capability is not usable on the installation, or no adapter is
     * wired.
     */
    private Optional<Target> resolveTarget(UUID tenantId, UUID orderId) {
        Optional<JdbcPosExportStore.ExportDetail> detail = exports.findDetailByOrder(tenantId, orderId);
        if (detail.isEmpty() || detail.get().externalOrderId() == null) {
            return Optional.empty();
        }
        Optional<JdbcPosExportStore.ExportRow> row =
                exports.find(tenantId, detail.get().exportId());
        if (row.isEmpty()) {
            return Optional.empty();
        }
        UUID bindingId = row.get().bindingId();
        if (!capabilities.enabledCapabilities(tenantId, bindingId).contains(PosCapability.ORDER_REVISION.code())) {
            return Optional.empty();
        }
        Optional<BindingRef> binding = configuration.bindingRef(tenantId, bindingId);
        if (binding.isEmpty()) {
            return Optional.empty();
        }
        Optional<CapabilitySnapshot> snapshot =
                capabilities.readSnapshot(tenantId, binding.get().installationId());
        if (snapshot.isEmpty() || !snapshot.get().usable(PosCapability.ORDER_REVISION)) {
            return Optional.empty();
        }
        Optional<PosAdapter> adapter = adapters.forProvider(binding.get().providerType());
        if (adapter.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> config = configuration.resolve(binding.get()).orElse(Map.of());
        PosContext context = new PosContext(
                tenantId,
                binding.get().installationId(),
                bindingId,
                row.get().externalVenueReference(),
                config,
                "till-revision-" + orderId);
        return Optional.of(new Target(adapter.get(), context, detail.get().externalOrderId()));
    }

    private static String codeOf(ProviderOutcome outcome) {
        String code = outcome.errorCode();
        return code == null || code.isBlank() ? outcome.status().name() : code;
    }
}
