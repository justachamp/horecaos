package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.customers.application.ConsentTypeService;
import uz.horecaos.platform.customers.application.ConsentTypeService.ConsentTypeView;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcConsentTypeStore.ConsentTypeRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;
import uz.horecaos.platform.tenancy.api.TenantLocaleSet;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The tenant-wide consent-purpose registry (Settings 10.11, ADR 0109) —
 * genuinely missing before this wave: {@code customer.consent_decisions} has
 * recorded a decision's purpose as free text since V0017, and there was no
 * table anywhere naming what a purpose code means or which language it reads
 * in. See {@link ConsentTypeService}'s own doc for why this is a reference
 * catalogue and not an enforcement point.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/consent-types")
@Tag(name = "Consent types", description = "The tenant's own consent-purpose registry")
public class ConsentTypeController {

    private final ConsentTypeService consentTypes;
    private final CurrentActor currentActor;

    public ConsentTypeController(ConsentTypeService consentTypes, CurrentActor currentActor) {
        this.consentTypes = consentTypes;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "Every consent purpose this tenant has defined",
            description = "Bootstraps the code-owned default catalogue on this tenant's first "
                    + "read, so a tenant with no rows yet still sees the purposes already in "
                    + "production use (MARKETING_PROMOTIONS, TERMS_OF_SERVICE, review.public_display) "
                    + "rather than an empty registry. A purpose's name is `labels`, a map by locale "
                    + "(ADR 0149): every language it has a name in, whether or not the tenant's brands "
                    + "currently offer it. labelRu/labelUz/labelEn are the platform triple's legacy "
                    + "columns, still sent for one release for a client that has not been rebuilt.")
    List<ConsentTypeResponse> list(@PathVariable UUID tenantId) {
        return consentTypes.list(tenantId, actor()).stream()
                .map(ConsentTypeResponse::of)
                .toList();
    }

    @GetMapping("/locale-set")
    @RequiresCapability(Capability.CUSTOMER_READ)
    @Operation(
            summary = "The languages a consent purpose is read in",
            description = "ADR 0149 Decision 4. A purpose belongs to the tenant, shared by every brand, so it is "
                    + "read in the union of the tenant's brands' supported languages, default first; the default is the "
                    + "tenant's first brand's. A brand that has chosen no set contributes the platform languages. "
                    + "The set is a menu for choosing a fallback order, not a constraint: a language outside it that a "
                    + "purpose already has a name in is still returned in `labels`.")
    LocaleSetResponse localeSet(@PathVariable UUID tenantId) {
        return LocaleSetResponse.of(consentTypes.localeSet(tenantId));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    /** @param locales default first; see {@link TenantLocaleSet} */
    record LocaleSetResponse(List<String> locales, String defaultLocale, boolean configured) {

        static LocaleSetResponse of(TenantLocaleSet set) {
            return new LocaleSetResponse(set.locales(), set.defaultLocale(), set.configured());
        }
    }

    /**
     * A legacy field's value: the wording in that language, else any wording the purpose has, else its code. Never null, so
     * the published contract of these three required fields does not change under a client that has not been rebuilt.
     */
    private static String legacyLabel(ConsentTypeView view, String locale) {
        String exact = view.labels().get(locale);
        if (exact != null && !exact.isBlank()) {
            return exact;
        }
        return view.labels().values().stream()
                .filter(label -> !label.isBlank())
                .findFirst()
                .orElse(view.row().code());
    }

    /**
     * One consent purpose, as the registry reads it.
     *
     * @param labels the purpose's name by locale tag
     * @param labelRu the {@code ru} name, or any name the purpose has when it has none in Russian; deprecated, kept for
     *     one release (V0683), and never null so the published contract of the three fields is unchanged
     */
    public record ConsentTypeResponse(
            UUID id,
            String code,
            Map<String, String> labels,
            String labelRu,
            String labelUz,
            String labelEn,
            @Nullable String description,
            boolean channelSpecific,
            String policyVersion,
            boolean active,
            Instant updatedAt) {

        static ConsentTypeResponse of(ConsentTypeView view) {
            ConsentTypeRow row = view.row();
            return new ConsentTypeResponse(
                    row.id(),
                    row.code(),
                    view.labels(),
                    legacyLabel(view, LocalizedLabels.RU),
                    legacyLabel(view, LocalizedLabels.UZ_LATN),
                    legacyLabel(view, LocalizedLabels.EN),
                    row.description(),
                    row.channelSpecific(),
                    row.policyVersion(),
                    row.active(),
                    row.updatedAt());
        }
    }
}
