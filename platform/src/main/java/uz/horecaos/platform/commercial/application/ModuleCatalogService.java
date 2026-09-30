package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.domain.BillingUnit;
import uz.horecaos.platform.commercial.domain.ModuleAcquisition;
import uz.horecaos.platform.commercial.domain.SellableModule;
import uz.horecaos.platform.commercial.domain.TenantModule;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Drafting, activating and retiring modules, and giving them to tenants (ADR 0087).
 *
 * <p>The same guards as a plan version: the features a module switches on must
 * be features the code declares, the person who activates it is not the person
 * who drafted it, and its terms are frozen at the database once it is live.
 */
@Service
public class ModuleCatalogService {

    private final JdbcModuleStore modules;
    private final JdbcSubscriptionStore subscriptions;
    private final AuditRecorder audit;
    private final Clock clock;

    public ModuleCatalogService(
            JdbcModuleStore modules, JdbcSubscriptionStore subscriptions, AuditRecorder audit, Clock clock) {
        this.modules = modules;
        this.subscriptions = subscriptions;
        this.audit = audit;
        this.clock = clock;
    }

    public List<SellableModule> all() {
        return modules.list();
    }

    /** What is on sale now: activated and not retired. */
    public List<SellableModule> onSale() {
        return modules.list().stream().filter(SellableModule::isOnSale).toList();
    }

    public List<TenantModule> tenantModules(UUID tenantId) {
        return modules.tenantModules(tenantId);
    }

