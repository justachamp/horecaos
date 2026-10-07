package uz.horecaos.platform.tenancy.api;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A tenant's company as another party's document names it (ADR 0038, ADR 0096).
 *
 * <p>The buyer on an invoice HorecaOS sends the tenant: the company's registered
 * name, its taxpayer number, whether it is registered for VAT and where it is
 * registered. Narrower than {@link FiscalSeller}, which answers "who sold this
 * order" by location and date; this answers "which companies does this tenant
 * have", for a caller that has to <em>choose</em> one rather than resolve one.
 *
 * <p>Company identifiers only. A taxpayer number here is an {@code ИНН} of a
 * legal entity, never a natural person's {@code ПИНФЛ} (the column holds nine
 * digits and nothing longer), so it is a business identifier, not personal data.
 */
public record LegalParty(
        UUID id,
        UUID tenantId,
        String code,
        String legalName,
        String taxpayerNumber,
        boolean vatRegistered,
        @Nullable String registeredAddress) {

    public LegalParty {
        Objects.requireNonNull(id, "A legal entity id is required");
        Objects.requireNonNull(tenantId, "A tenant id is required");
        Objects.requireNonNull(code, "A legal entity code is required");
        Objects.requireNonNull(legalName, "A legal name is required");
        Objects.requireNonNull(taxpayerNumber, "A taxpayer number is required");
    }

    /** The taxpayer number is an identifier of a company, but it still stays out of a log line. */
    @Override
    public String toString() {
        return "LegalParty[id=%s, code=%s]".formatted(id, code);
    }
}
