package uz.horecaos.platform.customers.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerPhoneLookup;

/**
 * {@link CustomerPhoneLookup} over {@link CustomerProfileService} (ADR 0015,
 * ADR 0064).
 */
@Service
public class CustomerPhoneLookupAdapter implements CustomerPhoneLookup {

    private final CustomerProfileService profiles;

    public CustomerPhoneLookupAdapter(CustomerProfileService profiles) {
        this.profiles = profiles;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerAccountRef> findByPhone(UUID tenantId, String rawPhoneNumber) {
        return profiles
                .findAccountsByContact(tenantId, CustomerProfileService.ContactType.PHONE, rawPhoneNumber)
                .stream()
                .map(accountId -> new CustomerAccountRef(accountId, tenantId))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CardProfile> cardProfile(UUID tenantId, UUID accountId) {
        return profiles.profile(tenantId, accountId).map(account -> new CardProfile(accountId, account.displayName()));
    }
}
