package uz.horecaos.platform.customers.application;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.api.CustomerTelegramSignIn;
import uz.horecaos.platform.customers.application.CustomerVerificationService.Redemption;

/**
 * {@link CustomerTelegramSignIn}'s implementation (ADR 0063) — two already-existing
 * services composed for one new caller, the same shape
 * {@code CustomerImportDirectoryService} already gives the SendPulse import.
 */
@Service
public class CustomerTelegramSignInService implements CustomerTelegramSignIn {

    private final CustomerVerificationService verification;
    private final CustomerSessionService sessions;

    public CustomerTelegramSignInService(CustomerVerificationService verification, CustomerSessionService sessions) {
        this.verification = verification;
        this.sessions = sessions;
    }

    @Override
    @Transactional
    public Resolved resolveAccount(UUID tenantId, UUID brandId, String rawPhone) {
        Redemption redemption = verification.redeemAsTelegramContact(tenantId, brandId, rawPhone);
        return new Resolved(redemption.account().accountId(), redemption.created());
    }

    @Override
    @Transactional
    public Session establishSession(UUID tenantId, UUID brandId, UUID accountId, boolean accountCreated) {
        CustomerSessionService.Established established =
                sessions.establishForAccount(tenantId, brandId, accountId, accountCreated);
        return new Session(established.token(), established.expiresAt(), accountId, accountCreated);
    }
}
