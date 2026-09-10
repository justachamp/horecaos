package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.TenantProfileService;
import uz.horecaos.platform.tenancy.domain.Markets;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcTenantProfileStore.PublicHoliday;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The platform's own supported countries, locales and public holidays
 * (control-plane IA 8.3).
 *
 * <p><strong>Not a general ISO reference-data service.</strong> {@code
 * CreateTenantRequest.defaultCurrency} validates any three-letter ISO 4217
 * code and {@code defaultTimezone} any {@link java.time.ZoneId}; the countries
 * here are the markets the platform trades in ({@link Markets}), so a
 * platform-admin filling in a tenant-creation form sees the deliberate set
 * rather than picking blind from every currency and timezone in the world.
 *
 * <p>Public holidays (ADR 0090) are kept per country: fixed dates recur every
 * year and movable ones are entered for the year they fall in. The elapsed-time
 * buckets reports use are fixed by the reporting module and read from there.
 */
@RestController
@RequestMapping("/api/v1/control-plane/reference-data")
@Tag(name = "Reference data", description = "The platform's own supported countries, locales and public holidays")
public class ReferenceDataController {

    private static final List<Locale> LOCALES =
            List.of(new Locale("ru", "Русский"), new Locale("uz-Latn", "O'zbekcha"), new Locale("en", "English"));

    private final TenantProfileService profiles;
    private final CurrentActor currentActor;

    public ReferenceDataController(TenantProfileService profiles, CurrentActor currentActor) {
        this.profiles = profiles;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "The platform's supported reference values",
            description = "Countries this platform trades in with their default currency and timezone, "
                    + "the locales the staff consoles ship, and each country's public holidays.")
    ReferenceData get() {
        return new ReferenceData(
                Markets.all().stream()
                        .map(market -> new Country(
                                market.code(), market.name(), market.defaultCurrency(), market.defaultTimezone()))
                        .toList(),
                LOCALES,
                profiles.holidays().stream().map(Holiday::of).toList());
    }

    @PostMapping("/holidays")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Add a public holiday",
            description = "A month and day for one that recurs every year, or a date for one that moves "
                    + "with the lunar calendar.")
    ResponseEntity<HolidayAdded> addHoliday(@Valid @RequestBody HolidayRequest body) {
        UUID id = profiles.addHoliday(
                body.countryCode(), body.name(), body.month(), body.day(), body.date(), actor(), body.reason());
        return ResponseEntity.ok(new HolidayAdded(id));
    }

    @DeleteMapping("/holidays/{holidayId}")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Remove a public holiday")
    ResponseEntity<Void> removeHoliday(@PathVariable UUID holidayId, @Valid @RequestBody RemoveHolidayRequest body) {
        profiles.removeHoliday(holidayId, actor(), body.reason());
        return ResponseEntity.noContent().build();
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    public record ReferenceData(List<Country> countries, List<Locale> locales, List<Holiday> holidays) {}

    public record Country(String code, String name, String defaultCurrency, String defaultTimezone) {}

    public record Locale(String code, String displayName) {}

    /** One public holiday: a month and day every year, or one date. */
    public record Holiday(
            UUID holidayId,
            String countryCode,
            String name,
            @Nullable Integer month,
            @Nullable Integer day,
            @Nullable String date) {

        static Holiday of(PublicHoliday holiday) {
            LocalDate date = holiday.date();
            return new Holiday(
                    holiday.id(),
                    holiday.countryCode(),
                    holiday.name(),
                    holiday.month(),
                    holiday.day(),
                    date == null ? null : date.toString());
        }
    }

    public record HolidayAdded(UUID holidayId) {}

    public record HolidayRequest(
            @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
            @NotBlank @Size(max = 200) String name,
            @Min(1) @Max(12) Integer month,
            @Min(1) @Max(31) Integer day,
            LocalDate date,
            @NotBlank @Size(max = 1000) String reason) {}

    public record RemoveHolidayRequest(
            @NotBlank @Size(max = 1000) String reason) {}
}
