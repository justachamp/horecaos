package uz.horecaos.platform.tenancy.domain;

import java.util.Objects;
import java.util.UUID;

import uz.horecaos.platform.tenancy.api.LegalEntityId;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * A company inside a tenant that sells in its own name (ADR 0038).
 *
 * <p>ADR 0002 models {@code Tenant -> Brand -> Location} with no company
 * anywhere: {@code tenant.tenants} carries a {@code legal_name} column and
 * nothing else, and {@code tenant.locations} carries no tax identity at all. That
 * is a model of one taxpayer per tenant, and this market is not that — the
 * competitor holds the fiscalization INN at branch granularity precisely because
 * one operator routinely splits branches across companies.
 *
 * <p>The entity is not the brand and cannot be folded into it. A brand is a trade
 * name; a legal entity is who signs the tax return. One company runs three
 * brands, and one brand is split across two companies for franchise reasons, so
 * neither contains the other.
 *
 * <p>Mutable in the same narrow way {@link Location} is, and for the same reason:
 * the platform's write path is a service holding an aggregate under an expected
 * version, not a record replaced wholesale. What is deliberately <em>not</em>
 * mutable is {@link #tin()}. A re-registration is a new taxpayer, and rewriting
 * the number in place would silently restate which company issued every receipt
 * this entity ever sold under — the exact rewrite the effective-dated assignment
 * exists to prevent.
 */
public final class LegalEntity {

    private final LegalEntityId id;
    private final TenantId tenantId;
    private final String code;
    private final TaxpayerNumber tin;
    private String legalName;
    private String shortName;
    private boolean vatRegistered;
    private String vatCertificateReference;
    private UUID taxProfileId;
    private String registeredAddress;
    private String contactPhone;
    private OperatingUnitStatus status;
    private int version;

    private LegalEntity(LegalEntityId id, TenantId tenantId, String code, String legalName,
            String shortName, TaxpayerNumber tin, boolean vatRegistered,
            String vatCertificateReference, UUID taxProfileId, String registeredAddress,
            String contactPhone, OperatingUnitStatus status, int version) {
        this.id = Objects.requireNonNull(id, "Legal entity ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        this.code = Brand.normalizedCode(code);
        this.legalName = Brand.normalizedName(legalName);
        this.shortName = shortName == null || shortName.isBlank() ? null : shortName.strip();
        this.tin = Objects.requireNonNull(tin, "A taxpayer number is required");
        this.status = Objects.requireNonNull(status, "Legal entity status is required");
        this.taxProfileId = taxProfileId;
        this.registeredAddress = registeredAddress;
        this.contactPhone = contactPhone;
        this.version = version;
        applyVatRegistration(vatRegistered, vatCertificateReference);
    }

    public static LegalEntity draft(LegalEntityId id, TenantId tenantId, String code,
            String legalName, TaxpayerNumber tin) {
        return new LegalEntity(id, tenantId, code, legalName, null, tin, false, null, null, null,
                null, OperatingUnitStatus.DRAFT, 1);
    }

    public static LegalEntity reconstitute(LegalEntityId id, TenantId tenantId, String code,
            String legalName, String shortName, TaxpayerNumber tin, boolean vatRegistered,
            String vatCertificateReference, UUID taxProfileId, String registeredAddress,
            String contactPhone, OperatingUnitStatus status, int version) {
        return new LegalEntity(id, tenantId, code, legalName, shortName, tin, vatRegistered,
                vatCertificateReference, taxProfileId, registeredAddress, contactPhone, status,
                version);
    }

    /**
     * Records VAT registration and the certificate that evidences it.
     *
     * <p>An unregistered entity may not hold a certificate reference. The pair is
     * validated together rather than as two independent fields because the failure
     * they guard against is one field being updated and not the other: an entity
     * that deregisters and keeps its certificate reads to a later auditor as
     * though it were still registered, and every receipt it issues charges VAT it
     * does not owe.
     */
    public void applyVatRegistration(boolean registered, String certificateReference) {
        String reference = certificateReference == null || certificateReference.isBlank()
                ? null
                : certificateReference.strip();
        if (!registered && reference != null) {
            throw new IllegalArgumentException(
                    "An entity that is not VAT-registered cannot hold a VAT certificate reference");
        }
        this.vatRegistered = registered;
        this.vatCertificateReference = reference;
    }

    /**
     * Names the ADR 0018 tax profile this entity's sales are rated with.
     *
     * <p>Nullable, and null is not "no tax": it means resolution falls through to
     * brand and tenant exactly as ADR 0018 already does. The entity step exists
     * because VAT registration belongs to the company, and a tenant with one
     * registered and one unregistered company cannot be expressed by brand scope.
     */
    public void useTaxProfile(UUID profileId) {
        this.taxProfileId = profileId;
    }

    public void describeRegistration(String address, String phone) {
        this.registeredAddress = address == null || address.isBlank() ? null : address.strip();
        this.contactPhone = phone == null || phone.isBlank() ? null : phone.strip();
    }

    public void rename(String newLegalName, String newShortName) {
        this.legalName = Brand.normalizedName(newLegalName);
        this.shortName = newShortName == null || newShortName.isBlank() ? null : newShortName.strip();
    }

    public void activate() {
        requireStatus(OperatingUnitStatus.DRAFT, OperatingUnitStatus.SUSPENDED);
        status = OperatingUnitStatus.ACTIVE;
    }

    public void suspend() {
        requireStatus(OperatingUnitStatus.ACTIVE);
        status = OperatingUnitStatus.SUSPENDED;
    }

    /**
     * Ends the entity's life on the platform.
     *
     * <p>Permitted from {@code DRAFT} and {@code SUSPENDED} only, like a location,
     * and the row survives archiving. Every fiscal document ever issued points at
     * this entity and must still resolve a name and an INN years later — a deleted
     * company makes a past receipt unexplainable, which is the one thing tax
     * evidence may not be.
     */
    public void archive() {
        requireStatus(OperatingUnitStatus.DRAFT, OperatingUnitStatus.SUSPENDED);
        status = OperatingUnitStatus.ARCHIVED;
    }

    /** Whether this entity may be named as the seller on a new receipt. */
    public boolean canSell() {
        return status == OperatingUnitStatus.ACTIVE;
    }

    public LegalEntityId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String code() {
        return code;
    }

    public String legalName() {
        return legalName;
    }

    public String shortName() {
        return shortName;
    }

    public TaxpayerNumber tin() {
        return tin;
    }

    public boolean vatRegistered() {
        return vatRegistered;
    }

    public String vatCertificateReference() {
        return vatCertificateReference;
    }

    public UUID taxProfileId() {
        return taxProfileId;
    }

    public String registeredAddress() {
        return registeredAddress;
    }

    public String contactPhone() {
        return contactPhone;
    }

    public OperatingUnitStatus status() {
        return status;
    }

    public int version() {
        return version;
    }

    private void requireStatus(OperatingUnitStatus... allowed) {
        for (OperatingUnitStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Legal entity cannot transition from " + status);
    }
}
