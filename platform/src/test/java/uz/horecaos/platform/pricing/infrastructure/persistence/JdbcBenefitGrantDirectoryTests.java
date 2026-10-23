package uz.horecaos.platform.pricing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.pricing.api.BenefitGrantDirectory;
import uz.horecaos.platform.pricing.api.BenefitGrantDirectory.GrantSummary;
import uz.horecaos.platform.support.TestDatabase;

/**
 * What a service-recovery case may learn about the apology code it is asked to link (ADR 0159, Decision 9):
 * whose the grant is and where it came from, and nothing else -- never the code, never the discount.
 *
 * <p>The directory is the only thing standing between a case and another tenant's grant, so the
 * isolation test names the same id under both tenants and asserts each sees only its own.
 */
class JdbcBenefitGrantDirectoryTests {

    private static final UUID TENANT = UUID.fromString("018f9f10-7000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9f10-7000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9f10-7000-7000-8000-0000000000b1");
    private static final UUID CUSTOMER = UUID.fromString("018f9f10-7000-7000-8000-0000000000c1");
    private static final UUID CASE = UUID.fromString("018f9f10-7000-7000-8000-0000000000d1");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcBenefitGrantDirectory directory;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the grant directory tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE pricing.benefit_grants").update();
        directory = new JdbcBenefitGrantDirectory(jdbc);
    }

    private UUID grant(UUID tenant, String status, String sourceType, @Nullable UUID sourceId, String hashSeed) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.benefit_grants (id, tenant_id, brand_id, customer_account_id, benefit_type, value,
                    currency, code_hash, code_hint, status, source_type, source_id, valid_from,
                    redeemed_at, order_id)
                VALUES (:id, :tenant, :brand, :customer, 'FIXED_AMOUNT_OFF_ORDER', 20000, 'UZS', :hash, 'ABCD',
                    :status, :sourceType, :sourceId, now(),
                    CASE WHEN :status = 'REDEEMED' THEN now() END, CASE WHEN :status = 'REDEEMED' THEN :id END)
                """)
                .param("id", id)
                .param("tenant", tenant)
                .param("brand", BRAND)
                .param("customer", CUSTOMER)
                .param(
                        "hash",
                        String.valueOf(hashSeed.hashCode())
                                .repeat(16)
                                .substring(0, 64)
                                .replaceAll("[^0-9]", "1"))
                .param("status", status)
                .param("sourceType", sourceType)
                .param("sourceId", sourceId)
                .update();
        return id;
    }

    @Test
    @DisplayName("it says whose a grant is, its status and what asked for it")
    void itDescribesAGrant() {
        UUID id = grant(TENANT, "ACTIVE", "RECOVERY_CASE", CASE, "first");

        assertThat(directory.summary(TENANT, id))
                .hasValue(new GrantSummary(id, BRAND, CUSTOMER, "ACTIVE", "RECOVERY_CASE", CASE));
    }

    @Test
    @DisplayName("a manually minted grant has no case, and a spent one says so")
    void aManualGrantHasNoSource() {
        UUID id = grant(TENANT, "REDEEMED", "OPERATOR_MANUAL", null, "second");

        assertThat(directory.summary(TENANT, id)).hasValueSatisfying(summary -> {
            assertThat(summary.sourceId()).isNull();
            assertThat(summary.sourceType()).isEqualTo("OPERATOR_MANUAL");
            assertThat(summary.status()).isEqualTo("REDEEMED");
        });
    }

    @Test
    @DisplayName("a grant of another tenant is absent, the same answer as one that does not exist")
    void anotherTenantsGrantIsAbsent() {
        UUID theirs = grant(OTHER_TENANT, "ACTIVE", "OPERATOR_MANUAL", null, "third");

        assertThat(directory.summary(TENANT, theirs)).isEmpty();
        assertThat(directory.summary(TENANT, UUID.randomUUID())).isEmpty();
        assertThat(directory.summary(OTHER_TENANT, theirs)).isPresent();
    }

    @Test
    @DisplayName("the summary has no field that could hold the code or the discount")
    void theSummaryCarriesNoCode() {
        assertThat(GrantSummary.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("grantId", "brandId", "customerAccountId", "status", "sourceType", "sourceId");
    }

    @Test
    @DisplayName("a customer's grants are listed newest first, with what each is worth and the end of its code, and"
            + " never another tenant's, brand's or customer's")
    void aCustomersGrantsAreListed() {
        UUID older = grant(TENANT, "REDEEMED", "OPERATOR_MANUAL", null, "older");
        jdbc.sql("UPDATE pricing.benefit_grants SET created_at = now() - interval '1 day' WHERE id = :id")
                .param("id", older)
                .update();
        UUID newer = grant(TENANT, "ACTIVE", "RECOVERY_CASE", CASE, "newer");
        grant(OTHER_TENANT, "ACTIVE", "OPERATOR_MANUAL", null, "foreign");
        UUID forSomeoneElse = grant(TENANT, "ACTIVE", "OPERATOR_MANUAL", null, "someone-else");
        jdbc.sql("UPDATE pricing.benefit_grants SET customer_account_id = gen_random_uuid() WHERE id = :id")
                .param("id", forSomeoneElse)
                .update();
        UUID otherBrand = grant(TENANT, "ACTIVE", "OPERATOR_MANUAL", null, "other-brand");
        jdbc.sql("UPDATE pricing.benefit_grants SET brand_id = gen_random_uuid() WHERE id = :id")
                .param("id", otherBrand)
                .update();

        var listed = directory.forCustomer(TENANT, BRAND, CUSTOMER);

        assertThat(listed)
                .extracting(BenefitGrantDirectory.GrantListing::grantId)
                .containsExactly(newer, older);
        assertThat(listed.get(0)).satisfies(grant -> {
            assertThat(grant.benefitType()).isEqualTo("FIXED_AMOUNT_OFF_ORDER");
            assertThat(grant.value()).isEqualTo(20_000L);
            assertThat(grant.currency()).isEqualTo("UZS");
            assertThat(grant.status()).isEqualTo("ACTIVE");
            assertThat(grant.sourceType()).isEqualTo("RECOVERY_CASE");
            assertThat(grant.sourceId()).isEqualTo(CASE);
            assertThat(grant.codeHint()).isEqualTo("ABCD");
        });
        assertThat(BenefitGrantDirectory.GrantListing.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .as("the hint is the last characters; there is no field that could hold the code or its hash")
                .doesNotContain("code", "codeHash", "plaintextCode");
    }
}
