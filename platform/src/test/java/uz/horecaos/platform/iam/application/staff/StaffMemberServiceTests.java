package uz.horecaos.platform.iam.application.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.protection.FieldProtection.ProtectionIntegrityException;
import uz.horecaos.platform.iam.api.staff.StaffMemberChanged;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.EmploymentEdit;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.EndOutcome;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.MemberView;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.ProfileEdit;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.Reach;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0139: the tenant's own record of each person who works for it, against a
 * real PostgreSQL and the real envelope protection.
 *
 * <p>The names below are deliberately unlike anything else in the suite
 * (<em>Zukhra Ismoilova</em>, <em>+998 90 777 66 55</em>), so that "this value
 * appears nowhere it should not" is a search for a string that could not be
 * there by accident.
 */
class StaffMemberServiceTests {

    private static final String OWNER = "staff-owner-1";
    private static final String CHEF = "staff-chef-1";

    private static final String FIRST = "Zukhra";
    private static final String LAST = "Ismoilova";
    private static final String PHONE = "+998 90 777 66 55";
    private static final String PHONE_STORED = "+998907776655";

    private static TestDatabase.Handle db;
    private static StaffKit kit;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
        db = TestDatabase.migrated();
        kit = new StaffKit(db);
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        kit.reset();
        kit.grant(StaffKit.TENANT_A, OWNER, PlatformRole.TENANT_OWNER, "TENANT", StaffKit.TENANT_A);
    }

    // ----------------------------------------------------------- protection

    @Test
    @DisplayName("name, phone and employee number are sealed in the row, bound to it, and open only through the codec")
    void thePersonalColumnsAreCiphertextAndRoundTrip() {
        UUID id = kit.invite(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);

        var row = kit.store.find(StaffKit.TENANT_A, id).orElseThrow();
        assertThat(List.of(
                        row.protectedFirstName(), row.protectedLastName(), row.protectedPhone(), row.phoneLookupHash()))
                .as("every personal column is stored sealed, and the lookup hash is not the number")
                .allSatisfy(stored -> assertThat(stored).isNotBlank().doesNotContain(FIRST, LAST));
        assertThat(row.phoneLookupHash()).hasSize(43).doesNotContain("=");

        MemberView view = kit.members.detail(StaffKit.TENANT_A, Reach.tenant(null, null), id);
        assertThat(view.firstName()).isEqualTo(FIRST);
        assertThat(view.lastName()).isEqualTo(LAST);
        assertThat(view.phone()).isEqualTo(PHONE_STORED);
        assertThat(view.displayName()).isEqualTo(FIRST + " " + LAST);
        assertThat(view.employmentStatus()).isEqualTo("PENDING");
        assertThat(view.displayReference()).isEqualTo("S-0001");
    }

    @Test
    @DisplayName("a ciphertext copied to another member's row fails to open instead of showing that person's name")
    void aCiphertextMovedToAnotherRowFailsToOpen() {
        UUID first = kit.invite(StaffKit.TENANT_A, "mover-1", FIRST, LAST, null);
        UUID second = kit.invite(StaffKit.TENANT_A, "mover-2", "Dilnoza", "Karimova", null);
        String firstSealed =
                kit.store.find(StaffKit.TENANT_A, first).orElseThrow().protectedFirstName();
        kit.jdbc
                .sql("UPDATE iam.staff_members SET protected_first_name = :sealed WHERE id = :id")
                .param("sealed", firstSealed)
                .param("id", second)
                .update();

        var tampered = kit.store.find(StaffKit.TENANT_A, second).orElseThrow();
        assertThatThrownBy(() -> kit.codec.open(
                        StaffKit.TENANT_A, second, StaffMemberCodec.FIRST_NAME, tampered.protectedFirstName()))
                .isInstanceOf(ProtectionIntegrityException.class);
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "mover-2"))
                .as("the audit log and the order detail must still render; the member shows by reference")
                .isEqualTo("Karimova")
                .doesNotContain(FIRST);
    }

    @Test
    @DisplayName("the lookup hash of one phone differs between tenants, so a hash cannot confirm a number across them")
    void theLookupHashIsKeyedPerTenant() {
        UUID a = kit.invite(StaffKit.TENANT_A, "shared-1", FIRST, LAST, PHONE);
        UUID b = kit.invite(StaffKit.TENANT_B, "shared-1", FIRST, LAST, PHONE);

        assertThat(kit.store.find(StaffKit.TENANT_A, a).orElseThrow().phoneLookupHash())
                .isNotEqualTo(kit.store.find(StaffKit.TENANT_B, b).orElseThrow().phoneLookupHash());
    }

    @Test
    @DisplayName("an event carries no name, phone or employee number -- only that a personal field is set")
    void noPersonalValueTravelsInAnEvent() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);
        kit.grant(StaffKit.TENANT_A, CHEF, PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.published.clear();

        kit.inTx(() -> kit.members.updateByManager(
                StaffKit.TENANT_A,
                Reach.tenant(null, null),
                id,
                kit.store.find(StaffKit.TENANT_A, id).orElseThrow().version(),
                new ProfileEdit("Gulnora", "Yusupova", "+998 91 555 44 33", "uz", List.of("uz", "ru")),
                new EmploymentEdit(null, "EMP-4471", LocalDate.parse("2026-01-12"), null),
                OWNER,
                null,
                "corr-1"));

        assertThat(kit.published).hasSize(1);
        StaffMemberChanged event = kit.published.getFirst();
        String everything = event.before() + " " + event.after() + " " + event.reason();
        assertThat(everything)
                .doesNotContain("Gulnora", "Yusupova", FIRST, LAST, "555", "777", "EMP-4471")
                .doesNotContain("998");
        assertThat(event.after()).containsEntry("firstName", "set").containsEntry("employeeNumber", "set");

        String auditDocument = kit.jdbc
                .sql(
                        "SELECT change_document::text FROM audit.audit_events WHERE action_code = 'staff.member.updated' AND correlation_id = 'corr-1'")
                .query(String.class)
                .single();
        assertThat(auditDocument)
                .as("the trail says that a phone changed and never what it changed to")
                .doesNotContain("Gulnora", "Yusupova", FIRST, LAST, "555", "777", "EMP-4471")
                .contains("[redacted]");
    }

    // ------------------------------------------------------ tenant isolation

    @Test
    @DisplayName("a subject who works in two tenants has two rows that never see each other")
    void aSubjectInTwoTenantsHasTwoRows() {
        UUID a = kit.activeMember(StaffKit.TENANT_A, "two-tenant-1", "Aziza", "Karimova", null);
        UUID b = kit.activeMember(StaffKit.TENANT_B, "two-tenant-1", "Aziz", "Karimov", null);

        assertThat(a).isNotEqualTo(b);
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "two-tenant-1")).isEqualTo("Aziza Karimova");
        assertThat(kit.directory.nameOf(StaffKit.TENANT_B, "two-tenant-1")).isEqualTo("Aziz Karimov");
        assertThat(kit.directory.memberIdOf(StaffKit.TENANT_A, "two-tenant-1")).contains(a);
        assertThat(kit.directory.memberIdOf(StaffKit.TENANT_B, "two-tenant-1")).contains(b);

        assertThatThrownBy(() -> kit.members.detail(StaffKit.TENANT_B, Reach.tenant(null, null), a))
                .as("a member id of the other tenant is simply not there")
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThat(kit.members.list(StaffKit.TENANT_B, Reach.tenant(null, null), null, null))
                .extracting(MemberView::memberId)
                .containsExactly(b);
    }

    @Test
    @DisplayName("the directory refuses a cross-tenant lookup: a subject this tenant keeps no row for answers nothing")
    void theDirectoryRefusesACrossTenantLookup() {
        kit.activeMember(StaffKit.TENANT_B, "only-in-b", "Bekzod", "Aliyev", null);
        kit.accounts.profiles.put(
                "only-in-b",
                new uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffProfile("Bekzod", "Aliyev", null, true));

        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "only-in-b"))
                .as("not a member of A, and the Keycloak fallback must not leak B's colleague into A")
                .isNull();
        assertThat(kit.directory.memberIdOf(StaffKit.TENANT_A, "only-in-b")).isEmpty();
        assertThat(kit.cards.cardsOf(
                        StaffKit.TENANT_A,
                        List.of(kit.store
                                .findBySubject(StaffKit.TENANT_B, "only-in-b")
                                .orElseThrow()
                                .id())))
                .isEmpty();
    }

    @Test
    @DisplayName(
            "a staff photo of another tenant's private asset is refused by PostgreSQL when the service is bypassed")
    void aPhotoOfAnotherTenantsAssetIsRefusedByTheDatabase() {
        UUID memberInA = kit.activeMember(StaffKit.TENANT_A, "photo-a", "Nodira", "Rahimova", null);
        UUID assetOfB = UUID.randomUUID();
        kit.jdbc
                .sql("""
                INSERT INTO media.assets (asset_id, tenant_id, owner_scope, owner_id, bucket,
                    object_key, visibility, status, declared_content_type, declared_size_bytes,
                    verified_content_type, verified_size_bytes, verified_checksum_sha256)
                VALUES (:id, :t, 'TENANT', :t, 'horecaos-media', :key, 'PRIVATE', 'AVAILABLE',
                    'image/jpeg', 10, 'image/jpeg', 10, repeat('0', 64))
                """)
                .param("id", assetOfB)
                .param("t", StaffKit.TENANT_B)
                .param("key", StaffKit.TENANT_B + "/tenant/" + assetOfB)
                .update();

        assertThatThrownBy(() -> kit.jdbc
                        .sql("UPDATE iam.staff_members SET photo_asset_id = :asset WHERE id = :id")
                        .param("asset", assetOfB)
                        .param("id", memberInA)
                        .update())
                .as("the composite reference (photo_asset_id, tenant_id) is the backstop")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ----------------------------------------------------------- references

    @Test
    @DisplayName("two concurrent invitations in one tenant get different references and both succeed")
    void concurrentInvitationsGetDifferentReferences() throws Exception {
        int invitations = 8;
        ExecutorService pool = Executors.newFixedThreadPool(invitations);
        try {
            List<Callable<UUID>> work = new ArrayList<>();
            for (int i = 0; i < invitations; i++) {
                String subject = "concurrent-" + i;
                work.add(() -> kit.invite(StaffKit.TENANT_A, subject, "Person" + subject, "Concurrent", null));
            }
            List<Future<UUID>> results = pool.invokeAll(work);
            for (Future<UUID> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        List<String> references = kit.jdbc
                .sql("SELECT display_reference FROM iam.staff_members WHERE tenant_id = :t ORDER BY display_reference")
                .param("t", StaffKit.TENANT_A)
                .query(String.class)
                .list();
        assertThat(references).hasSize(invitations).doesNotHaveDuplicates();
        assertThat(references.getFirst()).isEqualTo("S-0001");
        assertThat(references.getLast()).isEqualTo("S-0008");
    }

    @Test
    @DisplayName("a rolled-back invitation gives its reference back; a committed one never has its number reused")
    void aReferenceIsOnlyTakenByACommit() {
        assertThatThrownBy(() -> kit.tx.executeWithoutResult(status -> {
                    kit.members.registerInvited(
                            StaffKit.TENANT_A, "rolled-back", "Temp", null, null, "mgr", "corr-rollback");
                    throw new IllegalStateException("the invitation row failed");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(kit.store.findBySubject(StaffKit.TENANT_A, "rolled-back")).isEmpty();

        UUID kept = kit.invite(StaffKit.TENANT_A, "kept-1", "Kept", null, null);
        assertThat(kit.store.find(StaffKit.TENANT_A, kept).orElseThrow().displayReference())
                .as("the rolled-back allocation took its number back with it")
                .isEqualTo("S-0001");
    }

    @Test
    @DisplayName("a contact phone is not an identity: two members may hold the same one and both are found by it")
    void twoMembersMayShareAContactPhone() {
        UUID cook = kit.activeMember(StaffKit.TENANT_A, "cook-1", "Cook", "One", "+998 90 111 22 33");
        UUID newHire = kit.invite(StaffKit.TENANT_A, "new-hire-1", "New", "Hire", "+998 90 111 22 33");

        List<UUID> found = kit.jdbc
                .sql("""
                        SELECT id FROM iam.staff_members
                         WHERE tenant_id = :t
                           AND phone_lookup_hash = :hash
                        """)
                .param("t", StaffKit.TENANT_A)
                .param("hash", kit.codec.phoneHash(StaffKit.TENANT_A, "998901112233"))
                .query(UUID.class)
                .list();
        assertThat(found).containsExactlyInAnyOrder(cook, newHire);
    }

    // ----------------------------------------------------------------- reach

    /** The six people {@link #seedPeople} creates. */
    private record People(UUID m1, UUID m2, UUID m3, UUID m4, UUID m5, UUID m6) {}

    /**
     * m1 L1; m2 L2; m3 L1 and L2; m4 brand manager of B1; m5 tenant admin;
     * m6 nobody (no active job).
     */
    private People seedPeople() {
        UUID m1 = kit.activeMember(StaffKit.TENANT_A, "m1", "Alisher", "Aaa", null);
        kit.grant(StaffKit.TENANT_A, "m1", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        UUID m2 = kit.activeMember(StaffKit.TENANT_A, "m2", "Bobur", "Bbb", null);
        kit.grant(StaffKit.TENANT_A, "m2", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_2);
        UUID m3 = kit.activeMember(StaffKit.TENANT_A, "m3", "Chingiz", "Ccc", null);
        kit.grant(StaffKit.TENANT_A, "m3", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, "m3", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_2);
        UUID m4 = kit.activeMember(StaffKit.TENANT_A, "m4", "Dilshod", "Ddd", null);
        kit.grant(StaffKit.TENANT_A, "m4", PlatformRole.BRAND_MANAGER, "BRAND", StaffKit.BRAND_1);
        UUID m5 = kit.activeMember(StaffKit.TENANT_A, "m5", "Eldor", "Eee", null);
        kit.grant(StaffKit.TENANT_A, "m5", PlatformRole.TENANT_ADMIN, "TENANT", StaffKit.TENANT_A);
        UUID m6 = kit.activeMember(StaffKit.TENANT_A, "m6", "Farrukh", "Fff", null);
        return new People(m1, m2, m3, m4, m5, m6);
    }

    private static List<String> names(List<MemberView> views) {
        return views.stream().map(MemberView::lastName).collect(Collectors.toList());
    }

    @Test
    @DisplayName("each level sees only the people with an active job at or below it; no job means tenant level only")
    void eachLevelSeesItsOwnPeople() {
        seedPeople();

        assertThat(names(kit.members.list(StaffKit.TENANT_A, Reach.tenant(null, null), null, null)))
                .containsExactly("Aaa", "Bbb", "Ccc", "Ddd", "Eee", "Fff");
        assertThat(names(kit.members.list(StaffKit.TENANT_A, Reach.brand(StaffKit.BRAND_1, null), null, null)))
                .as("the brand manager of B1 and everyone with a job inside B1; not the tenant admin, not nobody")
                .containsExactly("Aaa", "Bbb", "Ccc", "Ddd");
        assertThat(names(kit.members.list(StaffKit.TENANT_A, Reach.brand(StaffKit.BRAND_2, null), null, null)))
                .isEmpty();
        assertThat(names(kit.members.list(
                        StaffKit.TENANT_A, Reach.location(StaffKit.BRAND_1, StaffKit.LOCATION_1), null, null)))
                .as("a branch manager's list is the people of that branch, and no one else")
                .containsExactly("Aaa", "Ccc");
        assertThat(names(kit.members.list(
                        StaffKit.TENANT_A, Reach.location(StaffKit.BRAND_1, StaffKit.LOCATION_2), null, null)))
                .containsExactly("Bbb", "Ccc");
    }

    @Test
    @DisplayName("the brand and location filters on the tenant route narrow a result and never widen a scope")
    void filtersNarrowAndNeverWiden() {
        seedPeople();

        assertThat(names(kit.members.list(StaffKit.TENANT_A, Reach.tenant(StaffKit.BRAND_1, null), null, null)))
                .containsExactly("Aaa", "Bbb", "Ccc", "Ddd");
        assertThat(names(kit.members.list(StaffKit.TENANT_A, Reach.tenant(null, StaffKit.LOCATION_2), null, null)))
                .containsExactly("Bbb", "Ccc");
        assertThat(names(kit.members.list(
                        StaffKit.TENANT_A, Reach.tenant(StaffKit.BRAND_2, StaffKit.LOCATION_1), null, null)))
                .as("location 1 is not in brand 2")
                .isEmpty();
        assertThat(names(kit.members.list(
                        StaffKit.TENANT_A, Reach.brand(StaffKit.BRAND_1, StaffKit.LOCATION_1), null, null)))
                .containsExactly("Aaa", "Ccc");
    }

    @Test
    @DisplayName("a branch answers \"no such member\" for a person of a sibling branch, the answer an unknown id gets")
    void aBranchIsNoExistenceOracleForItsSiblings() {
        People people = seedPeople();
        Reach branch1 = Reach.location(StaffKit.BRAND_1, StaffKit.LOCATION_1);

        assertThat(kit.members.detail(StaffKit.TENANT_A, branch1, people.m1()).lastName())
                .isEqualTo("Aaa");
        for (UUID invisible : List.of(people.m2(), people.m4(), people.m5(), people.m6(), UUID.randomUUID())) {
            assertThatThrownBy(() -> kit.members.detail(StaffKit.TENANT_A, branch1, invisible))
                    .isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                        assertThat(e.getMessage()).isEqualTo("No such staff member");
                    });
        }
    }

    @Test
    @DisplayName("a branch changes only people whose every active job is inside it")
    void aBranchChangesOnlyItsOwnPeople() {
        People people = seedPeople();
        Reach branch1 = Reach.location(StaffKit.BRAND_1, StaffKit.LOCATION_1);
        ProfileEdit edit = new ProfileEdit("Alisher", "Renamed", null, null, null);
        EmploymentEdit employment = new EmploymentEdit(null, null, null, null);
        int version =
                kit.store.find(StaffKit.TENANT_A, people.m1()).orElseThrow().version();

        MemberView changed = kit.inTx(() -> kit.members.updateByManager(
                StaffKit.TENANT_A, branch1, people.m1(), version, edit, employment, OWNER, null, "corr-1"));
        assertThat(changed.lastName()).isEqualTo("Renamed");

        assertThatThrownBy(() -> kit.inTx(() -> kit.members.updateByManager(
                        StaffKit.TENANT_A,
                        branch1,
                        people.m3(),
                        kit.store
                                .find(StaffKit.TENANT_A, people.m3())
                                .orElseThrow()
                                .version(),
                        edit,
                        employment,
                        OWNER,
                        null,
                        "corr-2")))
                .as("m3 also works at location 2: not branch 1's to change")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CAPABILITY));
        assertThatThrownBy(() -> kit.inTx(() -> kit.members.updateByManager(
                        StaffKit.TENANT_A,
                        branch1,
                        people.m2(),
                        kit.store
                                .find(StaffKit.TENANT_A, people.m2())
                                .orElseThrow()
                                .version(),
                        edit,
                        employment,
                        OWNER,
                        null,
                        "corr-3")))
                .as("m2 is a person of the sibling branch: no such member")
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ---------------------------------------------------------------- edits

    private MemberView edit(UUID id, ProfileEdit profile, EmploymentEdit employment) {
        int version = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().version();
        return kit.inTx(() -> kit.members.updateByManager(
                StaffKit.TENANT_A,
                Reach.tenant(null, null),
                id,
                version,
                profile,
                employment,
                OWNER,
                null,
                "corr-edit"));
    }

    @Test
    @DisplayName("an edit moves the version once; an edit that changes nothing moves nothing and publishes nothing")
    void aNoOpEditMovesNothing() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);
        kit.published.clear();
        ProfileEdit same = new ProfileEdit(FIRST, LAST, PHONE, null, List.of());
        EmploymentEdit none = new EmploymentEdit(null, null, null, null);

        MemberView unchanged = edit(id, same, none);

        assertThat(unchanged.version()).isEqualTo(2);
        assertThat(kit.published).isEmpty();

        MemberView renamed = edit(id, new ProfileEdit(FIRST, "Newname", PHONE, null, List.of()), none);
        assertThat(renamed.version()).isEqualTo(3);
        assertThat(kit.published).hasSize(1);
    }

    @Test
    @DisplayName("a stale version is refused with both versions reported")
    void aStaleVersionIsRefused() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);

        assertThatThrownBy(() -> kit.inTx(() -> kit.members.updateByManager(
                        StaffKit.TENANT_A,
                        Reach.tenant(null, null),
                        id,
                        1,
                        new ProfileEdit(FIRST, "Other", null, null, null),
                        new EmploymentEdit(null, null, null, null),
                        OWNER,
                        null,
                        "corr-stale")))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                    assertThat(e.properties())
                            .containsEntry("expectedVersion", 1L)
                            .containsEntry("currentVersion", 2L);
                });
    }

    @Test
    @DisplayName("the first name is required before a record will save, and a phone that is not a number is refused")
    void validationRefusesWhatCannotBeSaved() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);
        EmploymentEdit none = new EmploymentEdit(null, null, null, null);

        assertThatThrownBy(() -> edit(id, new ProfileEdit("  ", LAST, PHONE, null, null), none))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.properties()).containsEntry("field", "firstName");
                });
        assertThatThrownBy(() -> edit(id, new ProfileEdit(FIRST, LAST, "call me maybe", null, null), none))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.properties()).containsEntry("field", "phone"));
        assertThatThrownBy(() -> edit(id, new ProfileEdit(FIRST, LAST, PHONE, "klingon", null), none))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.properties()).containsEntry("field", "uiLocale"));
        assertThatThrownBy(() -> edit(id, new ProfileEdit(FIRST, LAST, PHONE, null, List.of("ru", "12")), none))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.properties()).containsEntry("field", "spokenLanguages"));
    }

    @Test
    @DisplayName("an employee number is unique inside a tenant and free in another")
    void anEmployeeNumberIsUniqueWithinOneTenant() {
        UUID first = kit.activeMember(StaffKit.TENANT_A, "emp-1", "Emp", "One", null);
        UUID second = kit.activeMember(StaffKit.TENANT_A, "emp-2", "Emp", "Two", null);
        UUID elsewhere = kit.activeMember(StaffKit.TENANT_B, "emp-3", "Emp", "Three", null);
        ProfileEdit profile = new ProfileEdit("Emp", "X", null, null, null);

        edit(first, profile, new EmploymentEdit(null, "A-17", null, null));
        assertThatThrownBy(() -> edit(second, profile, new EmploymentEdit(null, "a-17", null, null)))
                .as("the hash is taken over the upper-cased number, so a case change is not a new number")
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT);
                    assertThat(e.properties()).containsEntry("field", "employeeNumber");
                });

        int version = kit.store.find(StaffKit.TENANT_B, elsewhere).orElseThrow().version();
        MemberView other = kit.inTx(() -> kit.members.updateByManager(
                StaffKit.TENANT_B,
                Reach.tenant(null, null),
                elsewhere,
                version,
                profile,
                new EmploymentEdit(null, "A-17", null, null),
                OWNER,
                null,
                "corr-b"));
        assertThat(other.employeeNumber()).isEqualTo("A-17");
    }

    @Test
    @DisplayName(
            "status moves between ACTIVE and ON_LEAVE; ENDED is only reached by ending employment; PENDING is not editable")
    void statusRules() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);
        ProfileEdit profile = new ProfileEdit(FIRST, LAST, null, null, null);

        assertThat(edit(id, profile, new EmploymentEdit("ON_LEAVE", null, null, null))
                        .employmentStatus())
                .isEqualTo("ON_LEAVE");
        assertThat(edit(id, profile, new EmploymentEdit("ACTIVE", null, null, null))
                        .employmentStatus())
                .isEqualTo("ACTIVE");
        assertThatThrownBy(() -> edit(id, profile, new EmploymentEdit("ENDED", null, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> edit(
                        id,
                        profile,
                        new EmploymentEdit(null, null, LocalDate.parse("2026-05-01"), LocalDate.parse("2026-04-01"))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.properties()).containsEntry("field", "employedUntil"));

        UUID pending = kit.invite(StaffKit.TENANT_A, "pending-1", "Pen", "Ding", null);
        assertThatThrownBy(() -> edit(
                        pending,
                        new ProfileEdit("Pen", "Ding", null, null, null),
                        new EmploymentEdit("ACTIVE", null, null, null)))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE));
    }

    // ------------------------------------------------------------- self edit

    @Test
    @DisplayName("a person edits their own name and phone and nothing about their employment")
    void aPersonEditsOnlyTheirOwnProfile() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);
        kit.inTx(() -> kit.members.updateByManager(
                StaffKit.TENANT_A,
                Reach.tenant(null, null),
                id,
                2,
                new ProfileEdit(FIRST, LAST, PHONE, null, null),
                new EmploymentEdit("ON_LEAVE", "EMP-9", LocalDate.parse("2026-02-02"), null),
                OWNER,
                null,
                "corr-seed"));
        int version = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().version();

        MemberView after = kit.inTx(() -> kit.members.updateSelf(
                StaffKit.TENANT_A,
                CHEF,
                version,
                new ProfileEdit("Zukhra", "Ismoilova-Karimova", "+998 99 000 11 22", "uz", List.of("uz", "ru", "en")),
                false,
                "corr-self"));

        assertThat(after.lastName()).isEqualTo("Ismoilova-Karimova");
        assertThat(after.phone()).isEqualTo("+998990001122");
        assertThat(after.uiLocale())
                .as("a bare uz is read as uz-Latn and the member reads back the tag")
                .isEqualTo("uz-Latn");
        assertThat(after.spokenLanguages()).containsExactly("uz", "ru", "en");
        assertThat(after.employmentStatus())
                .as("a person never changes their own employment")
                .isEqualTo("ON_LEAVE");
        assertThat(after.employeeNumber()).isEqualTo("EMP-9");
        assertThat(after.employedFrom()).isEqualTo(LocalDate.parse("2026-02-02"));
    }

    @Test
    @DisplayName("self-service resolves the row from the subject and the tenant: another tenant's account has none")
    void selfServiceCannotReachAnotherTenantsRow() {
        kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);

        assertThat(kit.members.self(StaffKit.TENANT_A, CHEF).firstName()).isEqualTo(FIRST);
        assertThatThrownBy(() -> kit.members.self(StaffKit.TENANT_B, CHEF))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> kit.members.self(StaffKit.TENANT_A, "someone-else"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("a self-edit is visible through the directory at once, not after the cache's ten minutes")
    void aSelfEditIsVisibleAtOnce() {
        kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, CHEF)).isEqualTo(FIRST + " " + LAST);
        assertThat(kit.cache.get(StaffKit.TENANT_A, CHEF)).as("warmed").isEqualTo(FIRST + " " + LAST);

        kit.inTx(() -> kit.members.updateSelf(
                StaffKit.TENANT_A,
                CHEF,
                2,
                new ProfileEdit("Zukhra", "Renamed", null, null, null),
                false,
                "corr-self"));

        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, CHEF))
                .as("the write evicted the (tenant, subject) entry once its transaction committed")
                .isEqualTo("Zukhra Renamed");
    }

    @Test
    @DisplayName(
            "the cache is evicted only after the commit: a reader inside the open transaction still sees the old name")
    void evictionWaitsForTheCommit() {
        kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);
        kit.directory.nameOf(StaffKit.TENANT_A, CHEF);

        kit.tx.executeWithoutResult(status -> {
            kit.members.updateSelf(
                    StaffKit.TENANT_A,
                    CHEF,
                    2,
                    new ProfileEdit("Zukhra", "Inflight", null, null, null),
                    false,
                    "corr-x");
            assertThat(kit.cache.get(StaffKit.TENANT_A, CHEF))
                    .as("evicting before the commit would let a concurrent reader re-cache the pre-commit row")
                    .isEqualTo(FIRST + " " + LAST);
        });

        assertThat(kit.cache.get(StaffKit.TENANT_A, CHEF)).isNull();
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, CHEF)).isEqualTo("Zukhra Inflight");
    }

    @Test
    @DisplayName("the same cache key in another tenant is untouched by an eviction")
    void theCacheKeyCarriesTheTenant() {
        kit.activeMember(StaffKit.TENANT_A, "shared-cache", "Aziza", "A", null);
        kit.activeMember(StaffKit.TENANT_B, "shared-cache", "Aziz", "B", null);
        kit.directory.nameOf(StaffKit.TENANT_A, "shared-cache");
        kit.directory.nameOf(StaffKit.TENANT_B, "shared-cache");

        kit.cache.evict(StaffKit.TENANT_A, "shared-cache");

        assertThat(kit.cache.get(StaffKit.TENANT_A, "shared-cache")).isNull();
        assertThat(kit.cache.get(StaffKit.TENANT_B, "shared-cache")).isEqualTo("Aziz B");
    }

    @Test
    @DisplayName("a batch of subjects costs one directory call, and a subject with no answer is absent, not null")
    void namesOfAnswersABatch() {
        kit.activeMember(StaffKit.TENANT_A, "n1", "One", "A", null);
        kit.activeMember(StaffKit.TENANT_A, "n2", "Two", "B", null);

        assertThat(kit.directory.namesOf(StaffKit.TENANT_A, List.of("n1", "n2", "nobody")))
                .containsOnlyKeys("n1", "n2")
                .containsEntry("n1", "One A")
                .containsEntry("n2", "Two B");
    }

    // ------------------------------------------------------ the registry flows

    @Test
    @DisplayName("acceptance moves PENDING to ACTIVE with the typed name; an ended member is not resurrected")
    void acceptancePromotesPendingAndOnlyPending() {
        UUID pending = kit.invite(StaffKit.TENANT_A, "acceptor", "Invited", "Name", "+998 90 123 45 67");
        kit.tx.executeWithoutResult(
                status -> kit.members.activate(StaffKit.TENANT_A, "acceptor", "Typed", "ByThem", "corr-accept"));

        MemberView accepted = kit.members.detail(StaffKit.TENANT_A, Reach.tenant(null, null), pending);
        assertThat(accepted.employmentStatus()).isEqualTo("ACTIVE");
        assertThat(accepted.firstName()).isEqualTo("Typed");
        assertThat(accepted.lastName()).isEqualTo("ByThem");
        assertThat(accepted.phone()).as("the contact phone survives acceptance").isEqualTo("+998901234567");

        kit.jdbc
                .sql("UPDATE iam.staff_members SET employment_status = 'ENDED', employed_until = :d WHERE id = :id")
                .param("d", LocalDate.parse("2026-09-01"))
                .param("id", pending)
                .update();
        kit.tx.executeWithoutResult(
                status -> kit.members.activate(StaffKit.TENANT_A, "acceptor", "Stale", "Link", "corr-stale-link"));
        assertThat(kit.members
                        .detail(StaffKit.TENANT_A, Reach.tenant(null, null), pending)
                        .firstName())
                .isEqualTo("Typed");
        assertThat(kit.store.find(StaffKit.TENANT_A, pending).orElseThrow().employmentStatus())
                .isEqualTo("ENDED");
    }

    @Test
    @DisplayName("an owner who is never invited gets an ACTIVE row at acceptance")
    void anOwnerGetsAnActiveRowAtAcceptance() {
        kit.tx.executeWithoutResult(
                status -> kit.members.activate(StaffKit.TENANT_A, "owner-new", "Oybek", "Owner", "corr-owner"));

        var row = kit.store.findBySubject(StaffKit.TENANT_A, "owner-new").orElseThrow();
        assertThat(row.employmentStatus()).isEqualTo("ACTIVE");
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "owner-new")).isEqualTo("Oybek Owner");
    }

    // -------------------------------------------------------- end employment

    private UUID memberWithTwoJobs() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, "leaver", "Leaving", "Employee", null);
        kit.grant(StaffKit.TENANT_A, "leaver", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, "leaver", PlatformRole.BRAND_MANAGER, "BRAND", StaffKit.BRAND_1);
        return id;
    }

    private EndOutcome end(UUID id, String actor) {
        int version = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().version();
        return kit.members.endEmployment(StaffKit.TENANT_A, id, version, null, "Resigned", actor, "corr-end");
    }

    @Test
    @DisplayName(
            "ending employment ends access in the same act: ENDED with a date, every job revoked, each revoke audited")
    void endingEmploymentRevokesEveryJob() {
        UUID id = memberWithTwoJobs();

        EndOutcome outcome = end(id, OWNER);

        assertThat(outcome.revokedGrants()).isEqualTo(2);
        assertThat(outcome.remainingGrants()).isZero();
        assertThat(outcome.member().employmentStatus()).isEqualTo("ENDED");
        assertThat(outcome.member().employedUntil()).isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(outcome.member().accessDrift()).isFalse();
        assertThat(kit.jdbc
                        .sql("SELECT count(*) FROM iam.grants WHERE principal_subject = 'leaver' AND status = 'ACTIVE'")
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(kit.jdbc
                        .sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'iam.grant.revoked'")
                        .query(Integer.class)
                        .single())
                .as("one audited revoke per job, never one transaction")
                .isEqualTo(2);
        assertThat(kit.jdbc
                        .sql(
                                "SELECT count(*) FROM audit.audit_events WHERE action_code = 'staff.member.employment_ended'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, "leaver"))
                .as("their history still names them")
                .isEqualTo("Leaving Employee");
    }

    @Test
    @DisplayName(
            "a revoke that fails part-way leaves the person ENDED with the drift flag up, and a retry completes it")
    void aMidWayFailureLeavesDriftAndARetryCompletesIt() {
        UUID id = memberWithTwoJobs();
        kit.revoking.reset();
        kit.revoking.failOn = 2;

        assertThatThrownBy(() -> end(id, OWNER)).isInstanceOf(IllegalStateException.class);

        MemberView half = kit.members.detail(StaffKit.TENANT_A, Reach.tenant(null, null), id);
        assertThat(half.employmentStatus()).isEqualTo("ENDED");
        assertThat(half.accessDrift())
                .as("an ENDED member who still holds a job is shown as drift")
                .isTrue();
        assertThat(kit.store.countEndedWithAccess(StaffKit.NOW, StaffMembers.MACHINE_ROLE_CODES))
                .isEqualTo(1);

        kit.revoking.failOn = 0;
        EndOutcome retried = end(id, OWNER);
        assertThat(retried.remainingGrants()).isZero();
        assertThat(retried.revokedGrants()).isEqualTo(1);
        assertThat(retried.member().accessDrift()).isFalse();
        assertThat(kit.store.countEndedWithAccess(StaffKit.NOW, StaffMembers.MACHINE_ROLE_CODES))
                .isZero();
    }

    @Test
    @DisplayName("a caller who cannot manage grants at every job's scope changes nothing at all")
    void lackingGrantAuthorityAtAnyJobChangesNothing() {
        UUID id = memberWithTwoJobs();
        // Can end a profile, but holds iam.grant.manage only at the other brand.
        kit.grant(StaffKit.TENANT_A, "brand-2-admin", PlatformRole.TENANT_ADMIN, "BRAND", StaffKit.BRAND_2);

        assertThatThrownBy(() -> end(id, "brand-2-admin"))
                .isInstanceOf(AuthorizationService.AccessDeniedException.class);

        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().employmentStatus())
                .as("refused before the first change, so nobody is half ended")
                .isEqualTo("ACTIVE");
        assertThat(kit.jdbc
                        .sql("SELECT count(*) FROM iam.grants WHERE principal_subject = 'leaver' AND status = 'ACTIVE'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("nobody ends their own employment")
    void nobodyEndsTheirOwnEmployment() {
        UUID owner = kit.activeMember(StaffKit.TENANT_A, OWNER, "Self", "Owner", null);

        assertThatThrownBy(() -> end(owner, OWNER))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNPROCESSABLE_STATE));
    }

    @Test
    @DisplayName("a machine job is not a colleague: a device grant is neither revoked nor counted as access")
    void aDeviceGrantIsNotAStaffJob() {
        // The touch KDS (ADR 0079) and the wall display (ADR 0151) are both machines: a role added to
        // the closed device set that StaffMembers.MACHINE_ROLE_CODES forgot would make a TV a colleague.
        for (PlatformRole device : List.of(PlatformRole.KITCHEN_DEVICE, PlatformRole.KITCHEN_VDU_DEVICE)) {
            String subject = "device-ish-" + device.code();
            UUID id = kit.activeMember(StaffKit.TENANT_A, subject, "Not", "ADevice", null);
            kit.grant(StaffKit.TENANT_A, subject, device, "LOCATION", StaffKit.LOCATION_1);

            assertThat(kit.members
                            .detail(StaffKit.TENANT_A, Reach.tenant(null, null), id)
                            .hasActiveAccess())
                    .as(device.code())
                    .isFalse();
        }
        assertThat(kit.members.list(
                        StaffKit.TENANT_A, Reach.location(StaffKit.BRAND_1, StaffKit.LOCATION_1), null, null))
                .isEmpty();
    }

    // ----------------------------------------------------------------- photos

    @Test
    @DisplayName(
            "a photo goes through the media pipeline, is stored on the person's own row, and is shown by a signed URL")
    void aPhotoIsStoredAndSigned() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);

        MemberView withPhoto =
                kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {1, 2, 3}, "me.jpg", null, "corr-photo");

        assertThat(withPhoto.hasPhoto()).isTrue();
        assertThat(String.valueOf(withPhoto.photoUrl())).startsWith("https://signed.example/");
        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId())
                .isNotNull();
        assertThat(kit.members
                        .list(StaffKit.TENANT_A, Reach.tenant(null, null), null, null)
                        .getFirst()
                        .photoUrl())
                .as("a list carries the flag and never a link")
                .isNull();
    }

    @Test
    @DisplayName(
            "a rejected image is refused with the pipeline's code, and a stale version is refused before any upload")
    void aRejectedImageAndAStaleVersion() {
        kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);

        assertThatThrownBy(() ->
                        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {'X', 1}, null, null, "corr-bad"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.properties()).containsEntry("reason", "CONTENT_NOT_AN_IMAGE");
                });
        assertThatThrownBy(() ->
                        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 1, new byte[] {1}, null, null, "corr-stale"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
    }

    @Test
    @DisplayName("removing a photo drops the reference")
    void removingAPhotoDropsTheReference() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);
        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {1}, null, null, "corr-photo");

        MemberView removed = kit.inTx(() -> kit.members.updateSelf(
                StaffKit.TENANT_A, CHEF, 3, new ProfileEdit(FIRST, LAST, null, null, null), true, "corr-remove"));

        assertThat(removed.hasPhoto()).isFalse();
        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId())
                .isNull();
    }

    @Test
    @DisplayName("removing a photo asks the pipeline to delete it, so pressing remove removes the picture")
    void removingAPhotoDiscardsTheAsset() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);
        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {1}, null, null, "corr-photo");
        UUID photo = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId();

        kit.inTx(() -> kit.members.updateSelf(
                StaffKit.TENANT_A, CHEF, 3, new ProfileEdit(FIRST, LAST, null, null, null), true, "corr-remove"));

        assertThat(kit.photos.discarded).containsExactly(photo);
    }

    @Test
    @DisplayName("an edit that leaves the photo alone deletes nothing")
    void anEditThatKeepsThePhotoDiscardsNothing() {
        kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);
        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {1}, null, null, "corr-photo");

        kit.inTx(() -> kit.members.updateSelf(
                StaffKit.TENANT_A, CHEF, 3, new ProfileEdit("Zukhra-2", LAST, null, null, null), false, "corr-edit"));

        assertThat(kit.photos.discarded).isEmpty();
    }

    @Test
    @DisplayName("replacing a photo asks the pipeline to delete the one it replaces, and not the new one")
    void replacingAPhotoDiscardsTheOldOne() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);
        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {1}, null, null, "corr-first");
        UUID first = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId();
        assertThat(kit.photos.discarded)
                .as("the first photo has nothing before it to delete")
                .isEmpty();

        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 3, new byte[] {2}, null, null, "corr-second");

        UUID second = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId();
        assertThat(second).isNotEqualTo(first);
        assertThat(kit.photos.discarded).containsExactly(first);
    }

    @Test
    @DisplayName("a photo that loses the version race is deleted at once, and the record keeps the one it had")
    void aPhotoThatLosesTheRaceIsDiscarded() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);
        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {1}, null, null, "corr-first");
        UUID kept = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId();
        // Somebody else edits the record while the second upload is in flight, so
        // the version the upload was checked against is stale by the time it counts.
        kit.photos.whileIngesting = () -> kit.jdbc
                .sql("UPDATE iam.staff_members SET version = version + 1 WHERE id = :id")
                .param("id", id)
                .update();

        assertThatThrownBy(
                        () -> kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 3, new byte[] {2}, null, null, "corr-lost"))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));

        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId())
                .as("the record still points at the photo it had")
                .isEqualTo(kept);
        assertThat(kit.photos.discarded)
                .as("the upload that lost is deleted, and the photo still in use is not")
                .hasSize(1)
                .doesNotContain(kept);
    }

    @Test
    @DisplayName("a refused image deletes nothing, because no asset was made")
    void aRefusedImageDiscardsNothing() {
        kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, null);

        assertThatThrownBy(() ->
                        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {'X', 1}, null, null, "corr-bad"))
                .isInstanceOf(ApiException.class);

        assertThat(kit.photos.discarded).isEmpty();
    }

    // -------------------------------------------------------------- anonymise

    @Test
    @DisplayName("anonymising an ended member nulls the personal fields in place and keeps the reference and history")
    void anonymiseOverwritesInPlace() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);
        kit.inTx(() -> kit.emergency.replace(
                StaffKit.TENANT_A,
                Reach.tenant(null, null),
                id,
                2,
                List.of(new uz.horecaos.platform.iam.application.staff.StaffEmergencyContactService.ContactEdit(
                        "SPOUSE", "Third Party", "+998 90 000 00 01")),
                OWNER,
                null,
                "corr-contact"));
        kit.grant(StaffKit.TENANT_A, CHEF, PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        end(id, OWNER);

        boolean done = kit.inTx(
                () -> kit.members.anonymise(StaffKit.TENANT_A, id, "retention", "staff-member-retention", "corr-anon"));

        assertThat(done).isTrue();
        var row = kit.store.find(StaffKit.TENANT_A, id).orElseThrow();
        assertThat(row.protectedFirstName()).isNull();
        assertThat(row.protectedLastName()).isNull();
        assertThat(row.protectedPhone()).isNull();
        assertThat(row.phoneLookupHash()).isNull();
        assertThat(row.displayReference()).isEqualTo("S-0001");
        assertThat(row.employmentStatus()).isEqualTo("ENDED");
        assertThat(kit.contactStore.count(StaffKit.TENANT_A, id)).isZero();
        assertThat(kit.photos.discarded).as("no photo, nothing to delete").isEmpty();
        assertThat(kit.directory.nameOf(StaffKit.TENANT_A, CHEF))
                .as("audit and order attribution still resolve to the former employee, by reference")
                .isEqualTo("S-0001");

        assertThat(kit.inTx(() -> kit.members.anonymise(
                        StaffKit.TENANT_A,
                        kit.activeMember(StaffKit.TENANT_A, "still-employed", "Still", "Here", null),
                        "retention",
                        "staff-member-retention",
                        "corr-no")))
                .as("only an ended member can be anonymised")
                .isFalse();
    }

    @Test
    @DisplayName("anonymising an ended member deletes their photo as well as dropping the reference to it")
    void anonymiseDiscardsThePhoto() {
        UUID id = kit.activeMember(StaffKit.TENANT_A, CHEF, FIRST, LAST, PHONE);
        kit.members.setPhoto(StaffKit.TENANT_A, CHEF, 2, new byte[] {1}, null, null, "corr-photo");
        UUID photo = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId();
        kit.grant(StaffKit.TENANT_A, CHEF, PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        end(id, OWNER);

        kit.inTx(
                () -> kit.members.anonymise(StaffKit.TENANT_A, id, "retention", "staff-member-retention", "corr-anon"));

        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().photoAssetId())
                .isNull();
        assertThat(kit.photos.discarded)
                .as("a data-subject erasure must not leave the face in storage")
                .containsExactly(photo);
    }
}
