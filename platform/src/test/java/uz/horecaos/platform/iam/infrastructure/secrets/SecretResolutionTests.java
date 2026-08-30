package uz.horecaos.platform.iam.infrastructure.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.horecaos.platform.iam.api.secrets.SecretCategory;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;

/** ADR 0028: rotation without a restart, no leaks, and a provider-neutral reference. */
class SecretResolutionTests {

    private static final SecretReference PAYMENT =
            new SecretReference("local", SecretCategory.PROVIDER_PAYMENT, "installation-1", "api-key");

    @Test
    void resolvesAConfiguredSecret() {
        var fixture = fixture(Map.of(EnvironmentSecretResolver.propertyNameFor(PAYMENT), "s3cret"));

        assertThat(fixture.resolver().resolve(PAYMENT).reveal()).isEqualTo("s3cret");
    }

    @Test
    void aMissingSecretFailsRatherThanReturningEmpty() {
        var fixture = fixture(Map.of());

        assertThatThrownBy(() -> fixture.resolver().resolve(PAYMENT))
                .isInstanceOf(SecretResolver.SecretNotFoundException.class)
                .hasMessageContaining("provider_payment");
    }

    @Test
    void aRotatedSecretIsPickedUpAfterTheCacheExpiresWithoutARestart() {
        Map<String, String> values = new HashMap<>();
        values.put(EnvironmentSecretResolver.propertyNameFor(PAYMENT), "old");
        var fixture = fixture(values);

        assertThat(fixture.resolver().resolve(PAYMENT).reveal()).isEqualTo("old");
        values.put(EnvironmentSecretResolver.propertyNameFor(PAYMENT), "new");

        assertThat(fixture.resolver().resolve(PAYMENT).reveal())
                .as("within the TTL the cached value is still served")
                .isEqualTo("old");

        fixture.clock().advance(EnvironmentSecretResolver.CACHE_TTL.plusSeconds(1));

        assertThat(fixture.resolver().resolve(PAYMENT).reveal()).isEqualTo("new");
    }

    @Test
    void resolveFreshBypassesTheCacheImmediately() {
        Map<String, String> values = new HashMap<>();
        values.put(EnvironmentSecretResolver.propertyNameFor(PAYMENT), "old");
        var fixture = fixture(values);
        fixture.resolver().resolve(PAYMENT);

        values.put(EnvironmentSecretResolver.propertyNameFor(PAYMENT), "rotated");

        assertThat(fixture.resolver().resolveFresh(PAYMENT).reveal())
                .as("an adapter calls this once after an auth failure, so a mid-cache rotation is not an outage")
                .isEqualTo("rotated");
    }

    @Test
    void aSecretValueNeverRendersItselfInAString() {
        SecretValue value = SecretValue.of("super-secret-token");

        assertThat(value.toString()).doesNotContain("super-secret-token").contains("REDACTED");
        assertThat("interpolated: " + value).doesNotContain("super-secret-token");
        assertThat(String.format("%s", value)).doesNotContain("super-secret-token");
    }

    @Test
    void aReferenceRoundTripsAndStaysProviderNeutral() {
        String rendered = PAYMENT.toString();

        assertThat(rendered).isEqualTo("horecaos:local:provider_payment:installation-1:api-key");
        assertThat(SecretReference.parse(rendered)).isEqualTo(PAYMENT);
        assertThat(rendered)
                .as("ADR 0034 moves provider in phase two; a reference must survive it unchanged")
                .doesNotContain("arn:")
                .doesNotContain("vault")
                .doesNotContain("secretsmanager");
    }

    @Test
    void aMalformedReferenceIsRejected() {
        assertThatThrownBy(() -> SecretReference.parse("horecaos:local:provider_payment:only-four"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretReference("local", SecretCategory.DATABASE, "owner", "has:separator"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNonLocalProfileRefusesToStartOnEnvironmentSecrets() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        var fixture = fixture(Map.of());

        SecretsProfileGuard guard = new SecretsProfileGuard(environment, fixture.resolver());

        assertThatThrownBy(() -> guard.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADR 0028");
    }

    @Test
    void aLocalProfileStartsNormally() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        var fixture = fixture(Map.of());

        new SecretsProfileGuard(environment, fixture.resolver()).run(null);
    }

    private static Fixture fixture(Map<String, String> values) {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        return new Fixture(new EnvironmentSecretResolver(values::get, clock), clock);
    }

    private record Fixture(EnvironmentSecretResolver resolver, MutableClock clock) {}

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
