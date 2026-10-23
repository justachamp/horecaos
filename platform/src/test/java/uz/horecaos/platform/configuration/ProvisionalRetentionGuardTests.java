package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * ADR 0029's "Provisional retention": unconfirmed numbers cannot govern real customer data.
 *
 * <p>The guard existed for one value (courier tracks) and the ADR promised it for all of them. What each test would still
 * pass on if the guard were broken: the production cases assert the start is <em>refused</em> and name which values it
 * refused on, so a guard that logged and continued fails them; the confirmation cases assert a reference is required, so a
 * guard that accepted a bare {@code all} fails them; the register test reads every tenant-visible retention key out of the
 * code, so a key added without being registered fails it.
 */
class ProvisionalRetentionGuardTests {

    private static ProvisionalRetentionGuard guard(String profile, String confirmed, String reference, String mode) {
        MockEnvironment environment = new MockEnvironment();
        if (!profile.isEmpty()) {
            environment.setActiveProfiles(profile);
        }
        return new ProvisionalRetentionGuard(environment, confirmed, reference, mode);
    }

    @Test
    @DisplayName("a production start is refused while any retention value is unconfirmed, and says which")
    void productionRefusesUnconfirmedValues() {
        assertThatThrownBy(() -> guard("production", "", "", "AUTO").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ordering.cart_retention_days")
                .hasMessageContaining("telemetry.track_retention_days")
                .hasMessageContaining("courier.applicant_retention_months")
                .hasMessageContaining("customers.dsar_cover_note")
                .hasMessageContaining("class.personal");
    }

    @Test
    @DisplayName("a confirmation with nothing to point at does not count")
    void aConfirmationNeedsAReference() {
        assertThatThrownBy(() -> guard("production", "all", "  ", "AUTO").check())
                .isInstanceOf(IllegalStateException.class);
        assertThat(guard("production", "all", "LEGAL-2026-117", "AUTO").check()).isEmpty();
    }

    @Test
    @DisplayName("a partial confirmation refuses on exactly what is left")
    void aPartialConfirmationNamesWhatIsLeft() {
        ProvisionalRetentionGuard partial = guard(
                "production",
                "class.personal, class.personal-sensitive, class.financial, class.observability, "
                        + "ordering.cart_retention_days",
                "LEGAL-2026-117",
                "AUTO");

        assertThat(partial.unconfirmed())
                .extracting(ProvisionalRetention.Entry::code)
                .containsExactly(
                        "telemetry.track_retention_days",
                        "courier.applicant_retention_months",
                        "iam.staff_sign_in_retention_days",
                        "customers.dsar_cover_note");
        assertThat(partial.isProvisional("ordering.cart_retention_days")).isFalse();
        assertThat(partial.isProvisional("telemetry.track_retention_days")).isTrue();
        assertThatThrownBy(partial::check)
                .hasMessageContaining("telemetry.track_retention_days")
                .hasMessageNotContaining("ordering.cart_retention_days");
    }

    @Test
    @DisplayName("local, test and default profiles report and continue")
    void localProfilesReportAndContinue() {
        assertThat(guard("local", "", "", "AUTO").check()).hasSameSizeAs(ProvisionalRetention.entries());
        assertThat(guard("test", "", "", "AUTO").check()).isNotEmpty();
        assertThat(guard("", "", "", "AUTO").check()).isNotEmpty();
    }

    @Test
    @DisplayName("REPORT_ONLY keeps a production start going for one deployment; ENFORCE refuses even locally")
    void theModesOverrideTheProfile() {
        assertThat(guard("production", "", "", "REPORT_ONLY").check()).isNotEmpty();
        assertThatThrownBy(() -> guard("local", "", "", "ENFORCE").check()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName(
            "every tenant-visible retention key in the code is in the register, and every register code is a real key or a class default")
    void theRegisterListsEveryTenantVisibleRetentionKey() throws Exception {
        List<String> retentionKeys = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*ConfigurationKeys")));
        for (var candidate : scanner.findCandidateComponents("uz.horecaos.platform")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        && ConfigurationKey.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    ConfigurationKey<?> key = (ConfigurationKey<?>) field.get(null);
                    if (key.tenantVisible() && key.code().contains("retention")) {
                        retentionKeys.add(key.code());
                    }
                }
            }
        }

        List<String> distinct = retentionKeys.stream().distinct().toList();
        assertThat(distinct)
                .as("the scan must find the retention keys at all, or the assertion below is over nothing")
                .contains(
                        "ordering.cart_retention_days",
                        "telemetry.track_retention_days",
                        "courier.applicant_retention_months",
                        "iam.staff_sign_in_retention_days");
        assertThat(ProvisionalRetention.codes())
                .as("a tenant-visible retention setting that is not in the register would govern real data unconfirmed")
                .containsAll(distinct);

        List<String> classDefaults = ProvisionalRetention.entries().stream()
                .map(ProvisionalRetention.Entry::code)
                .filter(code -> code.startsWith("class."))
                .toList();
        assertThat(classDefaults)
                .containsExactly(
                        "class.personal", "class.personal-sensitive", "class.financial", "class.observability");
        assertThat(ProvisionalRetention.entries())
                .extracting(ProvisionalRetention.Entry::code)
                .doesNotHaveDuplicates();
    }
}
