package uz.horecaos.platform.loyalty.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A difference between the platform's loyalty ledger and a till's balance that a person has to look at
 * (ADR 0171 Decision 6).
 *
 * <p>An in-process signal, published as a Spring application event after the discrepancy row is written,
 * for the operations alert; <strong>not</strong> a catalogued event and not on a topic (ADR 0171, "Commands
 * and events"). It carries ids and a kind and nothing else: no phone, no name, no balance of a person.
 *
 * @param kind {@code TILL_HIGHER}, {@code TILL_LOWER}, {@code TILL_ONLY}, {@code PLATFORM_ONLY},
 *             {@code AMBIGUOUS_MATCH}, {@code SPEND_EXCEEDS_BALANCE}, {@code CREDIT_HELD},
 *             {@code RATIO_MISMATCH}, {@code NOT_CONSENTED} or {@code UNVERIFIABLE}
 */
public record LoyaltySyncDiscrepancyOpened(
        UUID tenantId,
        UUID brandId,
        UUID bindingId,
        UUID discrepancyId,
        @Nullable UUID accountId,
        String kind) {}
