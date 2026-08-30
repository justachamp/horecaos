package uz.qoida.platform.courier.domain;

/** What a partner invoice line is charging for (ADR 0042). */
public enum PartnerChargeType {

    DELIVERY,
    CANCELLATION,
    WAITING,
    SURCHARGE,
    ADJUSTMENT
}
