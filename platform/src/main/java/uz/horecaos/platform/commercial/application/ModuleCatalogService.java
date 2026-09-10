package uz.horecaos.platform.commercial.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
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
import uz.horecaos.platform.commercial.api.EntitlementKey;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.domain.BillingUnit;
import uz.horecaos.platform.commercial.domain.SellableModule;
import uz.horecaos.platform.commercial.domain.TenantModule;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
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
    private final AuditRecorder audit;
    private final Clock clock;

    public ModuleCatalogService(JdbcModuleStore modules, AuditRecorder audit, Clock clock) {
        this.modules = modules;
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

        Map<String, Object> change = new HashMap<>();
        change.put("code", code);
        change.put("billingUnit", billingUnit.name());
        change.put("currency", currency);
        change.put("unitPriceMinor", unitPriceMinor);
        change.put("featureKeys", features);
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
        record(
                "commercial.module.activated",
                approver,
                moduleId,
                reason,
                Map.of("code", module.code(), "unitPriceMinor", module.unitPriceMinor(), "currency", module.currency()),
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
        record(
                "commercial.module.retired",
                actor,
                moduleId,
                reason,
                Map.of("code", module.code()),
                Capability.COMMERCIAL_PLAN_MANAGE,
                correlationId,
                now);
    }

    /**
     * Gives a tenant a module.
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
            modules.insertTenantModule(
                    new TenantModule(id, tenantId, moduleId, quantity, now, subject(actor), reason, null, null, null));
        } catch (DuplicateKeyException already) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "The tenant already has module %s".formatted(module.code()));
        }

        Map<String, Object> change = new HashMap<>();
        change.put("moduleCode", module.code());
        change.put("billingUnit", module.billingUnit().name());
        if (quantity != null) {
            change.put("quantity", quantity);
        }
        audit.record(AuditFact.of("commercial.tenant_module.added", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.tenant(tenantId))
                .target("commercial.tenant_module", id)
                .because(reason)
                .changed(change)
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
                .changed(Map.of("moduleId", held.moduleId().toString()))
                .usingCapability(Capability.COMMERCIAL_SUBSCRIPTION_MANAGE.code())
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());
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
