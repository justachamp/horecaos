package uz.horecaos.platform.customers.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Which tenants know a phone number as a customer (ADR 0094).
 *
 * <p>For support answering "I am a customer of one of your restaurants": the
 * number is hashed with each tenant's own key and matched the way the tenant's
 * operators match it, and the answer is the tenant and the account identifier,
 * never a name or a contact value. The number travels in the request body,
 * never a URL, and every lookup is audited with its reason, found or not,
 * without the number itself.
 */
@RestController
@Tag(name = "Global lookup", description = "ADR 0083: find an identifier across tenants")
public class PlatformCustomerLookupController {

    private final CustomerProfileService profiles;
    private final JdbcClient jdbc;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public PlatformCustomerLookupController(
            CustomerProfileService profiles,
            JdbcClient jdbc,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock) {
        this.profiles = profiles;
        this.jdbc = jdbc;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @PostMapping("/api/v1/control-plane/customer-lookups")
    @RequiresCapability(value = Capability.CUSTOMER_PII_REVEAL, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Find which tenants know a phone number as a customer",
            description = "Answers tenants and account identifiers only. Audited with its reason whether "
                    + "or not anything matched; the number is never recorded.")
    @Transactional
    CustomerLookupResult lookup(@Valid @RequestBody CustomerLookupRequest body) {
        List<TenantRef> tenants = jdbc.sql("SELECT id, display_name FROM tenant.tenants ORDER BY display_name")
                .query((row, number) -> new TenantRef(row.getObject("id", UUID.class), row.getString("display_name")))
                .list();
        List<CustomerMatch> matches = new ArrayList<>();
        for (TenantRef tenant : tenants) {
            for (UUID accountId : profiles.findAccountsByContact(
                    tenant.id(), CustomerProfileService.ContactType.PHONE, body.phone())) {
                matches.add(new CustomerMatch(tenant.id(), tenant.name(), accountId));
            }
        }

        String subject = currentActor.get().subject();
        audit.record(AuditFact.of("customer.phone_lookup.performed", AuditClass.SECURITY)
                .by(ActorRef.user(subject, null))
                .at(ResourceScope.platform())
                .because(body.reason())
                .changed(Map.of("tenantsSearched", tenants.size(), "matches", matches.size()))
                .usingCapability(Capability.CUSTOMER_PII_REVEAL.code())
                .correlatedBy(UUID.randomUUID().toString())
                .occurredAt(clock.instant())
                .build());
        return new CustomerLookupResult(tenants.size(), matches);
    }

    private record TenantRef(UUID id, String name) {}

    /** How many tenants were searched, and the accounts that hold the number. */
    public record CustomerLookupResult(int tenantsSearched, List<CustomerMatch> matches) {}

    /** One tenant's account holding the number: identifiers only. */
    public record CustomerMatch(UUID tenantId, String tenantName, UUID accountId) {}

    public record CustomerLookupRequest(
            @NotBlank @Size(max = 32) String phone,
            @NotBlank @Size(max = 1000) String reason) {}
}
