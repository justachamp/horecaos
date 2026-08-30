package uz.horecaos.platform.fulfillment.domain.sourcing;

/** A bag moving, and nothing about the decision that put it in a hand (ADR 0014). */
public enum ShipmentStatus {

    PENDING,
    ASSIGNED,
    PICKUP_PENDING,
    PICKED_UP,
    DELIVERED,
    CANCELLED
}
