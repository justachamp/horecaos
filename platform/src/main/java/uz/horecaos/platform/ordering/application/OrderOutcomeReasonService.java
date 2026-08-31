package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.domain.CustomerRefund;
import uz.horecaos.platform.ordering.domain.LiabilityParty;
import uz.horecaos.platform.ordering.domain.OutcomeReasonKind;
import uz.horecaos.platform.ordering.domain.OutcomeSystemCategory;
import uz.horecaos.platform.ordering.domain.StockDisposition;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOutcomeReasonStore.ReasonRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * Authoring the tenant's cancellation and completion reasons (ADR 0039).
 *
 * <p>Two rules run through it.
 *
 * <p>A reason is authored in every locale at once. Saving one language at a time
 * would make a half-translated reason a legitimate intermediate state, and
 * intermediate states are what get used by accident — after which a customer
 * reading Uzbek is told nothing, or is told the operator's shorthand.
 *
 * <p>The consequence fields belong to the reason, not to the cancel dialog. ADR
 * 0039 refuses an operator checkbox by name: under pressure an operator picks
 * whatever closes the dialog fastest, and the write-off rate becomes noise
 * instead of a number the kitchen can act on.
 */
@Service
public class OrderOutcomeReasonService {

    private final JdbcOutcomeReasonStore reasons;
    private final Clock clock;

    public OrderOutcomeReasonService(JdbcOutcomeReasonStore reasons, Clock clock) {
        this.reasons = reasons;
        this.clock = clock;
    }

    /** The locales every reason must be written in before it can be used. */
    public static final Set<String> REQUIRED_LOCALES = Set.of("ru", "uz-Latn", "en");

    @Transactional
    public UUID create(UUID tenantId, CreateReason command) {
        validate(command);

        UUID reasonId = UUID.randomUUID();
        Instant now = clock.instant();

        // validate() below already refused a CANCELLATION reason with any of the
        // three null, or a COMPLETION reason with modes absent; NullAway cannot
        // see that cross-field invariant.
        reasons.insert(new JdbcOutcomeReasonStore.NewReason(
                reasonId,
                tenantId,
                command.kind(),
                command.systemCategory().name(),
                command.internalName().strip(),
                command.kind() == OutcomeReasonKind.CANCELLATION
                        ? requireCancellationField(command.stockDisposition()).name()
                        : null,
                command.kind() == OutcomeReasonKind.CANCELLATION
                        ? requireCancellationField(command.liabilityParty()).name()
                        : null,
                command.kind() == OutcomeReasonKind.CANCELLATION
                        ? requireCancellationField(command.customerRefund()).name()
                        : null,
                command.kind() == OutcomeReasonKind.COMPLETION
                        ? requireCompletionModes(command.allowedFulfillmentModes()).stream()
                                .map(Enum::name)
                                .toList()
                        : null,
                now));

        reasons.replaceTexts(reasonId, command.customerTexts());
        return reasonId;
    }

    @Transactional
    public int update(UUID tenantId, UUID reasonId, int expectedVersion, CreateReason command) {
        ReasonRow existing = reasons.find(tenantId, reasonId).orElseThrow(() -> new ReasonNotFoundException(reasonId));
        if (existing.kind() != command.kind()) {
            // A cancellation reason cannot become a completion reason. Every
            // outcome already recorded under it cited a kind, and changing it
            // would move historical rows between two funnels.
            throw new IllegalArgumentException("A reason's kind is fixed at creation; archive it and author a new one");
        }
        validate(command);

        // validate() above already refused a CANCELLATION reason with any of the
        // three null, or a COMPLETION reason with modes absent; NullAway cannot
        // see that cross-field invariant.
        int version = reasons.update(
                        tenantId,
                        reasonId,
                        expectedVersion,
                        command.internalName().strip(),
                        command.kind() == OutcomeReasonKind.CANCELLATION
                                ? requireCancellationField(command.stockDisposition())
                                        .name()
                                : null,
                        command.kind() == OutcomeReasonKind.CANCELLATION
                                ? requireCancellationField(command.liabilityParty())
                                        .name()
                                : null,
                        command.kind() == OutcomeReasonKind.CANCELLATION
                                ? requireCancellationField(command.customerRefund())
                                        .name()
                                : null,
                        command.kind() == OutcomeReasonKind.COMPLETION
                                ? requireCompletionModes(command.allowedFulfillmentModes()).stream()
                                        .map(Enum::name)
                                        .toList()
                                : null,
                        clock.instant())
                .orElseThrow(() -> new StaleReasonException(expectedVersion, existing.version()));

        reasons.replaceTexts(reasonId, command.customerTexts());
        return version;
    }

    @Transactional
    public void archive(UUID tenantId, UUID reasonId, int expectedVersion) {
        if (!reasons.archive(tenantId, reasonId, expectedVersion, clock.instant())) {
            ReasonRow existing =
                    reasons.find(tenantId, reasonId).orElseThrow(() -> new ReasonNotFoundException(reasonId));
            throw new StaleReasonException(expectedVersion, existing.version());
        }
    }

