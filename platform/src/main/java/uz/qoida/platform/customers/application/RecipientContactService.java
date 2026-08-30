package uz.qoida.platform.customers.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.customers.api.RecipientContactDirectory;
import uz.qoida.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.qoida.platform.customers.infrastructure.persistence.JdbcCustomerStore.ContactPointRow;
import uz.qoida.platform.iam.api.protection.FieldProtection;
import uz.qoida.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.qoida.platform.iam.api.protection.ProtectedValue;

/**
 * ADR 0015's answer to ADR 0020's {@code ResolveRecipientValue}.
 *
 * <p>Everything personal stays on this side of the port. Notifications receives a
 * contact-point id and a lookup hash from {@link #primaryContact}, stores those,
 * and calls {@link #resolveValue} once per send. Nothing it holds decrypts to a
 * phone number, which is the whole reason ADR 0020 refused a second ciphertext
 * copy in the notifications schema.
 */
@Service
public class RecipientContactService implements RecipientContactDirectory {

    private static final String CONTACT_TABLE = "customer.contact_points";

    private final JdbcCustomerStore store;
    private final FieldProtection protection;

    public RecipientContactService(JdbcCustomerStore store, FieldProtection protection) {
        this.store = store;
        this.protection = protection;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ContactEndpoint> primaryContact(UUID tenantId, UUID accountId,
            ContactMethod method) {

        // The store already orders primary first, then oldest, so the first match
        // is the answer. Ordering in SQL rather than here keeps "which number do
        // we text?" a single, stable rule instead of one per caller.
        return store.contactPoints(tenantId, accountId).stream()
                .filter(row -> row.type().equals(method.name()))
                .findFirst()
                .map(row -> new ContactEndpoint(row.id(), method, row.normalizedHash(),
                        row.verificationStatus()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolveValue(UUID tenantId, UUID contactPointId, String purpose) {
        return store.contactPoint(tenantId, contactPointId)
                .map(row -> reveal(tenantId, row, purpose));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> preferredLocale(UUID tenantId, UUID accountId) {
        return store.preferredLocale(tenantId, accountId)
                .filter(locale -> !locale.isBlank());
    }

    private String reveal(UUID tenantId, ContactPointRow row, String purpose) {
        // The record reference is rebuilt from the row rather than trusted from
        // the caller. It is bound into the AEAD associated data, so a ciphertext
        // that was copied from another row or another tenant fails here instead of
        // quietly addressing the message to the wrong person.
        return protection.reveal(tenantId, ProtectedValue.deserialize(row.encryptedValue()),
                new RecordRef(CONTACT_TABLE, "encrypted_value", row.id()), purpose);
    }
}
