package uz.horecaos.platform.payments.infrastructure.payme;

/**
 * Which Payme surface carries the fiscal receipt detail (ADR 0166 Decision 9, provider note question U10).
 *
 * <p>The docs mark {@code items} «required» on the {@code CheckPerformTransaction} page and «optional» on the
 * checkout-POST page, and never say which surface a fiscalised cashbox reads. Only a sandbox transaction, or
 * Payme in writing, can say. Until then the safe reading, the one the provider note itself gives («always send
 * items when the cashbox is fiscalised»), is both, and the detail is built once so the two cannot disagree. The
 * owner narrows this when Payme answers; nothing else changes.
 *
 * <ul>
 * <li>{@link #FORM}: the checkout form's {@code detail} field only. A link or a QR (a GET surface, where {@code
 *     detail} is not documented, question U20) then carries no detail.</li>
 * <li>{@link #CHECK}: the {@code CheckPerformTransaction} result's {@code detail} member only.</li>
 * <li>{@link #BOTH}: both. The default.</li>
 * </ul>
 */
public enum PaymeReceiptSurface {
    FORM,
    CHECK,
    BOTH;

    /** Whether the checkout form carries the detail. */
    public boolean form() {
        return this == FORM || this == BOTH;
    }

    /** Whether {@code CheckPerformTransaction} returns the detail. */
    public boolean check() {
        return this == CHECK || this == BOTH;
    }
}
