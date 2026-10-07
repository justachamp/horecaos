package uz.horecaos.platform.commercial.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * HorecaOS's own account with one e-invoicing operator (ADR 0096, ADR 0026): a
 * platform installation -- no tenant -- naming an approved environment, holding
 * a secret <em>reference</em> (ADR 0028) and the seller's public identity.
 *
 * <p>Seeded unbound. HorecaOS has no account with either operator yet, so an
 * installation is {@link #connected()} only once somebody has stored the
 * credential, written down its reference and the seller's identity here, and
 * activated it; until then {@link #missing()} says exactly what is still needed,
 * and the control plane says so.
 */
public record EInvoicingInstallation(
        UUID id,
        String providerType,
        String environmentCode,
        String displayName,
        String status,
        @Nullable String secretReference,
        Map<String, String> config,
        String adapterVersion,
        long version,
        String updatedBy,
        Instant updatedAt) {

    public static final String DRAFT = "DRAFT";
    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";

    public static final String DIDOX = "DIDOX";
    public static final String FAKTURA_UZ = "FAKTURA_UZ";

    /** The seller's taxpayer number: also the login the operator knows HorecaOS by. */
    public static final String SELLER_TAXPAYER_NUMBER = "sellerTaxpayerNumber";

    public static final String SELLER_NAME = "sellerName";
    public static final String SELLER_ADDRESS = "sellerAddress";
    public static final String SELLER_VAT_REGISTRATION_CODE = "sellerVatRegistrationCode";
    public static final String SELLER_BANK_ACCOUNT = "sellerBankAccount";
    public static final String SELLER_BANK_CODE = "sellerBankCode";
    public static final String SELLER_BANK_NAME = "sellerBankName";

    /** {@code ru} or {@code uz}: the language the operator's login is made in. Defaults to {@code ru}. */
    public static final String LOCALE = "locale";

    /** The configuration keys the installation may carry; anything else is refused rather than stored. */
    public static final List<String> CONFIG_KEYS = List.of(
            SELLER_TAXPAYER_NUMBER,
            SELLER_NAME,
            SELLER_ADDRESS,
            SELLER_VAT_REGISTRATION_CODE,
            SELLER_BANK_ACCOUNT,
            SELLER_BANK_CODE,
            SELLER_BANK_NAME,
            LOCALE);

    public static final String NEEDS_SECRET_REFERENCE = "SECRET_REFERENCE";

    public EInvoicingInstallation {
        config = Map.copyOf(config);
    }

    /** What an installation still lacks before it can be activated, as stable codes. */
    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (secretReference == null || secretReference.isBlank()) {
            missing.add(NEEDS_SECRET_REFERENCE);
        }
        if (blank(config.get(SELLER_TAXPAYER_NUMBER))) {
            missing.add("SELLER_TAXPAYER_NUMBER");
        }
        if (blank(config.get(SELLER_NAME))) {
            missing.add("SELLER_NAME");
        }
        return List.copyOf(missing);
    }

    /** Whether documents can be sent through it right now. */
    public boolean connected() {
        return ACTIVE.equals(status) && missing().isEmpty();
    }

    /** Whether the seller is registered for VAT, which decides whether VAT is charged at all. */
    public boolean sellerChargesVat() {
        return !blank(config.get(SELLER_VAT_REGISTRATION_CODE));
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    /**
     * Names the reference's presence, never its text: a reference is not a secret,
     * but a diagnostic line that prints one teaches that printing them is fine.
     */
    @Override
    public String toString() {
        return "EInvoicingInstallation[id=%s, provider=%s, status=%s, secret=%s]"
                .formatted(id, providerType, status, secretReference == null ? "none" : "bound");
    }
}
