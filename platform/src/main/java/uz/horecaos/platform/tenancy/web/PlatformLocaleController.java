package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.tenancy.api.PlatformLocale;
import uz.horecaos.platform.tenancy.api.PlatformLocale.Tier;
import uz.horecaos.platform.tenancy.api.PlatformLocales;

/**
 * Which languages exist, and where each is live (ADR 0149, Decision 5): the registry, read.
 *
 * <p>A console or a storefront that used to carry its own {@code ['ru', 'uz-Latn', 'en']} reads
 * this instead and offers a language when its build contains a catalogue <em>and</em> the registry
 * says the tier is live. It is a read of code, not of a table: the answer is the same for every
 * tenant and changes only with a release. It says nothing about anybody, so the staff surfaces
 * answer any signed-in principal and the storefront's answers a visitor with no account yet
 * (the language picker is the first thing a storefront paints; {@code SecurityConfiguration}
 * lists the path beside the other pre-account reads).
 *
 * <p>One method per surface, because ADR 0057 puts every published path in exactly one OpenAPI
 * group by its prefix, and two paths on one method would publish one operation id twice.
 */
@RestController
@Tag(name = "Locales", description = "The languages the platform knows and where each is live (ADR 0149)")
public class PlatformLocaleController {

    @GetMapping("/api/v1/operations/locales")
    @Operation(
            summary = "The platform's languages (operations)",
            description = "Every declared language, live or not, with the tiers it is live in "
                    + "(CONTENT, MESSAGES, STAFF_UI), its script, direction, face and names. A read of code.")
    public PlatformLocalesResponse operations() {
        return PlatformLocalesResponse.current();
    }

    @GetMapping("/api/v1/control-plane/locales")
    @Operation(
            summary = "The platform's languages (control plane)",
            description = "The same answer as the operations surface, for the control plane's own client.")
    public PlatformLocalesResponse controlPlane() {
        return PlatformLocalesResponse.current();
    }

    @GetMapping("/api/v1/storefront/locales")
    @Operation(
            summary = "The platform's languages (storefront)",
            description = "The same answer as the operations surface, readable before an account exists, "
                    + "for the storefronts' and the mobile app's own clients.")
    public PlatformLocalesResponse storefront() {
        return PlatformLocalesResponse.current();
    }

    /**
     * @param fallback the tag every reader falls back to when a customer or a brand names none
     */
    public record PlatformLocalesResponse(List<PlatformLocaleEntryResponse> locales, String fallback) {

        static PlatformLocalesResponse current() {
            return new PlatformLocalesResponse(
                    PlatformLocales.all().stream()
                            .map(PlatformLocaleEntryResponse::of)
                            .toList(),
                    PlatformLocales.fallback().tag());
        }
    }

    /**
     * @param catalogCode what the catalog stores for this language ({@code uz} for {@code uz-Latn}): a
     *     client that writes a catalog translation uses it, so the mapping lives here and not in a
     *     console's own conversion
     * @param inputAliases spellings the platform reads and never stores
     * @param tiers where the language is live; empty means declared, not live
     */
    public record PlatformLocaleEntryResponse(
            String tag,
            String catalogCode,
            List<String> inputAliases,
            String script,
            String direction,
            String face,
            int fallbackRank,
            List<String> tiers,
            Map<String, String> names) {

        static PlatformLocaleEntryResponse of(PlatformLocale locale) {
            return new PlatformLocaleEntryResponse(
                    locale.tag(),
                    locale.catalogCode(),
                    locale.inputAliases().stream().sorted().toList(),
                    locale.script().name(),
                    locale.direction().name(),
                    locale.face(),
                    locale.fallbackRank(),
                    java.util.Arrays.stream(Tier.values())
                            .filter(locale::active)
                            .map(Tier::name)
                            .toList(),
                    locale.names());
        }
    }
}
