package uz.horecaos.platform.customers.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerImportDirectory;
import uz.horecaos.platform.customers.application.CustomerProfileService.ContactType;

/**
 * {@link CustomerImportDirectory}'s implementation — three already-existing
 * services composed for one new caller, adding no persistence of its own
 * beyond the one method that could not be built from what existed (ADR 0059
 * stage 3).
 */
@Service
public class CustomerImportDirectoryService implements CustomerImportDirectory {

    private final CustomerIdentityService identity;
    private final CustomerProfileService profiles;
    private final ConsentService consent;

    public CustomerImportDirectoryService(
            CustomerIdentityService identity, CustomerProfileService profiles, ConsentService consent) {
        this.identity = identity;
        this.profiles = profiles;
        this.consent = consent;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> accountsWithPhone(UUID tenantId, String rawPhone) {
        return profiles.findAccountsByContact(tenantId, ContactType.PHONE, rawPhone);
    }

    @Override
    @Transactional
    public CustomerAccountRef createAccountWithoutPrincipal(UUID tenantId, UUID brandId) {
        return identity.createAccountWithoutPrincipal(tenantId, brandId);
    }

    @Override
    @Transactional
    public UUID attachPhoneContact(UUID tenantId, UUID accountId, String rawPhone) {
        // Always primary: the only caller of this method is the import path,
        // for an account it just created and that therefore holds no other
        // contact yet — see CustomerImportDirectory#attachPhoneContact.
        return profiles.addContactPoint(tenantId, accountId, ContactType.PHONE, rawPhone, true);
    }

    @Override
    @Transactional
    public UUID record(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String purpose,
            String channel,
            boolean granted,
            String policyVersion,
            String evidenceReference,
            Instant decidedAt) {
        return consent.record(
                tenantId,
                accountId,
                brandId,
                purpose,
                channel,
                granted ? ConsentService.Decision.GRANTED : ConsentService.Decision.WITHDRAWN,
                policyVersion,
                ConsentService.Source.IMPORT,
                evidenceReference,
                decidedAt);
    }
}
