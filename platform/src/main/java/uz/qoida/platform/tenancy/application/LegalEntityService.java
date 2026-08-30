package uz.qoida.platform.tenancy.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.tenancy.api.FiscalSeller;
import uz.qoida.platform.tenancy.api.LegalEntityId;
import uz.qoida.platform.tenancy.api.TenantId;
import uz.qoida.platform.tenancy.domain.LegalEntity;
import uz.qoida.platform.tenancy.domain.LocationFiscalAssignment;
import uz.qoida.platform.tenancy.domain.TaxpayerNumber;
import uz.qoida.platform.tenancy.infrastructure.persistence.JdbcLegalEntityStore;

/**
 * Registering the companies inside a tenant, and saying which one a branch sells
 * as (ADR 0038).
 *
 * <p>Tenant CRUD, like the sales-channel registry and for the same reason: a
 * tenant incorporates a second company or transfers a branch to it without
 * waiting for a release. What the tenant cannot do is leave a branch with two
 * companies at once, or with none on a date it traded, and both of those are
 * refused by the database rather than here.
 *
 * <p><strong>Assignment is close-then-open, in one transaction.</strong> The
 * predecessor is given an end date equal to the successor's start, so the two are
 * adjacent and no day belongs to both or to neither. Doing it in two commands
 * would leave a window in which the branch has no taxpayer, and an order accepted
 * inside that window resolves no seller and blocks — a receipt lost to an
 * operator's mid-edit rather than to anything about the business.
 */
@Service
public class LegalEntityService {

    private final JdbcLegalEntityStore store;
    private final Clock clock;

