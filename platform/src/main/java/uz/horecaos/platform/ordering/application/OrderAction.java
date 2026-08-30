package uz.horecaos.platform.ordering.application;

import uz.horecaos.platform.ordering.domain.OrderStatus;

/**
 * One action the console may offer on an order right now (orders.md §4.2).
 *
 * @param targetStatus the status {@link OrderActionCode#ADVANCE} would move the
 *                      order to. Null for every other code, which names no
 *                      status of its own — {@code APPROVE} and {@code REJECT}
 *                      both leave {@code AWAITING_APPROVAL} and the response
 *                      already carries the order's current status
 */
public record OrderAction(OrderActionCode code, OrderStatus targetStatus) {}
