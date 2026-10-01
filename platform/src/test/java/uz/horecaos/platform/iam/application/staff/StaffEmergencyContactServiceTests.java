package uz.horecaos.platform.iam.application.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.protection.FieldProtection.ProtectionIntegrityException;
import uz.horecaos.platform.iam.application.staff.StaffEmergencyContactService.ContactEdit;
import uz.horecaos.platform.iam.application.staff.StaffEmergencyContactService.Read;
import uz.horecaos.platform.iam.application.staff.StaffEmergencyContactService.Replaced;
import uz.horecaos.platform.iam.application.staff.StaffMemberService.Reach;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0139, gap map row 9.2b: a staff member's emergency contacts -- a third
 * party's name and phone, kept about a person who never dealt with the platform,
 * read only through an audited capability.
 */
class StaffEmergencyContactServiceTests {

    private static final String OWNER = "emergency-owner";
    private static final String COOK = "emergency-cook";

    private static final String THIRD_NAME = "Maftuna Qodirova";
    private static final String THIRD_PHONE = "+998 71 234 56 78";

    private static TestDatabase.Handle db;
    private static StaffKit kit;

    private UUID cook;

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
        cook = kit.activeMember(StaffKit.TENANT_A, COOK, "Rustam", "Cook", null);
        kit.grant(StaffKit.TENANT_A, COOK, PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.published.clear();
    }

    private static ContactEdit contact(String relationship) {
        return new ContactEdit(relationship, THIRD_NAME, THIRD_PHONE);
    }

    private Replaced replace(Reach reach, UUID member, List<ContactEdit> edits) {
        int version = kit.store.find(StaffKit.TENANT_A, member).orElseThrow().version();
        return kit.inTx(() ->
                kit.emergency.replace(StaffKit.TENANT_A, reach, member, version, edits, OWNER, null, "corr-contacts"));
    }

    @Test
    @DisplayName("the name and phone are sealed per contact and come back whole")
    void contactsAreSealedAndRoundTrip() {
        Replaced replaced = replace(Reach.tenant(null, null), cook, List.of(contact("SPOUSE"), contact("PARENT")));

        assertThat(replaced.contacts()).hasSize(2);
        var stored = kit.contactStore.forMember(StaffKit.TENANT_A, cook);
        assertThat(stored)
                .allSatisfy(row -> assertThat(row.protectedName() + row.protectedPhone())
                        .doesNotContain("Maftuna", "Qodirova", "234"));

        Read read = kit.inTx(
                () -> kit.emergency.read(StaffKit.TENANT_A, Reach.tenant(null, null), cook, OWNER, "corr-read"));
        assertThat(read.contacts()).extracting(c -> c.name()).containsOnly(THIRD_NAME);
        assertThat(read.contacts()).extracting(c -> c.phone()).containsOnly("+998712345678");
        assertThat(read.contacts()).extracting(c -> c.slot()).containsExactly(1, 2);
        assertThat(read.memberVersion()).isEqualTo(replaced.memberVersion());
    }

    @Test
    @DisplayName("every read writes an audit fact, with a count and never a name or a number")
    void everyReadIsAudited() {
        replace(Reach.tenant(null, null), cook, List.of(contact("SPOUSE")));

        kit.inTx(() -> kit.emergency.read(StaffKit.TENANT_A, Reach.tenant(null, null), cook, OWNER, "corr-1"));
        kit.inTx(() -> kit.emergency.read(StaffKit.TENANT_A, Reach.tenant(null, null), cook, OWNER, "corr-2"));

        List<String> documents = kit.jdbc
                .sql(
                        "SELECT change_document::text FROM audit.audit_events WHERE action_code = 'staff.emergency_contact.read'")
                .query(String.class)
                .list();
        assertThat(documents)
                .as("a read that left no evidence would defeat the capability")
                .hasSize(2);
        assertThat(documents)
                .allSatisfy(document -> assertThat(document)
                        .contains("contactCount")
                        .doesNotContain("Maftuna", "Qodirova", "234", "998"));
        assertThat(kit.jdbc
                        .sql(
                                "SELECT capability_used FROM audit.audit_events WHERE action_code = 'staff.emergency_contact.read' LIMIT 1")
                        .query(String.class)
                        .single())
                .isEqualTo("staff.emergency-contact.read");
    }

    @Test
    @DisplayName("a replace is audited without the contacts, and moves the member's version")
    void aReplaceIsAuditedAndMovesTheMemberVersion() {
        int before = kit.store.find(StaffKit.TENANT_A, cook).orElseThrow().version();

        Replaced replaced = replace(Reach.tenant(null, null), cook, List.of(contact("FRIEND")));

        assertThat(replaced.memberVersion()).isEqualTo(before + 1);
        assertThat(kit.store.find(StaffKit.TENANT_A, cook).orElseThrow().version())
                .isEqualTo(before + 1);
        String document = kit.jdbc
                .sql(
                        "SELECT change_document::text FROM audit.audit_events WHERE action_code = 'staff.emergency_contact.updated'")
                .query(String.class)
                .single();
        assertThat(document).contains("contactCount").contains("[redacted]").doesNotContain("Maftuna", "234");
    }

