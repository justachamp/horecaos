package uz.horecaos.platform.catalog.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A location offering became {@code AVAILABLE} — created that way, or set to
 * it (gap-map rows 4.1/4.4c/4.4d's pilot-critical follow-up).
 *
 * <p>Published by {@code CatalogAuthoringService} wherever it writes {@code
 * catalog.location_offerings} with status {@code AVAILABLE}: both {@code
 * setOffering} overloads, {@code offerIfAbsent} (the ADR 0099 sample-menu
 * installer's create-only sibling, and any future CSV/xlsx import row that
 * starts calling it — every offering write funnels through one of these
 * three), and {@code bulkSetOfferingStatus}. A variant with no {@code
 * inventory.stock_items} row reads as {@code NOT_STOCKED} regardless of
 * {@code catalog.use_stock_logic} — {@code InventoryService#evaluateAvailability}
 * says so deliberately — so an offering that is published, priced and set
 * {@code AVAILABLE} still cannot be sold until something lists it. Before this
 * event the only lister was the ADR 0099 sample-menu installer, which calls
 * {@code inventory.api.StockListingPort} directly because {@code ordering}
 * already depends on both {@code catalog.api} and {@code inventory.api};
 * ordinary authoring had no such caller.
 *
 * <p><strong>Not an ADR 0032 governed event</strong>, for the same reason
 * {@code loyalty.api.LoyaltyBalanceChanged} gives for its own case: it never
 * reaches Kafka, carries no {@code eventVersion}, and is not in the event
 * catalogue. Its one in-process consumer, {@code
 * inventory.application.CatalogOfferingListingTrigger}, runs moments after
 * the publishing transaction commits, in the same deployable — a much
 * narrower gap than the outbox exists to close, and re-listing (this event's
 * own consumer's job) is itself idempotent, so a redelivery is not even a
 * concern the way it is for a durable topic.
 *
 * <p><strong>Why {@code catalog.api}, not {@code inventory.api}</strong>: a
 * direct {@code catalog -> inventory} call closes a module cycle —
 * {@code inventory} already depends on {@code catalog.api} (through {@code
 * InventoryMenuAvailabilityLookup implements MenuAvailabilityLookup}),
 * proven failing by {@code ModularArchitectureTests} in batch 12. An event
 * {@code catalog} publishes and {@code inventory} listens for adds no new
 * edge in that forbidden direction: {@code inventory} depending on one more
 * class inside {@code catalog.api} is the same edge it already has.
 *
 * <p><strong>{@code AFTER_COMMIT}, not {@code BEFORE_COMMIT}</strong> on its
 * one listener: listing is a best-effort consequence of an authoring decision
 * that has already happened, not something the business wants atomic with it
 * — a bug on the inventory side (a lock timeout, an unexpected constraint)
 * must never roll back a product edit that otherwise succeeded. The gap this
 * leaves — a crash between catalog's commit and the listener running — has
 * the same answer a pre-existing tenant's backlog does: the operations
 * backfill endpoint and the product editor's own "not listed at N branches"
 * action (gap-map row 4.1's own follow-up).
 */
public record OfferingBecameAvailable(
        UUID tenantId, UUID brandId, UUID locationId, UUID variantId, Instant occurredAt) {

    public OfferingBecameAvailable {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(brandId, "Brand ID is required");
        Objects.requireNonNull(locationId, "Location ID is required");
        Objects.requireNonNull(variantId, "Variant ID is required");
        Objects.requireNonNull(occurredAt, "Occurrence time is required");
    }
}
