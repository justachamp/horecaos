package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The platform's own supported countries, currencies, locales, and timezones
 * (control-plane IA 8.3), read-only.
 *
 * <p><strong>Not a general ISO reference-data service.</strong> {@code
 * CreateTenantRequest.defaultCurrency} validates any three-letter ISO 4217
 * code and {@code defaultTimezone} any {@link java.time.ZoneId}, so nothing in
 * the schema constrains a tenant to this list today — it names what this
 * platform actually markets to (ADR 0034: Uzbekistan, Kazakhstan, Georgia)
 * and what {@code I18nService}'s own three shipped locales are, so a
 * platform-admin filling in a tenant-creation form sees the deliberate set
 * rather than picking blind from every currency and timezone in the world.
 *
 * <p><strong>Named gaps, not silently absent</strong>: national holiday seeds
 * and default SLA buckets (also named by IA 8.3) are not modeled anywhere in
 * this codebase — no table, no endpoint, no seed data — and are not invented
 * here. A platform-admin reading this endpoint sees countries/currencies/
 * locales/timezones only.
 */
@RestController
@RequestMapping("/api/v1/control-plane/reference-data")
@Tag(name = "Reference data", description = "The platform's own supported countries, currencies, locales, timezones")
public class ReferenceDataController {

    private static final List<Country> COUNTRIES = List.of(
            new Country("UZ", "Uzbekistan", "UZS", "Asia/Tashkent"),
            new Country("KZ", "Kazakhstan", "KZT", "Asia/Almaty"),
            new Country("GE", "Georgia", "GEL", "Asia/Tbilisi"));

    private static final List<Locale> LOCALES =
            List.of(new Locale("ru", "Русский"), new Locale("uz-Latn", "O'zbekcha"), new Locale("en", "English"));

    @GetMapping
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The platform's supported reference values",
            description = "Countries this platform markets to (ADR 0034) with their default currency "
                    + "and timezone, and the locales the staff consoles ship. Holiday seeds and SLA "
                    + "bucket defaults are not modeled and are not returned.")
    ReferenceData get() {
        return new ReferenceData(COUNTRIES, LOCALES);
    }

    public record ReferenceData(List<Country> countries, List<Locale> locales) {}

    public record Country(String code, String name, String defaultCurrency, String defaultTimezone) {}

    public record Locale(String code, String displayName) {}
}
