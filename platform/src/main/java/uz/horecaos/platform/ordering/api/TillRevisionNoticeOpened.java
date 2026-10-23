package uz.horecaos.platform.ordering.api;

import java.util.UUID;

/**
 * An order the till already holds just came to be worth a different total (ADR 0137, row {@code 4.2c}):
 * published inside the transaction that captured the weight, so a listener in {@code notifications} raises
 * the operator alert in the same transaction and a rolled-back weighing raises nothing.
 *
 * <p>Deliberately not an {@link OrderingEvent}: it is an in-process signal between two modules of this
 * application and not a fact other systems consume, so it has no topic, no schema and no outbox row. It
 * carries money and identifiers only; the public order number is looked up by the alert, never copied here.
 *
 * @param noticeId the notice, whose id is the alert's idempotency subject: one alert per notice
 */
public record TillRevisionNoticeOpened(
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        UUID orderId,
        UUID noticeId,
        String currency,
        long tillTotalMinor,
        long revisedTotalMinor) {}
