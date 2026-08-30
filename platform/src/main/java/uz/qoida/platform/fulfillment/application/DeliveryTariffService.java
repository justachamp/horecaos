package uz.qoida.platform.fulfillment.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.fulfillment.application.ServiceZoneService.DeliveryResourceNotFoundException;
import uz.qoida.platform.fulfillment.domain.VersionStatus;
import uz.qoida.platform.fulfillment.domain.tariff.DeliveryTariff;
import uz.qoida.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore;

/**
 * Authoring rate tables and activating their versions (ADR 0037).
 *
 * <p>The activation gate is where the tiling rule is enforced. Bands must cover
 * {@code [0, max_distance_meters)} with no gap and no overlap: the overlap half is
 * refused by the database's exclusion constraint at insert, and the gap half is
 * checked here, because gap-freeness is a property of the whole set against the
 * version's own maximum and no constraint can see all of it at once.
 *
 * <p>A gap is worth this much machinery because of how it fails. 4,600 m prices,
 * 4,800 m prices, and 4,700 m does not — and nobody discovers it until a customer
 * at that exact distance reports that the app will not let them order.
 */
@Service
public class DeliveryTariffService {

    private final JdbcDeliveryTariffStore store;
    private final Clock clock;

    public DeliveryTariffService(JdbcDeliveryTariffStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Transactional
    public UUID createTariff(UUID tenantId, UUID brandId, String code, String name,
            boolean brandDefault) {
        UUID id = UUID.randomUUID();
        store.insertTariff(id, tenantId, brandId, code, name, brandDefault, clock.instant());
        return id;
    }

    /**
     * Drafts a version.
     *
     * <p>The bands are validated here as well as at activation. Nothing forbids
     * saving a broken draft — an operator half way through entering a rate table
     * has a broken one by definition — but a draft that is already unfixable
     * (negative amounts, an inverted band) is refused at construction by
     * {@code TariffBand}, so the only faults that survive to activation are the
     * ones about the set as a whole.
     */
    @Transactional
    public DraftedVersion draftVersion(UUID tenantId, UUID brandId, DeliveryTariff draft,
            UUID createdBy) {
        requireOwned(tenantId, brandId, draft.tariffId());
        int version = store.nextVersion(tenantId, draft.tariffId());
        DeliveryTariff versioned = new DeliveryTariff(
                draft.tariffId(), version, VersionStatus.DRAFT, draft.currency(),
                draft.feeSource(), draft.distanceMode(), draft.roadFactorBasisPoints(),
                draft.routingProviderInstallationId(), draft.maxDistanceMeters(),
                draft.minFeeMinor(), draft.maxFeeMinor(),
                draft.distanceAccrual(), draft.feeRoundingStepMinor(), draft.feeRoundingRule(),
                draft.bands(), draft.timeRules(), draft.discounts());

        UUID id = UUID.randomUUID();
        store.insertVersion(id, tenantId, versioned, createdBy, clock.instant());
        return new DraftedVersion(id, draft.tariffId(), version);
    }

    /** Activates a draft, or refuses with every tiling and binding problem at once. */
    @Transactional
    public void activate(UUID tenantId, UUID brandId, UUID tariffId, int version, UUID actorId) {
        requireOwned(tenantId, brandId, tariffId);
        DeliveryTariff draft = store.loadVersion(tenantId, tariffId, version)
                .orElseThrow(() -> new DeliveryResourceNotFoundException(
                        "Tariff %s has no version %d".formatted(tariffId, version)));

        if (draft.status() != VersionStatus.DRAFT) {
            throw new TariffActivationRefusedException(
                    List.of("Only a DRAFT version can be activated; this one is " + draft.status()));
        }

        List<String> problems = draft.activationProblems();
        if (!problems.isEmpty()) {
            throw new TariffActivationRefusedException(problems);
        }

        if (store.activateVersion(tenantId, tariffId, version, actorId, clock.instant()) != 1) {
            throw new TariffActivationRefusedException(
                    List.of("This version was activated or withdrawn by someone else"));
        }
    }

    @Transactional
    public void bindLocation(UUID tenantId, UUID brandId, UUID locationId, UUID tariffId) {
        store.bindLocation(tenantId, brandId, locationId, tariffId, clock.instant());
    }

    public record DraftedVersion(UUID id, UUID tariffId, int version) { }

    public static final class TariffActivationRefusedException extends RuntimeException {

        private final List<String> problems;

        public TariffActivationRefusedException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /**
     * Refuses a tariff this brand does not own.
     *
     * <p>These endpoints declare a BRAND-scoped capability, so the caller was
     * authorised for the brand in the URL and not for whichever brand happens to
     * own the tariff id they supplied. Not-found rather than forbidden, so the
     * endpoint cannot be used to discover which tariff ids exist.
     */
    private void requireOwned(UUID tenantId, UUID brandId, UUID tariffId) {
        if (!store.tariffBelongsToBrand(tenantId, brandId, tariffId)) {
            throw new DeliveryResourceNotFoundException(
                    "Tariff %s does not belong to this brand".formatted(tariffId));
        }
    }
}