    public LegalEntityService(JdbcLegalEntityStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Registers a company, in {@code DRAFT}.
     *
     * <p>Draft rather than active, because a company that can be named as a seller
     * the instant somebody types its INN is a company that will be named as a
     * seller before finance has checked the INN. Activation is the separate step
     * where that check has a place to sit.
     */
    @Transactional
    public LegalEntity register(UUID tenantId, RegisterLegalEntityCommand command) {
        requireSchema();

        LegalEntity entity = LegalEntity.draft(
                new LegalEntityId(UUID.randomUUID()),
                new TenantId(tenantId),
                command.code(),
                command.legalName(),
                new TaxpayerNumber(command.tin()));
        entity.rename(command.legalName(), command.shortName());
        entity.applyVatRegistration(command.vatRegistered(), command.vatCertificateReference());
        entity.describeRegistration(command.registeredAddress(), command.contactPhone());
        entity.useTaxProfile(command.taxProfileId());

        try {
            store.insert(entity, clock.instant());
        } catch (DataIntegrityViolationException violation) {
            throw JdbcLegalEntityStore.explain(violation);
        }
        return entity;
    }

    @Transactional
    public LegalEntity activate(UUID tenantId, UUID entityId, int expectedVersion) {
        return transition(tenantId, entityId, expectedVersion, LegalEntity::activate);
    }

    /**
     * Suspends a company.
     *
     * <p>Its assignments are untouched. A suspended entity is one that may not be
     * named on a <em>new</em> receipt, and a branch it still holds therefore
     * blocks rather than silently falling through to another company — which is
     * the correct direction, because "which company sells here now" is not a
     * question the platform may answer on a tenant's behalf.
     */
    @Transactional
    public LegalEntity suspend(UUID tenantId, UUID entityId, int expectedVersion) {
        return transition(tenantId, entityId, expectedVersion, LegalEntity::suspend);
    }

    @Transactional
    public LegalEntity archive(UUID tenantId, UUID entityId, int expectedVersion) {
        return transition(tenantId, entityId, expectedVersion, LegalEntity::archive);
    }

    @Transactional(readOnly = true)
    public List<LegalEntity> list(UUID tenantId) {
        return store.listForTenant(tenantId);
    }

    @Transactional(readOnly = true)
    public LegalEntity require(UUID tenantId, UUID entityId) {
        requireSchema();
        return store.find(tenantId, entityId)
                .orElseThrow(() -> new TenantResourceNotFoundException(
                        "No legal entity " + entityId + " for this tenant"));
    }

    // -------------------------------------------------------------- assignment

    /**
     * Makes a company the seller at a branch from a date.
     *
     * <p>The entity must be this tenant's and must be able to sell. Both are
     * checked here <em>and</em> by the composite foreign key, deliberately: the
     * check produces a sentence an operator can act on, and the constraint is what
     * holds when two operators assign the same branch in the same second.
     *
     * <p>An {@code effectiveFrom} in the past is permitted. Backdating is how the
     * first assignment is recorded at all — a branch that has been trading for a
     * year did so as somebody, and refusing to say so would leave every historical
     * receipt resolving no seller. What is refused is backdating <em>over</em> an
     * existing assignment, which the exclusion constraint does.
     */
    @Transactional
    public LocationFiscalAssignment assign(UUID tenantId, AssignLocationCommand command) {
        requireSchema();

        LegalEntity entity = require(tenantId, command.legalEntityId());
        if (!entity.canSell()) {
            throw new TenantResourceConflictException(
                    "Legal entity %s is %s and cannot be named as a seller"
                            .formatted(entity.code(), entity.status()));
        }

        Instant now = clock.instant();

        // Close, then open, in that order and in one transaction. A backdated
        // assignment whose start precedes the open one gives that row an end
        // before its beginning, and the check constraint refuses the whole
        // transaction — which is the right outcome: backdating over a period a
        // different company already sold in is a rewrite of who issued those
        // receipts, and it is a correction somebody has to make deliberately.
        store.closeOpenAssignment(tenantId, command.locationId(), command.effectiveFrom(), now);

        LocationFiscalAssignment assignment = new LocationFiscalAssignment(
                UUID.randomUUID(), tenantId, command.brandId(), command.locationId(),
                command.legalEntityId(), command.effectiveFrom(), null,
                command.approvedBy(), command.approvalReference(), 1);

        try {
            store.insertAssignment(assignment, now);
        } catch (DataIntegrityViolationException violation) {
            throw JdbcLegalEntityStore.explain(violation);
        }
        return assignment;
    }

    @Transactional(readOnly = true)
    public List<LocationFiscalAssignment> assignmentHistory(UUID tenantId, UUID locationId) {
        return store.assignmentHistory(tenantId, locationId);
    }

    /**
     * Who this branch sold as on that date.
     *
     * <p>Delegates rather than reimplements: the resolver is the store's, and
     * there is exactly one of it. See {@code LegalEntityDirectory}.
     */
    @Transactional(readOnly = true)
    public Optional<FiscalSeller> sellerFor(UUID tenantId, UUID locationId, LocalDate businessDate) {
        return store.sellerFor(tenantId, locationId, businessDate);
    }

    private LegalEntity transition(UUID tenantId, UUID entityId, int expectedVersion,
            java.util.function.Consumer<LegalEntity> change) {
        LegalEntity entity = require(tenantId, entityId);
        change.accept(entity);
        if (!store.update(entity, expectedVersion, clock.instant())) {
            throw new TenantResourceConflictException(
                    "Legal entity %s has moved on from version %d"
                            .formatted(entityId, expectedVersion));
        }
        return entity;
    }

    /**
     * ADR 0038's rollout stage 1 is not built, and a write that silently did
     * nothing would be worse than a refusal.
     */
    private void requireSchema() {
        if (!store.isWired()) {
            throw new TenantResourceConflictException(
                    "tenant.legal_entities is not present in this deployment (ADR 0038 rollout "
                            + "stage 1); no legal entity can be registered or assigned");
        }
    }

    /**
     * @param code stable and tenant-unique. A fiscal document and a merchant
     *             binding both point at the row it names and must still resolve a
     *             year later, so an entity is archived rather than deleted and its
     *             code is never reused
     */
    public record RegisterLegalEntityCommand(
            String code,
            String legalName,
            String shortName,
            String tin,
            boolean vatRegistered,
            String vatCertificateReference,
            UUID taxProfileId,
            String registeredAddress,
            String contactPhone) {
    }

    /**
     * @param approvedBy ADR 0027 evidence, required. Which company sells at a
     *                   branch is a decision somebody signed, and the platform is
     *                   not entitled to record it anonymously
     */
    public record AssignLocationCommand(
            UUID brandId,
            UUID locationId,
            UUID legalEntityId,
            LocalDate effectiveFrom,
            String approvedBy,
            String approvalReference) {
    }

    /** Exposed so a console can render what an unbuilt stage 1 means for a tenant. */
    public boolean schemaAvailable() {
        return store.isWired();
    }
}
