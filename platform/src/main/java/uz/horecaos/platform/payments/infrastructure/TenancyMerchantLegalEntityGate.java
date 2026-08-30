package uz.horecaos.platform.payments.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.payments.application.MerchantLegalEntityGate;
import uz.horecaos.platform.tenancy.api.LegalEntityDirectory;
import uz.horecaos.platform.tenancy.api.LegalEntitySummary;

/**
 * The real answer to "is this legal entity real and active" (ADR 0038,
 * unblocking ADR 0013's merchant-binding registry).
 *
 * <p>Sits beside {@link TenancyLegalEntityResolver} rather than inside it:
 * that class answers "who sells at this branch today", delegating to
 * {@link LegalEntityDirectory#sellerFor}, and this answers "is this id a real,
 * active entity of this tenant", delegating to
 * {@link LegalEntityDirectory#summary}. Different questions, against the same
 * directory, so both adapters exist rather than one doing double duty.
 *
 * <p>Module boundaries stay intact: only {@code tenancy.api} is imported, never
 * {@code tenancy.application} or {@code tenancy.infrastructure}.
 */
@Component
public class TenancyMerchantLegalEntityGate implements MerchantLegalEntityGate {

    private final LegalEntityDirectory directory;

    public TenancyMerchantLegalEntityGate(LegalEntityDirectory directory) {
        this.directory = directory;
    }

    @Override
    public Optional<Boolean> activeFor(UUID tenantId, UUID legalEntityId) {
        return directory.summary(tenantId, legalEntityId).map(LegalEntitySummary::active);
    }
}
