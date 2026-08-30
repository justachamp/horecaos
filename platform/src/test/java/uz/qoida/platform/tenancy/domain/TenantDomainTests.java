package uz.qoida.platform.tenancy.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import uz.qoida.platform.tenancy.api.TenantId;

class TenantDomainTests {

    private static final TenantId TENANT_ID = new TenantId(UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120002"));

    @Test
    void normalizesAndValidatesSlugs() {
        assertThat(new Slug("  Qoida-Food  ").value()).isEqualTo("qoida-food");
        assertThatIllegalArgumentException().isThrownBy(() -> new Slug("not valid"));
        assertThatIllegalArgumentException().isThrownBy(() -> new Slug("-invalid"));
    }

    @Test
    void permitsOnlyExplicitTenantLifecycleTransitions() {
        Tenant tenant = Tenant.provision(
                TENANT_ID,
                new Slug("qoida-food"),
                "Qoida Foods LLC",
                "Qoida Food",
                Currency.getInstance("UZS"),
                ZoneId.of("Asia/Tashkent"));

        tenant.activate();
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);

        assertThatIllegalStateException().isThrownBy(tenant::archive);
        tenant.suspend();
        tenant.archive();
        assertThat(tenant.status()).isEqualTo(TenantStatus.ARCHIVED);
        assertThatIllegalStateException().isThrownBy(tenant::activate);
    }

    @Test
    void requiresApprovedMigrationToChangeIdentityModeWhenCustomerDataExists() {
        Instant now = Instant.parse("2026-08-18T08:00:00Z");
        CustomerIdentityPolicy policy = CustomerIdentityPolicy.initial(
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120010"),
                TENANT_ID,
                CustomerIdentityMode.TENANT_SHARED,
                now);

        assertThatIllegalStateException().isThrownBy(() -> policy.supersede(
                UUID.randomUUID(),
                CustomerIdentityMode.BRAND_ISOLATED,
                now.plusSeconds(60),
                true,
                false));

        CustomerIdentityPolicy replacement = policy.supersede(
                UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120011"),
                CustomerIdentityMode.BRAND_ISOLATED,
                now.plusSeconds(60),
                true,
                true);

        assertThat(policy.supersededAt()).isEqualTo(now.plusSeconds(60));
        assertThat(replacement.version()).isEqualTo(2);
        assertThat(replacement.mode()).isEqualTo(CustomerIdentityMode.BRAND_ISOLATED);
    }
}
