package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressFields;
import uz.horecaos.platform.customers.application.CustomerProfileService.AddressNotFoundException;
import uz.horecaos.platform.customers.application.CustomerProfileService.CoordinateSource;
import uz.horecaos.platform.customers.application.CustomerProfileService.MaskedAddress;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * The delivery address of a phone order, for an operator who may not read a customer's addresses
 * (ADR 0177, rows {@code 1.3} and {@code 1.3b}).
 *
 * <p>Three acts on the customer the operator has already resolved, and nothing else: the masked
 * list of that customer's saved addresses, adding one with its pin, and setting the pin of a saved
 * one. They are the whole of {@link Capability#CUSTOMER_ADDRESS_CAPTURE}. Each is the same write
 * {@link CustomerProfileService} already performs for the tenant-wide Customers screen, reached
 * through a surface a {@code LOCATION} grant covers; this class adds the three things that surface
 * needs and the tenant-scoped one does not: the account is checked against the path's brand, the
 * source of a point is restricted to what a person at a console can honestly claim, and every call
 * is an audit fact.
 *
 * <p>The audit facts carry counts, a source and a version, never a label, a street or a
 * coordinate: the evidence that an operator looked, and at whose record, is the account id.
 */
@Service
public class OperatorAddressCaptureService {

    /**
     * What an operator at a console can claim about a point: she placed it, or she did not have
     * one to place. A geocoder's answer is a suggestion a person confirmed by looking at it (ADR
     * 0145 decision 5), a customer's pin is the customer's, and a migrated point is a migration's.
     */
    private static final Set<CoordinateSource> OPERATOR_SOURCES =
            EnumSet.of(CoordinateSource.OPERATOR_PIN, CoordinateSource.NOT_GEOCODED, CoordinateSource.LANDMARK_ONLY);

    private final CustomerProfileService profiles;
    private final JdbcCustomerStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    public OperatorAddressCaptureService(
            CustomerProfileService profiles, JdbcCustomerStore store, AuditRecorder audit, Clock clock) {
        this.profiles = profiles;
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /** The customer is not one this brand may serve: the same answer as "no such customer". */
    public static class CustomerNotVisibleException extends RuntimeException {
        public CustomerNotVisibleException() {
            super("No such customer");
        }
    }

    /**
     * The masked saved addresses of one customer.
     *
     * <p>The audit fact is written first, in the same transaction, so a failure while decrypting
     * cannot leave a read with no record of it; it is written when the customer has none too.
     */
    @Transactional
    public List<MaskedAddress> list(UUID tenantId, UUID brandId, UUID locationId, UUID accountId, ActorRef actor) {
        requireVisible(tenantId, brandId, accountId);
        List<MaskedAddress> addresses = profiles.maskedAddresses(tenantId, accountId);
        audit.record(AuditFact.of("customer.address.listed_masked", AuditClass.SECURITY)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("customer_account", accountId)
                .because("Operator read the masked saved addresses of the customer on the phone (ADR 0177)")
                .usingCapability(Capability.CUSTOMER_ADDRESS_CAPTURE.code())
                .changed(ChangeDocuments.created(Map.of("listedCount", addresses.size())))
                .correlatedBy(accountId.toString())
                .occurredAt(clock.instant())
                .build());
        return addresses;
    }

    /**
     * Saves one address for the customer, with its pin when the operator placed one.
     *
     * @throws IllegalArgumentException when the source is not one an operator can claim, or
     *     disagrees with the coordinates (the message names the mistake and never an address)
     */
    @Transactional
    public UUID add(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID accountId,
            String label,
            AddressFields fields,
            @Nullable String deliveryInstructions,
            @Nullable Double latitude,
            @Nullable Double longitude,
            CoordinateSource coordinateSource,
            ActorRef actor) {
        requireVisible(tenantId, brandId, accountId);
        if (coordinateSource == null || !OPERATOR_SOURCES.contains(coordinateSource)) {
            throw new IllegalArgumentException("An address given over the phone is saved as OPERATOR_PIN with a point, "
                    + "or as NOT_GEOCODED or LANDMARK_ONLY without one; a geocoder's answer, a customer's pin and a "
                    + "migrated point are not what an operator records");
        }
        UUID addressId = profiles.addAddress(
                tenantId, accountId, label, fields, deliveryInstructions, latitude, longitude, coordinateSource);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("pointSource", coordinateSource.name());
        after.put("hasPoint", latitude != null);
        audit.record(AuditFact.of("customer.address.captured", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("customer_account", accountId)
                .because("Operator saved a delivery address for the customer on the phone (ADR 0177)")
                .usingCapability(Capability.CUSTOMER_ADDRESS_CAPTURE.code())
                .changed(ChangeDocuments.created(after))
                .correlatedBy(accountId.toString())
                .occurredAt(clock.instant())
                .build());
        return addressId;
    }

    /**
     * Places or moves the pin of one saved address and returns the address as masked.
     *
     * @throws AddressNotFoundException when the address is not this customer's active address
     * @throws CustomerProfileService.StaleRecordException when it has moved on from {@code expectedVersion}
     */
    @Transactional
    public MaskedAddress setPin(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID accountId,
            UUID addressId,
            int expectedVersion,
            double latitude,
            double longitude,
            ActorRef actor) {
        requireVisible(tenantId, brandId, accountId);
        MaskedAddress before =
                profiles.maskedAddress(tenantId, accountId, addressId).orElseThrow(AddressNotFoundException::new);
        int version = profiles.setAddressPoint(tenantId, accountId, addressId, expectedVersion, latitude, longitude);

        Map<String, Object> was = new LinkedHashMap<>();
        was.put("pointSource", before.coordinateSource().name());
        was.put("hasPoint", before.latitude() != null);
        was.put("version", before.version());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("pointSource", CoordinateSource.OPERATOR_PIN.name());
        now.put("hasPoint", true);
        now.put("version", version);
        audit.record(AuditFact.of("customer.address.pin_set", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("customer_account", accountId)
                .because("Operator placed the pin of a saved delivery address on the phone (ADR 0177)")
                .usingCapability(Capability.CUSTOMER_ADDRESS_CAPTURE.code())
                .changed(ChangeDocuments.diff(was, now))
                .correlatedBy(accountId.toString())
                .occurredAt(clock.instant())
                .build());
        return new MaskedAddress(
                before.id(),
                before.label(),
                before.street(),
                before.city(),
                before.district(),
                latitude,
                longitude,
                CoordinateSource.OPERATOR_PIN,
                version);
    }

    private void requireVisible(UUID tenantId, UUID brandId, UUID accountId) {
        if (!store.accountVisibleToBrand(tenantId, brandId, accountId)) {
            throw new CustomerNotVisibleException();
        }
    }
}
