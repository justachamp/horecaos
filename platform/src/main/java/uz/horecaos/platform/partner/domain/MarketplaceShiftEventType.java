package uz.horecaos.platform.partner.domain;

/**
 * What an aggregator reported about its own operating shift for a branch
 * (ADR 0040, gap map row {@code 10.9d}, {@code notifications.aggregator_shift_notifications_enabled}).
 *
 * <p>The aggregator's own shift, not the branch's business hours and not an
 * internal courier's {@code CourierShiftService} shift — a marketplace
 * (Yandex Eda, Uzum Tezkor and the like) may itself go live or pause on a
 * venue independently of whether the branch is open, and this is what that
 * ping reports.
 */
public enum MarketplaceShiftEventType {
    OPENED,
    CLOSED
}
