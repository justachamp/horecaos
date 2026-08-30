package uz.horecaos.platform.payments.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * The payment method codes this build understands, and what each one implies.
 *
 * <p>Code-owned rather than a table, for the reason ADR 0025 gives for the
 * capability registry: an unknown method must fail rather than silently resolve
 * to something. ADR 0038 owns a tenant-scoped {@code payments.payment_methods}
 * registry that will eventually carry per-tenant naming and the fiscal
 * responsibility behind each row; this enum is what the platform can decide
 * without it, and the two are not in conflict — a tenant chooses which of these
 * to offer on a channel, and never invents a new one.
 *
 * <p>{@code tenant.channel_payment_methods} already stores these codes as free
 * text with no foreign key, which is exactly the gap ADR 0038 closes.
 */
public enum PaymentMethod {
    CASH("CASH", PaymentTender.CASH, null, CaptureTiming.ON_HANDOVER),

    CLICK("CLICK", PaymentTender.PROVIDER, PaymentProviderType.CLICK, CaptureTiming.BEFORE_CONFIRMATION),

    PAYME("PAYME", PaymentTender.PROVIDER, PaymentProviderType.PAYME, CaptureTiming.BEFORE_CONFIRMATION),

    /** Designed for; no channel offers it until a bot exists. */
    TELEGRAM("TELEGRAM", PaymentTender.PROVIDER, PaymentProviderType.TELEGRAM, CaptureTiming.BEFORE_CONFIRMATION),

    /**
     * The aggregator collected it (ADR 0040).
     *
     * <p>Not offerable on a HorecaOS channel and never chosen by a customer here:
     * this is the tender of an order that arrived already paid, pushed by a
     * marketplace that took the customer's money before HorecaOS heard of the order.
     * It exists so that such an order has a settlement and therefore tenders, and
     * therefore a refund, a remedy and a reportable figure — without which every
     * remedy on an aggregator order answers "the order has no settlement".
     *
     * <p>{@link CaptureTiming#BEFORE_CONFIRMATION} is the literal truth of it:
     * the money was in before the restaurant was asked. It has no
     * {@link PaymentProviderType} because HorecaOS holds no merchant account behind
     * it — there is nothing to authorize, capture or reconcile against a provider,
     * and the tenant is paid by the aggregator on the aggregator's own cycle. What
     * that means for the fiscal receipt is ADR 0040's open question and is not
     * decided by this enum.
     */
    MARKETPLACE("MARKETPLACE", PaymentTender.PROVIDER, null, CaptureTiming.BEFORE_CONFIRMATION);

    private final String code;
    private final PaymentTender tender;
    private final PaymentProviderType provider;
    private final CaptureTiming captureTiming;

    PaymentMethod(String code, PaymentTender tender, PaymentProviderType provider, CaptureTiming captureTiming) {
        this.code = code;
        this.tender = tender;
        this.provider = provider;
        this.captureTiming = captureTiming;
    }

    public String code() {
        return code;
    }

    public PaymentTender tender() {
        return tender;
    }

    /** Empty for cash, which has no provider and therefore no merchant account. */
    public Optional<PaymentProviderType> provider() {
        return Optional.ofNullable(provider);
    }

    public CaptureTiming captureTiming() {
        return captureTiming;
    }

    /**
     * Resolves a channel's stored code.
     *
     * <p>Empty rather than a default for an unrecognised code. A channel naming a
     * method this build does not implement must stop the checkout at the point of
     * the unknown, not fall through to cash and take an order nobody will be paid
     * for.
     */
    public static Optional<PaymentMethod> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        for (PaymentMethod method : values()) {
            if (method.code.equals(normalized)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }
}
