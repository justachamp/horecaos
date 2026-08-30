package uz.horecaos.platform.fiscal.domain;

import java.time.Duration;
import java.util.Map;

/**
 * How long a provider is given to report a receipt, before the document becomes
 * somebody's work (ADR 0038, stored under ADR 0030).
 *
 * <p>Stored in whole minutes rather than as a duration, for the reason
 * {@code OrderAcceptancePolicy} gives about seconds: the stored JSON is a
 * contract and must not depend on a serializer's choice of representation.
 *
 * <p>The per-provider map exists because the two providers are not comparable on
 * this axis. Click reports synchronously — a {@code submit_items} response either
 * carries a {@code qrCodeURL} or it does not — so a Click document that is still
 * {@code SUBMITTED} after a few minutes means a response was lost, and the
 * read-back should already have settled it. Payme's report arrives inbound, on
 * Payme's schedule, and an hour is a normal wait rather than a symptom. One
 * number for both would either block Click far too late or Payme far too often.
 *
 * <p>Identity, scope and version are absent on purpose; they belong to
 * {@code ResolvedPolicy}. ADR 0038 asks for this to resolve per legal entity as
 * well as per provider, and legal entity is not one of ADR 0025's scopes — the
 * ADR says so itself when it declines to widen {@code integration.bindings}. So
 * the entity dimension, when it is needed, belongs inside this document as a
 * second map rather than as a fourth {@code ScopeType}, which would change scope
 * precedence for every policy in the platform.
 *
 * @param deadlineMinutes           the default, applied to a provider the map does
 *                                  not name
 * @param deadlineMinutesByProvider overrides keyed by provider type, uppercase
 */
public record FiscalReportingPolicy(int deadlineMinutes, Map<String, Integer> deadlineMinutesByProvider) {

    /** ADR 0038's stated default: sixty minutes. */
    public static final int DEFAULT_MINUTES = 60;

    private static final int MIN_MINUTES = 1;

    /**
     * A week. Not a real policy value — it is the point past which a deadline is
     * indistinguishable from having no deadline, which is the state this whole
     * mechanism exists to leave behind. The business-date backstop makes anything
     * beyond a day inert anyway, so a larger number here is a misconfiguration
     * that would read as a decision.
     */
    private static final int MAX_MINUTES = 7 * 24 * 60;

    public FiscalReportingPolicy {
        deadlineMinutesByProvider =
                deadlineMinutesByProvider == null ? Map.of() : Map.copyOf(deadlineMinutesByProvider);
        requireSane("deadlineMinutes", deadlineMinutes);
        deadlineMinutesByProvider.forEach((provider, minutes) -> requireSane(provider, minutes == null ? 0 : minutes));
    }

    /**
     * The interval for one provider.
     *
     * @param providerType null for a document with no provider, which answers the
     *                     default rather than throwing — a cash document is never
     *                     swept, and a caller should not have to know that here
     */
    public Duration deadlineFor(String providerType) {
        Integer override = providerType == null
                ? null
                : deadlineMinutesByProvider.get(providerType.toUpperCase(java.util.Locale.ROOT));
        return Duration.ofMinutes(override == null ? deadlineMinutes : override);
    }

    /**
     * What applies when no tenant, brand or location policy exists.
     *
     * <p>Sixty minutes for both providers. The ADR accepts in writing that this
     * will produce false positives — a document blocked at sixty minutes whose
     * callback arrives at seventy — and decides the default on the asymmetry: a
     * cleared block costs an operator a glance, an uncleared silence costs a
     * tenant an audit finding.
     */
    public static FiscalReportingPolicy platformDefault() {
        return new FiscalReportingPolicy(DEFAULT_MINUTES, Map.of());
    }

    private static void requireSane(String field, int minutes) {
        if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
            throw new IllegalArgumentException("A reporting deadline must be between %d and %d minutes: %s was %d"
                    .formatted(MIN_MINUTES, MAX_MINUTES, field, minutes));
        }
    }
}