    @Transactional
    public UUID draft(
            String code,
            String name,
            @Nullable String description,
            BillingUnit billingUnit,
            String currency,
            long unitPriceMinor,
            List<String> featureKeys,
            ActorRef actor,
            String reason,
            String correlationId) {

        List<String> features = validatedFeatures(featureKeys);
        if (unitPriceMinor < 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A price is never negative");
        }
        UUID id = Ids.newId();
        Instant now = clock.instant();
        try {
            modules.insert(
                    new SellableModule(
                            id,
                            code,
                            name,
                            description,
                            billingUnit,
                            currency,
                            unitPriceMinor,
                            features,
                            SellableModule.DRAFT,
                            subject(actor),
                            null,
                            null,
                            null),
                    now);
        } catch (DuplicateKeyException taken) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A module with code %s already exists".formatted(code),
                    Map.of("moduleCode", code));
        }

        Map<String, Object> fields = new HashMap<>();
        fields.put("code", code);
        fields.put("billingUnit", billingUnit.name());
        fields.put("currency", currency);
        fields.put("unitPriceMinor", unitPriceMinor);
        fields.put("featureKeys", features);
        // Staff 9.3a: a brand-new module, no prior state to diff against.
        Map<String, Object> change = ChangeDocuments.created(fields);
        record(
                "commercial.module.drafted",
                actor,
                id,
                reason,
                change,
                Capability.COMMERCIAL_PLAN_MANAGE,
                correlationId,
                now);
        return id;
    }

    @Transactional
    public void activate(UUID moduleId, ActorRef approver, String reason, String correlationId) {
        SellableModule module = require(moduleId);
        if (module.isActivated()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "Module %s is already active".formatted(module.code()));
        }
        if (subject(approver).equals(module.createdBy())) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A module is approved by somebody other than the person who drafted it",
                    Map.of("createdBy", module.createdBy()));
        }
        validatedFeatures(module.featureKeys());
        Instant now = clock.instant();
        if (!modules.activate(moduleId, subject(approver), now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "The module changed while it was being activated");
        }
        // Staff 9.3a: "activated" genuinely moves -- the guard above already
        // proved module.isActivated() was false.
        Map<String, Object> change = ChangeDocuments.change("activated", false, true);
        record(
                "commercial.module.activated",
                approver,
                moduleId,
                reason,
                change,
                Capability.COMMERCIAL_PLAN_ACTIVATE,
                correlationId,
                now);
    }

    @Transactional
    public void retire(UUID moduleId, ActorRef actor, String reason, String correlationId) {
        SellableModule module = require(moduleId);
        Instant now = clock.instant();
        if (!modules.retire(moduleId, now)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Only a module on sale can be retired; %s is %s".formatted(module.code(), module.status()));
        }
        // Staff 9.3a: "status" genuinely moves -- modules.retire's own guard
        // above proved the row was on sale a moment ago, not already RETIRED.
        Map<String, Object> change = ChangeDocuments.change("status", module.status(), SellableModule.RETIRED);
        record(
                "commercial.module.retired",
                actor,
                moduleId,
                reason,
                change,
                Capability.COMMERCIAL_PLAN_MANAGE,
                correlationId,
                now);
    }

    /**
     * Gives a tenant a module: HorecaOS selling it (ADR 0087), which only
     * HorecaOS staff end again.
     *
     * <p>A quantity is required exactly when the module is billed per unit;
     * every other unit is counted from what the tenant has. One live instance
     * per module: a second kiosk raises the quantity by ending and adding again.
     */
    @Transactional
    public UUID add(
            UUID tenantId,
            UUID moduleId,
            @Nullable Integer quantity,
            ActorRef actor,
            String reason,
            String correlationId) {
        return give(tenantId, moduleId, quantity, actor, reason, correlationId, ModuleAcquisition.PLATFORM);
    }

    /**
     * A tenant buys a module for itself from its own console (ADR 0127).
     *
     * <p>The same on-sale check, quantity rule and one-live-instance guard as
     * {@link #add}; the only difference is the door recorded on the row, which
     * is what later lets the tenant, and only the tenant, undo this one with
     * {@link #endOwnPurchase}.
     */
    @Transactional
    public UUID purchase(
            UUID tenantId,
            UUID moduleId,
            @Nullable Integer quantity,
            ActorRef actor,
            String reason,
            String correlationId) {
        return give(tenantId, moduleId, quantity, actor, reason, correlationId, ModuleAcquisition.SELF_SERVICE);
    }

    private UUID give(
            UUID tenantId,
            UUID moduleId,
            @Nullable Integer quantity,
            ActorRef actor,
            String reason,
            String correlationId,
            ModuleAcquisition acquiredVia) {

        SellableModule module = require(moduleId);
        if (!module.isOnSale()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Module %s is not on sale".formatted(module.code()),
                    Map.of("status", module.status()));
        }
        boolean perUnit = module.billingUnit() == BillingUnit.PER_UNIT;
        if (perUnit && (quantity == null || quantity < 1)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Module %s is billed per unit; say how many".formatted(module.code()));
        }
        if (!perUnit && quantity != null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Module %s is counted from what the tenant has; it takes no quantity".formatted(module.code()));
        }

        UUID id = Ids.newId();
        Instant now = clock.instant();
        try {
            modules.insertTenantModule(new TenantModule(
                    id, tenantId, moduleId, quantity, now, subject(actor), reason, acquiredVia, null, null, null));
        } catch (DuplicateKeyException already) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "The tenant already has module %s".formatted(module.code()));
        }

        Map<String, Object> fields = new HashMap<>();
        fields.put("moduleCode", module.code());
        fields.put("billingUnit", module.billingUnit().name());
        fields.put("acquiredVia", acquiredVia.name());
        if (quantity != null) {
            fields.put("quantity", quantity);
        }
        audit.record(AuditFact.of("commercial.tenant_module.added", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.tenant_module", id)
                .because(reason)
                // Staff 9.3a: a brand-new tenant module, no prior state to diff against.
                .changed(ChangeDocuments.created(fields))
                .usingCapability(Capability.COMMERCIAL_SUBSCRIPTION_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return id;
    }

    @Transactional
    public void end(UUID tenantId, UUID tenantModuleId, ActorRef actor, String reason, String correlationId) {
        TenantModule held = modules.findTenantModule(tenantId, tenantModuleId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The tenant has no such module"));
        Instant now = clock.instant();
        if (!modules.endTenantModule(tenantId, tenantModuleId, subject(actor), reason, now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That module has already ended");
        }
        audit.record(AuditFact.of("commercial.tenant_module.ended", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.tenant_module", tenantModuleId)
                .because(reason)
                // Staff 9.3a: "live" genuinely moves -- held (read above,
                // before endTenantModule) was live or this call would have
                // failed the guard above.
                .changed(ChangeDocuments.diff(
                        Map.of("moduleId", held.moduleId().toString(), "live", true),
                        Map.of("moduleId", held.moduleId().toString(), "live", false)))
                .usingCapability(Capability.COMMERCIAL_SUBSCRIPTION_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    /**
     * A tenant undoes a module it bought itself (ADR 0127's status note of
     * 2026-09-30).
     *
     * <p>Only a module the tenant bought: one HorecaOS staff gave it is a sale
     * HorecaOS made, ended by HorecaOS through {@link #end}, so this refuses it
     * with {@code MODULE_ASSIGNED_BY_PLATFORM} rather than pretend it is absent.
     * A module of another tenant, or none, is not found: the lookup is by
     * tenant and id together, so an id from a neighbour reads the same as a
     * random one.
     *
     * <p>What the end costs is what ADR 0087 and ADR 0088 already decided, not
     * something invented here: nothing is prorated, a module live on any day of
     * a month bills that whole month, and ending keeps the row so the month
     * still finds it. So the end switches the features off now, the month it
     * happens in still bills the module in full, and no later month does. The
     * result names that last month so the caller can say so before and after.
     */
    @Transactional
    public ModuleEnding endOwnPurchase(
            UUID tenantId, UUID tenantModuleId, ActorRef actor, String reason, String correlationId) {
        TenantModule held = modules.findTenantModule(tenantId, tenantModuleId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "The tenant has no such module"));
        SellableModule module = require(held.moduleId());
        if (held.acquiredVia() != ModuleAcquisition.SELF_SERVICE) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Module %s was assigned by HorecaOS; ask HorecaOS to end it".formatted(module.code()),
                    Map.of("reason", "MODULE_ASSIGNED_BY_PLATFORM", "moduleCode", module.code()));
        }
        if (!held.isLive()) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That module has already ended");
        }

        Instant now = clock.instant();
        if (!modules.endTenantModule(tenantId, tenantModuleId, subject(actor), reason, now)) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "That module has already ended");
        }
        String lastBilledPeriod = lastBilledMonth(
                        module.billingUnit(), held.startedAt(), now, subscriptions.timezone(tenantId))
                .toString();

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("moduleCode", module.code());
        before.put("acquiredVia", held.acquiredVia().name());
        before.put("live", true);
        // Still open-ended: no last month yet.
        before.put("lastBilledPeriod", null);
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("live", false);
        after.put("lastBilledPeriod", lastBilledPeriod);
        audit.record(AuditFact.of("commercial.tenant_module.ended", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.tenant_module", tenantModuleId)
                .because(reason)
                .changed(ChangeDocuments.diff(before, after))
                .usingCapability(Capability.COMMERCIAL_SUBSCRIPTION_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
        return new ModuleEnding(now, lastBilledPeriod);
    }

    /** When a tenant's own module ended, and the last statement month that still bills it. */
    public record ModuleEnding(Instant endedAt, String lastBilledPeriod) {}

    /**
     * The last calendar month, in the tenant's timezone, a statement bills the
     * module for (ADR 0088).
     *
     * <p>A month bills a module that started before the month ended and did not
     * end at or before it began, so the last such month is the one holding the
     * instant just before the end. A one-off module bills only in the month it
     * started, whenever it ends.
     */
    static YearMonth lastBilledMonth(BillingUnit unit, Instant startedAt, Instant endedAt, ZoneId zone) {
        if (unit == BillingUnit.ONE_OFF || !endedAt.isAfter(startedAt)) {
            return YearMonth.from(startedAt.atZone(zone));
        }
        return YearMonth.from(endedAt.minusNanos(1).atZone(zone));
    }

    private SellableModule require(UUID moduleId) {
        return modules.find(moduleId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such module"));
    }

    /**
     * Refuses a feature the code does not declare, and a counted limit.
     *
     * <p>A module switches features on. Raising a counted limit is what a plan
     * or an override does; a module that did it too would make "why does this
     * tenant have that number" a question with three answers.
     */
    private static List<String> validatedFeatures(List<String> featureKeys) {
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String code : featureKeys) {
            EntitlementKey<?> key = EntitlementKeys.find(code)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "Unknown entitlement key %s".formatted(code),
                            Map.of("entitlementKey", code)));
            if (!key.isFeature()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "A module switches features on; %s is a counted limit".formatted(code),
                        Map.of("entitlementKey", code));
            }
            distinct.add(code);
        }
        return List.copyOf(distinct);
    }

    private void record(
            String action,
            ActorRef actor,
            UUID moduleId,
            String reason,
            Map<String, Object> change,
            Capability capability,
            String correlationId,
            Instant now) {
        audit.record(AuditFact.of(action, AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.platform())
                .target("commercial.module", moduleId)
                .because(reason)
                .changed(change)
                .usingCapability(capability.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
    }

    private static String subject(ActorRef actor) {
        return actor.subject() == null ? "" : actor.subject();
    }
}
