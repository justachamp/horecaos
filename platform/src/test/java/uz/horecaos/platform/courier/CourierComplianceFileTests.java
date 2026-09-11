package uz.horecaos.platform.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.courier.application.CourierEngagementService;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.application.CourierRosterQueryService;
import uz.horecaos.platform.courier.application.CourierRosterService;
import uz.horecaos.platform.courier.domain.ComplianceField;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The courier compliance file (IA 3.3, ADR 0029 over ADR 0042) — V0220 and
 * V0221, against the migrated schema.
 *
 * <p>Four things are worth a test here and they are not the ones a CRUD suite
 * would pick. That a value round-trips proves nothing about protection; what
 * matters is that <em>nothing readable</em> is in the column afterwards, that
 * the ciphertext is bound to its own column and not merely to its row, that a
 * reveal leaves a record of itself naming a purpose, and that the ПИНФЛ is
 * absent from every read except that reveal — asserted over the response types
 * themselves rather than over one query, because the next projection somebody
 * adds is the one that will carry it.
 *
 * <p>The {@link ColumnBoundProtection} fake is deliberately not a pass-through:
 * a plaintext written to a protected column by mistake would be visible as
 * plaintext in these assertions, and a ciphertext revealed against the wrong
 * column's {@code RecordRef} throws rather than quietly returning the value.
 * Both are exactly what the real AEAD binding does, and a fake that ignored the
 * {@code RecordRef} would let a broken caller pass.
 */
class CourierComplianceFileTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3100-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3100-7000-8000-0000000000c2");

    private static final String PASSPORT = "AA1234567";
    private static final String PINFL = "31234567890123";
    private static final String LICENCE = "AB 987654 / B";
    private static final String PLATE = "01 A 123 BC";
    private static final String HOME = "Tashkent, Chilonzor 14-24";
    private static final String NEXT_OF_KIN = "Dilnoza Karimova +998901112233";

    private static final Instant NOON = Instant.parse("2026-09-01T07:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCourierStore store;
    private CourierEngagementService engagements;
    private CourierRosterService roster;
    private CourierRosterQueryService rosterQuery;
    private RecordingAudit audit;
    private MutableClock clock;

    @SuppressWarnings("NullAway.Init")
    private UUID brandId;

    @SuppressWarnings("NullAway.Init")
    private UUID locationId;

    @SuppressWarnings("NullAway.Init")
    private UUID secondLocationId;

    @SuppressWarnings("NullAway.Init")
    private UUID courierId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the courier compliance file tests");
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
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_branch_bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_group_members CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_groups CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_engagements CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_types CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcCourierStore(jdbc);
        audit = new RecordingAudit();
        clock = new MutableClock(NOON);
        engagements = new CourierEngagementService(
                store,
                new ColumnBoundProtection(),
                audit,
                new CourierPolicyResolver(new DefaultPolicies()),
                (tenantId, assetIds) -> false,
                clock);
        roster = new CourierRosterService(store, audit, clock);
        // Load is not what this suite is about, and a fleet port that answered
        // from the shipment tables would need an order chain per courier; zero
        // is honest here and the real count is proven in CourierCompensationTests.
        rosterQuery = new CourierRosterQueryService(store, (tenantId, courierIds) -> Map.of());

        seedTenancy(TENANT);
        seedTenancy(OTHER_TENANT);
        courierId = registerCourier(TENANT, "K-001", "keycloak-k001");
    }

    // ------------------------------------------------------- envelope encryption

    @Test
    @DisplayName("every protected field is stored as ciphertext bound to its own column")
    void everyProtectedFieldIsStoredAsCiphertextBoundToItsOwnColumn() {
        engagements.recordComplianceFile(TENANT, courierId, wholeFile(), manager(), "onboarding", "corr-1");

        // 1. Nothing readable is left in any of the nine columns. Asserted per
        //    column rather than over a concatenation, so a single field that
        //    slipped through cannot hide behind eight that did not.
        for (ComplianceField field : ComplianceField.values()) {
            String stored = rawColumn(courierId, field.column());
            assertThat(stored).as("%s holds something", field).isNotNull();
            for (String plaintext : wholeFile().recorded().values()) {
                assertThat(stored)
                        .as("%s must not hold a plaintext of any field", field)
                        .doesNotContain(plaintext);
            }
        }

        // 2. And each one decrypts back to exactly what was filed.
        Map<ComplianceField, String> revealed =
                engagements.revealComplianceFile(TENANT, courierId, "compliance check", manager(), "corr-2");
        assertThat(revealed)
                .containsEntry(ComplianceField.PASSPORT, PASSPORT)
                .containsEntry(ComplianceField.PINFL, PINFL)
                .containsEntry(ComplianceField.DRIVING_LICENCE, LICENCE)
                .containsEntry(ComplianceField.VEHICLE_PLATE, PLATE)
                .containsEntry(ComplianceField.ADDRESS, HOME)
                .containsEntry(ComplianceField.EMERGENCY_CONTACT, NEXT_OF_KIN);
        assertThat(revealed.keySet()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ComplianceField.class));
    }

    @Test
    @DisplayName("a ciphertext moved between two columns of the same row fails to decrypt")
    void aCiphertextMovedBetweenColumnsFailsToDecrypt() {
        engagements.recordComplianceFile(TENANT, courierId, wholeFile(), manager(), "onboarding", "corr-1");

        // The row is the same row and the tenant is the same tenant, so a
        // binding that named only those two would happily hand the passport
        // back as though it were the licence. This is the assertion that says
        // the column is part of the associated data.
        jdbc.sql("UPDATE fulfillment.couriers SET protected_driving_licence = protected_passport"
                        + " WHERE tenant_id = :tenantId AND id = :courierId")
                .param("tenantId", TENANT)
                .param("courierId", courierId)
                .update();

        assertThat(catchThrowable(
                        () -> engagements.revealComplianceFile(TENANT, courierId, "dispute", manager(), "corr-3")))
                .isInstanceOf(FieldProtection.ProtectionIntegrityException.class);
    }

    @Test
    @DisplayName("a sensitive field is protected under the sensitive class, not the ordinary one")
    void aSensitiveFieldIsProtectedUnderTheSensitiveClass() {
        engagements.recordComplianceFile(TENANT, courierId, wholeFile(), manager(), "onboarding", "corr-1");

        // The key is per tenant and per class exactly so that a passport is not
        // filed beside a plate. If every field were protected under one class
        // this assertion would still pass for the plate and fail for the
        // passport, which is why both directions are checked.
        assertThat(classOf(courierId, ComplianceField.PASSPORT)).isEqualTo(DataClass.PERSONAL_SENSITIVE);
        assertThat(classOf(courierId, ComplianceField.PINFL)).isEqualTo(DataClass.PERSONAL_SENSITIVE);
        assertThat(classOf(courierId, ComplianceField.VEHICLE_PLATE)).isEqualTo(DataClass.PERSONAL);
        assertThat(classOf(courierId, ComplianceField.NOTES)).isEqualTo(DataClass.PERSONAL);
    }

    // --------------------------------------------------------------- the reveal

    @Test
    @DisplayName("a reveal writes one security audit fact carrying the purpose and the field names only")
    void aRevealWritesOneSecurityAuditFactCarryingThePurposeAndFieldNamesOnly() {
        engagements.recordComplianceFile(TENANT, courierId, wholeFile(), manager(), "onboarding", "corr-1");
        audit.facts.clear();

        engagements.revealComplianceFile(
                TENANT, courierId, "a traffic police enquiry about plate 01 A 123 BC", manager(), "corr-9");

        List<AuditFact> reveals = audit.of("courier.compliance.revealed");
        assertThat(reveals).hasSize(1);
        AuditFact fact = reveals.get(0);
        assertThat(fact.auditClass()).isEqualTo(AuditClass.SECURITY);
        assertThat(fact.capabilityUsed()).isEqualTo("courier.pii.reveal");
        assertThat(fact.correlationId()).isEqualTo("corr-9");
        assertThat(fact.targetId()).isEqualTo(courierId);
        assertThat(fact.reason()).isEqualTo("a traffic police enquiry about plate 01 A 123 BC");
        assertThat(String.valueOf(fact.changeDocument().get("complianceFieldsRevealed")))
                .contains("PASSPORT")
                .contains("PINFL");

        // The purpose is the operator's own sentence and is recorded verbatim;
        // everything the platform itself writes into the document is a field
        // name. No value of any field may appear anywhere in it.
        String document = fact.changeDocument().toString();
        for (String plaintext : wholeFile().recorded().values()) {
            assertThat(document)
                    .as("the change document must never carry a revealed value")
                    .doesNotContain(plaintext);
        }
    }

    @Test
    @DisplayName("a reveal of a file with three fields answers three, not nine nulls")
    void aRevealOfAPartialFileAnswersOnlyWhatIsOnFile() {
        engagements.recordComplianceFile(
                TENANT,
                courierId,
                new CourierEngagementService.ComplianceFile(
                        Map.of(ComplianceField.PASSPORT, PASSPORT, ComplianceField.PINFL, PINFL),
                        Set.of(),
                        "PETROL",
                        null),
                manager(),
                "what the rider brought with him",
                "corr-1");

        Map<ComplianceField, String> revealed =
                engagements.revealComplianceFile(TENANT, courierId, "audit", manager(), "corr-2");

        assertThat(revealed).containsOnlyKeys(ComplianceField.PASSPORT, ComplianceField.PINFL);
    }

    // ------------------------------------------------------------ the list read

    @Test
    @DisplayName("the ПИНФЛ is absent from the roster read, from the detail read, and from their types")
    void thePinflIsAbsentFromEveryReadButTheReveal() {
        engagements.recordComplianceFile(TENANT, courierId, wholeFile(), manager(), "onboarding", "corr-1");

        // The roster carries a reference, a type and a standing, and nothing a
        // reveal exists for. Rendered through toString() rather than field by
        // field: a future component carrying the number would be caught by this
        // assertion and would not be caught by one naming today's components.
        String rosterRendering = rosterQuery.roster(TENANT).toString();
        assertThat(rosterRendering).contains("K-001");
        for (String plaintext : wholeFile().recorded().values()) {
            assertThat(rosterRendering).doesNotContain(plaintext);
        }

        CourierRosterQueryService.CourierDetail detail =
                rosterQuery.detail(TENANT, courierId).orElseThrow();
        assertThat(detail.toString()).doesNotContain(PINFL).doesNotContain(PASSPORT);

        // The detail pane says the field exists. That is the whole difference
        // between "chase the missing licence" and "read the licence", and it is
        // the reason presence is not a reveal.
        assertThat(detail.compliance().onFile()).contains(ComplianceField.PINFL);
        assertThat(detail.compliance().vehicleFuelType()).isEqualTo("PETROL");

        // And the projection types themselves have no component that could ever
        // hold one. The store's own row is the last line of defence: a screen
        // cannot render what the query never selected.
        assertThat(componentNames(JdbcCourierStore.CourierRosterRow.class))
                .as("the roster row must name no document")
                .doesNotContain("pinfl", "passport", "drivinglicence", "address");
        assertThat(componentNames(JdbcCourierStore.ComplianceSummaryRow.class))
                .as("the summary carries presence, never content")
                .containsExactlyInAnyOrder(
                        "onfile", "vehiclefueltype", "photomediaid", "complianceupdatedat", "updatedby");
    }

    // ------------------------------------------------------------ partial writes

    @Test
    @DisplayName("a write that names one field leaves the other eight exactly as they were")
    void aPartialWriteLeavesEveryOtherFieldAlone() {
        engagements.recordComplianceFile(TENANT, courierId, wholeFile(), manager(), "onboarding", "corr-1");

        engagements.recordComplianceFile(
                TENANT,
                courierId,
                new CourierEngagementService.ComplianceFile(
                        Map.of(ComplianceField.VEHICLE_PLATE, "01 B 999 XX"), Set.of(), null, null),
                manager(),
                "he changed the scooter",
                "corr-2");

        Map<ComplianceField, String> revealed =
                engagements.revealComplianceFile(TENANT, courierId, "audit", manager(), "corr-3");

        assertThat(revealed).containsEntry(ComplianceField.VEHICLE_PLATE, "01 B 999 XX");
        // The passport is the assertion that matters: nothing in the second call
        // mentioned it, and a whole-row replace would have erased it.
        assertThat(revealed).containsEntry(ComplianceField.PASSPORT, PASSPORT);
        assertThat(revealed.keySet()).hasSize(ComplianceField.values().length);
    }

    @Test
    @DisplayName("a field is removed only when the request names it in clear")
    void clearingIsExplicit() {
        engagements.recordComplianceFile(TENANT, courierId, wholeFile(), manager(), "onboarding", "corr-1");

        engagements.recordComplianceFile(
                TENANT,
                courierId,
                new CourierEngagementService.ComplianceFile(Map.of(), EnumSet.of(ComplianceField.NOTES), null, null),
                manager(),
                "the remark no longer applies",
                "corr-2");

        assertThat(engagements
                        .revealComplianceFile(TENANT, courierId, "audit", manager(), "corr-3")
                        .keySet())
                .doesNotContain(ComplianceField.NOTES)
                .contains(ComplianceField.PASSPORT);
        assertThat(rawColumn(courierId, ComplianceField.NOTES.column())).isNull();
    }

    @Test
    @DisplayName("a call that records nothing and clears nothing is refused rather than stamping a review")
    void anEmptyWriteIsRefused() {
        Throwable refusal = catchThrowable(() -> engagements.recordComplianceFile(
                TENANT, courierId, CourierEngagementService.ComplianceFile.empty(), manager(), "nothing", "corr-1"));

        assertThat(refusal).isInstanceOf(ApiException.class);
        // And the provenance is untouched, so the file does not read afterwards
        // as though somebody had reviewed it.
        assertThat(store.findComplianceSummary(TENANT, courierId).orElseThrow().complianceUpdatedAt())
                .isNull();
    }

    @Test
    @DisplayName("another tenant's courier is not found, rather than found and refused")
    void aComplianceFileIsNeverReachableAcrossTenants() {
        engagements.recordComplianceFile(TENANT, courierId, wholeFile(), manager(), "onboarding", "corr-1");

        assertThat(catchThrowable(
                        () -> engagements.revealComplianceFile(OTHER_TENANT, courierId, "curiosity", manager(), "x")))
                .isInstanceOf(ApiException.class);
        assertThat(store.readComplianceFile(OTHER_TENANT, courierId)).isEmpty();
        assertThat(rosterQuery.detail(OTHER_TENANT, courierId)).isEmpty();
    }

    // -------------------------------------------------- groups and branch bindings

    @Test
    @DisplayName("a courier joins a group idempotently and leaves it once")
    void groupMembershipIsIdempotentToJoinAndSingleToLeave() {
        UUID nightShift = roster.createGroup(TENANT, "NIGHT", "Ночная смена", manager(), "planning the rota", "corr-g");

        roster.addToGroup(TENANT, nightShift, courierId, manager(), "he asked for nights", "corr-g1");
        roster.addToGroup(TENANT, nightShift, courierId, manager(), "a retry of the same request", "corr-g1");

        assertThat(store.groupsOf(TENANT, courierId)).hasSize(1);
        // A retry is one membership and one decision, so it is also one audit
        // fact: two would read afterwards as two managers agreeing.
        assertThat(audit.of("courier.group.joined")).hasSize(1);
        assertThat(store.listGroups(TENANT)).singleElement().satisfies(group -> {
            assertThat(group.code()).isEqualTo("NIGHT");
            assertThat(group.memberCount()).isEqualTo(1);
        });

        roster.removeFromGroup(TENANT, nightShift, courierId, manager(), "he moved to days", "corr-g2");
        assertThat(store.groupsOf(TENANT, courierId)).isEmpty();
        assertThat(catchThrowable(
                        () -> roster.removeFromGroup(TENANT, nightShift, courierId, manager(), "again", "corr-g3")))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("binding a second branch as primary stands the first one down")
    void atMostOneBranchIsPrimary() {
        roster.bindToBranch(TENANT, courierId, brandId, locationId, true, manager(), "his home branch", "corr-b1");
        roster.bindToBranch(TENANT, courierId, brandId, secondLocationId, true, manager(), "he moved", "corr-b2");

        assertThat(store.bindingsOf(TENANT, courierId))
                .hasSize(2)
                .filteredOn(JdbcCourierStore.BranchBindingRow::primary)
                .singleElement()
                .satisfies(binding -> assertThat(binding.locationId()).isEqualTo(secondLocationId));

        roster.unbindFromBranch(TENANT, courierId, locationId, manager(), "he never rides there", "corr-b3");
        assertThat(store.bindingsOf(TENANT, courierId)).hasSize(1);
    }

    @Test
    @DisplayName("a courier group belongs to its tenant and nobody else's courier may join it")
    void groupsAndBindingsAreTenantScoped() {
        UUID mine = roster.createGroup(TENANT, "NIGHT", "Ночная смена", manager(), "planning", "corr-g");
        UUID theirCourier = registerCourier(OTHER_TENANT, "K-900", "keycloak-k900");

        // The tenant predicate is on the membership's own key, so the database
        // refuses the pair before any service check could have missed it.
        assertThat(catchThrowable(
                        () -> roster.addToGroup(OTHER_TENANT, mine, theirCourier, manager(), "poaching", "corr-g4")))
                .isNotNull();
        assertThat(store.listGroups(OTHER_TENANT)).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private CourierEngagementService.ComplianceFile wholeFile() {
        Map<ComplianceField, String> recorded = new EnumMap<>(ComplianceField.class);
        recorded.put(ComplianceField.PASSPORT, PASSPORT);
        recorded.put(ComplianceField.PINFL, PINFL);
        recorded.put(ComplianceField.DRIVING_LICENCE, LICENCE);
        recorded.put(ComplianceField.VEHICLE_REGISTRATION, "SRC-4451920");
        recorded.put(ComplianceField.VEHICLE_PLATE, PLATE);
        recorded.put(ComplianceField.ADDRESS, HOME);
        recorded.put(ComplianceField.EMERGENCY_CONTACT, NEXT_OF_KIN);
        recorded.put(ComplianceField.REFERRAL, "brought in by Sardor");
        recorded.put(ComplianceField.NOTES, "prefers the northern zone");
        return new CourierEngagementService.ComplianceFile(recorded, Set.of(), "PETROL", null);
    }

    private UUID registerCourier(UUID tenantId, String reference, String subject) {
        return engagements
                .register(new CourierEngagementService.NewCourier(
                        tenantId,
                        courierTypeIdFor(tenantId),
                        subject,
                        reference,
                        "Alisher Karimov",
                        LocalDate.ofInstant(NOON, ZoneOffset.UTC),
                        manager(),
                        "onboarding a rider",
                        "corr-0"))
                .courierId();
    }

    private @org.jspecify.annotations.Nullable String rawColumn(UUID courier, String column) {
        return jdbc.sql("SELECT " + column + " FROM fulfillment.couriers"
                        + " WHERE tenant_id = :tenantId AND id = :courierId")
                .param("tenantId", TENANT)
                .param("courierId", courier)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    /** The class the fake recorded in the key id, which is where the real stack puts it too. */
    private DataClass classOf(UUID courier, ComplianceField field) {
        String stored = rawColumn(courier, field.column());
        return DataClass.valueOf(ProtectedValue.deserialize(java.util.Objects.requireNonNull(stored))
                .keyId());
    }

    private static List<String> componentNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static ActorRef manager() {
        return ActorRef.user("keycloak-manager", "Manager");
    }

    private void seedTenancy(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "courier-file-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandIdFor(tenantId))
                .param("tenantId", tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationIdFor(tenantId, "CENTRE"))
                .param("tenantId", tenantId)
                .param("brandId", brandIdFor(tenantId))
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'NORTH', 'north', 'North', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationIdFor(tenantId, "NORTH"))
                .param("tenantId", tenantId)
                .param("brandId", brandIdFor(tenantId))
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :tenantId, 'SCOOTER', 'Scooter', 'SCOOTER')
                """)
                .param("id", courierTypeIdFor(tenantId))
                .param("tenantId", tenantId)
                .update();

        if (tenantId.equals(TENANT)) {
            brandId = brandIdFor(tenantId);
            locationId = locationIdFor(tenantId, "CENTRE");
            secondLocationId = locationIdFor(tenantId, "NORTH");
        }
    }

    private static UUID brandIdFor(UUID tenantId) {
        return deriveId(tenantId, "brand");
    }

    private static UUID locationIdFor(UUID tenantId, String code) {
        return deriveId(tenantId, "location:" + code);
    }

    private static UUID courierTypeIdFor(UUID tenantId) {
        return deriveId(tenantId, "courier-type");
    }

    private static UUID deriveId(UUID tenantId, String seed) {
        return UUID.nameUUIDFromBytes((tenantId + ":" + seed).getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------------- fakes

    /**
     * Reversible, and bound to the exact column it was written for.
     *
     * <p>The reversal makes an accidental plaintext visible as a plaintext in
     * the assertions above. The binding is the part that matters: the canonical
     * {@code RecordRef} is carried in the associated-data field and checked on
     * reveal, so a ciphertext moved to another column, row or tenant throws the
     * same {@link FieldProtection.ProtectionIntegrityException} the real AEAD
     * stack raises. A fake that ignored the ref would let a caller that passed
     * the wrong one pass this suite.
     */
    private static final class ColumnBoundProtection implements FieldProtection {

        @Override
        public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext) {
            byte[] reversed = new StringBuilder(plaintext).reverse().toString().getBytes(StandardCharsets.UTF_8);
            return new ProtectedValue(
                    dataClass.name(),
                    "TEST",
                    (tenantId + "|" + record.canonical()).getBytes(StandardCharsets.UTF_8),
                    reversed,
                    1);
        }

        @Override
        public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
            String expected = tenantId + "|" + record.canonical();
            String actual = new String(value.nonce(), StandardCharsets.UTF_8);
            if (!expected.equals(actual)) {
                throw new ProtectionIntegrityException(
                        "This ciphertext does not belong where it was found", new IllegalStateException(expected));
            }
            return new StringBuilder(new String(value.ciphertext(), StandardCharsets.UTF_8))
                    .reverse()
                    .toString();
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            return Integer.toHexString((tenantId + lookupDomain + normalizedValue).hashCode());
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }

        private List<AuditFact> of(String actionCode) {
            return facts.stream()
                    .filter(fact -> fact.actionCode().equals(actionCode))
                    .toList();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Nothing here turns on the policy; the defaults are enough to verify with. */
    private static final class DefaultPolicies implements PolicyResolver {

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            return Optional.of((ResolvedPolicy<P>) new ResolvedPolicy<>(
                    key.code(),
                    UUID.nameUUIDFromBytes("courier-policy".getBytes(StandardCharsets.UTF_8)),
                    1,
                    scope.type(),
                    "test",
                    CourierCompensationPolicy.DEFAULTS));
        }

        @Override
        public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
            return resolve(key, ResourceScope.tenant(TENANT));
        }
    }
}
