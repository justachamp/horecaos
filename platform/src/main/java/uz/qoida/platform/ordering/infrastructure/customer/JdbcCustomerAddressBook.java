package uz.qoida.platform.ordering.infrastructure.customer;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import uz.qoida.platform.iam.api.protection.FieldProtection;
import uz.qoida.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.qoida.platform.iam.api.protection.ProtectedValue;
import uz.qoida.platform.ordering.application.CustomerAddressBook;
import uz.qoida.platform.ordering.domain.DeliveryDestination;

/**
 * Reads one of ADR 0015's saved addresses for the cart that is about to copy it
 * (ADR 0019, ADR 0029).
 *
 * <p>Follows {@code JdbcOrderCatalogSnapshot}: an adapter in ordering that reads
 * exactly the columns ordering needs from another module's tables, so that the
 * module boundary is a Java dependency ordering does not have rather than one it
 * declares. The table and column names of {@code customer.addresses} appear here
 * and nowhere else in ordering, because the ADR 0029 associated data is derived
 * from them and a ciphertext read with the wrong {@link RecordRef} does not
 * decrypt — which is the property that makes a row's ciphertext useless anywhere
 * but its own row.
 *
 * <p>Every predicate is in the statement. Tenant, account and {@code ACTIVE}
 * status are three separate reasons an address is not available to this cart, and
 * filtering after the load is one forgotten branch away from decrypting a
 * stranger's home address.
 */
@Component
public class JdbcCustomerAddressBook implements CustomerAddressBook {

    private static final String ADDRESS_TABLE = "customer.addresses";
    private static final String FIELDS_COLUMN = "encrypted_fields";
    private static final String INSTRUCTIONS_COLUMN = "delivery_instructions_encrypted";

    private final JdbcClient jdbc;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;

    public JdbcCustomerAddressBook(JdbcClient jdbc, FieldProtection protection,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.protection = protection;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<SavedDestination> destination(UUID tenantId, UUID customerAccountId,
            UUID addressId, String purpose) {

        if (customerAccountId == null || addressId == null) {
            // A guest cart has no account and therefore no saved addresses. Not an
            // error: ADR 0015's guest claim does not exist yet, so there is
            // nothing for a guest to have saved.
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT id, label, encrypted_fields, delivery_instructions_encrypted,
                       latitude, longitude
                FROM customer.addresses
                WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                  AND id = :addressId AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId).param("accountId", customerAccountId)
                .param("addressId", addressId)
                .query((row, number) -> new AddressRow(
                        row.getObject("id", UUID.class),
                        row.getString("label"),
                        row.getString("encrypted_fields"),
                        row.getString("delivery_instructions_encrypted"),
                        (Double) row.getObject("latitude"),
                        (Double) row.getObject("longitude")))
                .optional()
                .map(row -> reveal(tenantId, row, purpose));
    }

    /**
     * Decrypts the address document and turns it into a destination.
     *
     * <p>The coordinate pair is read as an object rather than a {@code double} on
     * purpose: {@code getDouble} returns 0.0 for SQL NULL, and 0,0 is a real point
     * in the Gulf of Guinea that a courier would be sent to. V0021's constraint
     * already refuses half a pair, so one null here means neither is present and
     * the address is a landmark-only one that cannot be delivered to by point.
     */
    private SavedDestination reveal(UUID tenantId, AddressRow row, String purpose) {
        String document = protection.reveal(tenantId,
                ProtectedValue.deserialize(row.encryptedFields()),
                new RecordRef(ADDRESS_TABLE, FIELDS_COLUMN, row.id()), purpose);
        AddressFields fields = objectMapper.readValue(document, AddressFields.class);

        DeliveryDestination destination = row.latitude() == null || row.longitude() == null
                ? null
                : new DeliveryDestination(fields.line1(), fields.line2(), fields.city(),
                        fields.district(), fields.postalCode(), fields.entrance(), fields.floor(),
                        fields.apartment(), fields.landmark(),
                        row.latitude(), row.longitude());

        String instructions = row.encryptedInstructions() == null ? null
                : protection.reveal(tenantId,
                        ProtectedValue.deserialize(row.encryptedInstructions()),
                        new RecordRef(ADDRESS_TABLE, INSTRUCTIONS_COLUMN, row.id()), purpose);

        return new SavedDestination(row.id(), row.label(), destination, instructions);
    }

    /**
     * The stored shape of {@code customer.addresses.encrypted_fields}.
     *
     * <p>Ordering's own reading of the document rather than an import of ADR
     * 0015's record: the shape is a wire contract between two modules, and a
     * shared class would make every field addition in customers a compile-time
     * change in ordering. Unknown fields are ignored for the same reason.
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record AddressFields(String line1, String line2, String city, String district,
            String postalCode, String entrance, String floor, String apartment, String landmark) { }

    /** Never printed: two of its columns are ciphertext and two are a doorstep. */
    private record AddressRow(UUID id, String label, String encryptedFields,
            String encryptedInstructions, Double latitude, Double longitude) {

        @Override
        public String toString() {
            return "AddressRow[REDACTED]";
        }
    }
}
