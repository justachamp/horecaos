package uz.horecaos.platform.integration.api.provider;

import java.util.List;
import java.util.Optional;

/**
 * Per-adapter connect field declarations (ADR 0065).
 *
 * <p>"Provider connect flows are declarative per adapter... so the screen
 * renders from the adapter's declaration and a new provider means no new screen
 * work — the same neutrality discipline ADR 0064 just recorded for voice." This
 * is that declaration: a small, code-owned registry the control-plane app reads
 * to build a connect form, rather than a screen hand-written per provider.
 *
 * <p>Each field says only whether it is a value the write-only door (ADR 0065)
 * must carry, or non-sensitive configuration the installation/binding call
 * carries directly. Labels, help text, and validation copy are deliberately not
 * here: those are presentation concerns the frontend's own i18n catalogue owns,
 * the same separation {@link ProviderCategory} keeps from a display name.
 */
public final class ConnectFieldCatalog {

    private static final List<ProviderConnectDeclaration> DECLARATIONS = List.of(
            new ProviderConnectDeclaration(
                    "CLICK",
                    ProviderCategory.PAYMENT,
                    List.of(
                            new ConnectField("merchantId", false),
                            new ConnectField("serviceId", false),
                            new ConnectField("secretKey", true))),
            new ProviderConnectDeclaration(
                    "PAYME",
                    ProviderCategory.PAYMENT,
                    List.of(new ConnectField("cashboxId", false), new ConnectField("key", true))),
            new ProviderConnectDeclaration(
                    "TELEGRAM_BOT_API", ProviderCategory.NOTIFICATION, List.of(new ConnectField("botToken", true))));

    private ConnectFieldCatalog() {}

    public static List<ProviderConnectDeclaration> all() {
        return DECLARATIONS;
    }

    public static Optional<ProviderConnectDeclaration> forProviderType(String providerType) {
        return DECLARATIONS.stream()
                .filter(declaration -> declaration.providerType().equals(providerType))
                .findFirst();
    }

    /**
     * @param key a stable field name, matched against the connect form the
     *            frontend renders. Not localized; not a database column
     * @param secret true when this field's value must travel through the ADR
     *               0065 door rather than as non-sensitive configuration on
     *               the installation or merchant-binding call
     */
    public record ConnectField(String key, boolean secret) {}

    public record ProviderConnectDeclaration(
            String providerType, ProviderCategory category, List<ConnectField> fields) {}
}
