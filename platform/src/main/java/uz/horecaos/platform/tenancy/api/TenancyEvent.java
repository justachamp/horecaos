package uz.horecaos.platform.tenancy.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned business fact emitted by the tenancy module.
 *
 * <p>Sealed so that {@code EventCatalogCompletenessTests} can enumerate what
 * this module is able to publish and insist every one of them has an ADR 0032
 * contract. Adding a permitted subtype without a catalogue entry fails the
 * build, which is the point.
 */
public sealed interface TenancyEvent
        permits TenantCreated,
                BrandCreated,
                LocationCreated,
                TenantOnboardingStarted,
                TenantOnboardingStepCompleted,
                TenantOnboardingFailed,
                TenantReady,
                TenantActivated {

    UUID eventId();

    String eventType();

    int eventVersion();

    TenantId tenantId();

    String aggregateType();

    UUID aggregateId();

    Instant occurredAt();

    Object payload();
}