    public List<ReasonRow> list(UUID tenantId, OutcomeReasonKind kind, boolean activeOnly) {
        return reasons.list(tenantId, kind, activeOnly);
    }

    public Optional<ReasonRow> find(UUID tenantId, UUID reasonId) {
        return reasons.find(tenantId, reasonId);
    }

    public Map<String, String> texts(UUID reasonId) {
        return reasons.texts(reasonId);
    }

    /**
     * The reason as it read at this moment, for the outcome snapshot.
     *
     * <p>Deliberately not the JSON of the live row read later. Renaming a reason
     * next year must not rewrite last year's funnel, and the only way that stays
     * true is to copy the whole row at the moment it is cited.
     */
    public Map<String, Object> snapshotOf(ReasonRow reason) {
        return Map.of(
                "reasonId", reason.id().toString(),
                "version", reason.version(),
                "kind", reason.kind().name(),
                "systemCategory", reason.systemCategory(),
                "internalName", reason.internalName(),
                "stockDisposition", String.valueOf(reason.stockDisposition()),
                "liabilityParty", String.valueOf(reason.liabilityParty()),
                "customerRefund", String.valueOf(reason.customerRefund()),
                "allowedFulfillmentModes",
                        reason.allowedFulfillmentModes() == null ? List.of() : reason.allowedFulfillmentModes());
    }

    private void validate(CreateReason command) {
        if (command.internalName() == null || command.internalName().isBlank()) {
            throw new IllegalArgumentException("A reason needs an internal name");
        }
        if (!command.systemCategory().availableFor(command.kind())) {
            throw new IllegalArgumentException(
                    "%s is not a category a %s reason can carry".formatted(command.systemCategory(), command.kind()));
        }
        // The two texts are genuinely different statements. Publishing the
        // internal name to a customer is what the split prevents, and it can only
        // prevent it if the customer wording actually exists.
        Set<String> provided = command.customerTexts().keySet();
        if (!provided.containsAll(REQUIRED_LOCALES)) {
            throw new IllegalArgumentException("A reason needs customer wording in ru, uz-Latn and en; missing "
                    + REQUIRED_LOCALES.stream()
                            .filter(locale -> !provided.contains(locale))
                            .collect(Collectors.joining(", ")));
        }
        if (command.kind() == OutcomeReasonKind.CANCELLATION) {
            if (command.stockDisposition() == null
                    || command.liabilityParty() == null
                    || command.customerRefund() == null) {
                throw new IllegalArgumentException(
                        "A cancellation reason decides the stock disposition, the liable party "
                                + "and the refund posture; none of the three has a safe default");
            }
            if (command.allowedFulfillmentModes() != null
                    && !command.allowedFulfillmentModes().isEmpty()) {
                throw new IllegalArgumentException(
                        "Fulfilment modes belong to a completion reason, not a cancellation");
            }
        } else {
            if (command.allowedFulfillmentModes() == null
                    || command.allowedFulfillmentModes().isEmpty()) {
                // Without this, «Самовывоз выполнен» lands on a delivery order and
                // both the courier SLA report and the external-logistics
                // settlement quietly lose that order.
                throw new IllegalArgumentException("A completion reason names the fulfilment modes it is valid for");
            }
            if (command.stockDisposition() != null
                    || command.liabilityParty() != null
                    || command.customerRefund() != null) {
                throw new IllegalArgumentException("A completed order moves no stock and costs nobody anything");
            }
        }
    }

    /**
     * Restates, for the type system, what {@link #validate} already enforced: a
     * cancellation reason never reaches {@code create}/{@code update} with this
     * field null.
     */
    private static <T> T requireCancellationField(@Nullable T value) {
        return Objects.requireNonNull(value, "validate() already required this field for a cancellation reason");
    }

    /** The completion counterpart of {@link #requireCancellationField}. */
    private static List<FulfillmentMode> requireCompletionModes(@Nullable List<FulfillmentMode> modes) {
        return Objects.requireNonNull(modes, "validate() already required modes for a completion reason");
    }

    /**
     * A reason to author or update, in every required locale at once.
     *
     * @param customerTexts what the customer is told, per locale. A different
     *                      statement from {@code internalName}: «Не дозвонились»
     *                      is what the operator needs in the list, and the
     *                      customer gets the softened wording the tenant wrote
     */
    public record CreateReason(
            OutcomeReasonKind kind,
            OutcomeSystemCategory systemCategory,
            String internalName,
            @Nullable StockDisposition stockDisposition,
            @Nullable LiabilityParty liabilityParty,
            @Nullable CustomerRefund customerRefund,
            @Nullable List<FulfillmentMode> allowedFulfillmentModes,
            Map<String, String> customerTexts) {}

    public static class ReasonNotFoundException extends RuntimeException {
        public ReasonNotFoundException(UUID reasonId) {
            super("No outcome reason " + reasonId + " for this tenant");
        }
    }

    /** The caller's expected version no longer matches the stored reason. */
    public static class StaleReasonException extends RuntimeException {

        private final int expected;
        private final int actual;

        public StaleReasonException(int expected, int actual) {
            super("The reason has changed since version %d was read".formatted(expected));
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }
}