    @Test
    @DisplayName("at most three contacts, a known relationship, a name and a number")
    void validationRefusesWhatCannotBeKept() {
        assertThatThrownBy(() -> replace(
                        Reach.tenant(null, null),
                        cook,
                        List.of(contact("SPOUSE"), contact("PARENT"), contact("CHILD"), contact("SIBLING"))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> replace(Reach.tenant(null, null), cook, List.of(contact("BOSS"))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.properties()).containsEntry("field", "relationshipCode"));
        assertThatThrownBy(() ->
                        replace(Reach.tenant(null, null), cook, List.of(new ContactEdit("SPOUSE", " ", THIRD_PHONE))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.properties()).containsEntry("field", "name"));
        assertThatThrownBy(() ->
                        replace(Reach.tenant(null, null), cook, List.of(new ContactEdit("SPOUSE", THIRD_NAME, "x"))))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.properties()).containsEntry("field", "phone"));
    }

    @Test
    @DisplayName("the set is replaced as a whole and a stale version is refused")
    void theSetIsReplacedWhole() {
        replace(Reach.tenant(null, null), cook, List.of(contact("SPOUSE"), contact("PARENT")));
        Replaced one = replace(Reach.tenant(null, null), cook, List.of(contact("CHILD")));

        assertThat(one.contacts()).extracting(c -> c.relationshipCode()).containsExactly("CHILD");
        assertThat(kit.contactStore.count(StaffKit.TENANT_A, cook)).isEqualTo(1);

        assertThatThrownBy(() -> kit.inTx(() -> kit.emergency.replace(
                        StaffKit.TENANT_A, Reach.tenant(null, null), cook, 1, List.of(), OWNER, null, "corr-stale")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        assertThat(replace(Reach.tenant(null, null), cook, List.of()).contacts())
                .isEmpty();
    }

    @Test
    @DisplayName(
            "a branch reads the contacts of its own people, changes them only for people wholly its own, and sees nothing of a sibling")
    void aBranchHasTheSameRulesAsForTheProfile() {
        UUID twoBranches = kit.activeMember(StaffKit.TENANT_A, "two-branches", "Two", "Branches", null);
        kit.grant(StaffKit.TENANT_A, "two-branches", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, "two-branches", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_2);
        UUID sibling = kit.activeMember(StaffKit.TENANT_A, "sibling", "Sib", "Ling", null);
        kit.grant(StaffKit.TENANT_A, "sibling", PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_2);
        Reach branch1 = Reach.location(StaffKit.BRAND_1, StaffKit.LOCATION_1);

        replace(branch1, cook, List.of(contact("SPOUSE")));
        assertThat(kit.inTx(() -> kit.emergency.read(StaffKit.TENANT_A, branch1, twoBranches, "mgr", "c1"))
                        .contacts())
                .as("visible at the branch, so readable there")
                .isEmpty();

        assertThatThrownBy(() -> replace(branch1, twoBranches, List.of(contact("SPOUSE"))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_CAPABILITY));
        assertThatThrownBy(() -> kit.inTx(() -> kit.emergency.read(StaffKit.TENANT_A, branch1, sibling, "mgr", "c2")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("a contact sealed for one member does not open on another's row")
    void aContactMovedToAnotherMemberFailsToOpen() {
        replace(
                Reach.tenant(null, null),
                cook,
                List.of(contact("SPOUSE"), new ContactEdit("PARENT", "Other Person", "+998 90 999 88 77")));
        var rows = kit.contactStore.forMember(StaffKit.TENANT_A, cook);
        kit.jdbc
                .sql("UPDATE iam.staff_emergency_contacts SET protected_phone = :phone WHERE id = :id")
                .param("phone", rows.get(0).protectedPhone())
                .param("id", rows.get(1).id())
                .update();

        assertThatThrownBy(() -> kit.inTx(() ->
                        kit.emergency.read(StaffKit.TENANT_A, Reach.tenant(null, null), cook, OWNER, "corr-tampered")))
                .isInstanceOf(ProtectionIntegrityException.class);
    }

    @Test
    @DisplayName("another tenant's member has no contacts to this tenant")
    void anotherTenantsMemberIsNotThere() {
        UUID foreign = kit.activeMember(StaffKit.TENANT_B, "foreign-1", "For", "Eign", null);

        assertThatThrownBy(() -> kit.inTx(() ->
                        kit.emergency.read(StaffKit.TENANT_A, Reach.tenant(null, null), foreign, OWNER, "corr-x")))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
