package uz.qoida.platform.courier.domain;

/** Which of the two delivery-cost paths a line belongs to (ADR 0042). */
public enum CostPath {

    /** What the courier accrued. */
    INTERNAL,

    /** What Noor or Yandex charged. */
    PARTNER
}
