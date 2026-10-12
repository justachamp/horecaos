package uz.horecaos.platform.iam.application.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.application.staff.StaffPreferenceSchemas.SurfaceDefinition;
import uz.horecaos.platform.iam.application.staff.StaffPreferencesService.StoredPreference;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * ADR 0160 against a real PostgreSQL and the real envelope protection: the store, the seal, the versions, the
 * branch check, the cap, the anonymisation hook and the cache.
 *
 * <p>The label below is deliberately a name and nothing else in the suite is, so "this text is in no column,
 * no log line and no metric" is a search for a string that could not be there by accident.
 */
class StaffPreferencesServiceTests {

    private static final String OWNER = "pref-owner";
    private static final String COOK = "pref-cook";
    private static final String OTHER_COOK = "pref-other-cook";

    private static final String LABEL = "Jasur Karimov";
    private static final String VIEW_ID = "0198a3c0-0000-7000-8000-000000000001";

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
        kit.meters.clear();
        kit.grant(StaffKit.TENANT_A, OWNER, PlatformRole.TENANT_OWNER, "TENANT", StaffKit.TENANT_A);
        kit.activeMember(StaffKit.TENANT_A, COOK, "Shahlo", "Tursunova", null);
        kit.activeMember(StaffKit.TENANT_A, OTHER_COOK, "Rustam", "Qodirov", null);
        kit.grant(StaffKit.TENANT_A, COOK, PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_1);
        kit.grant(StaffKit.TENANT_A, OTHER_COOK, PlatformRole.LOCATION_STAFF, "LOCATION", StaffKit.LOCATION_2);
    }

    private static JsonNode json(String text) {
        return StaffPreferenceSchemas.mapper().readTree(text);
    }

    private static String boardWithView(String label) {
        return "{\"columns\":{\"show\":[\"createdBy\"],\"hide\":[\"courier\"]},\"savedViews\":[{\"id\":\"" + VIEW_ID
                + "\",\"label\":\"" + label + "\",\"tab\":\"attention\",\"filters\":{\"lateOnly\":true}}]}";
    }

    private StoredPreference saveBoard(String subject, String label, @Nullable Long expected) {
        return kit.preferences.write(
                StaffKit.TENANT_A, subject, StaffPreferenceSurface.ORDER_BOARD, json(boardWithView(label)), expected);
    }

    private List<String> rawDocuments(String subject) {
        return kit.jdbc
                .sql(
                        "SELECT protected_document FROM iam.staff_preferences WHERE tenant_id = :t AND principal_subject = :s")
                .param("t", StaffKit.TENANT_A)
                .param("s", subject)
                .query(String.class)
                .list();
    }

    // ------------------------------------------------------------ protection

    @Test
    @DisplayName("a document is stored sealed: the row holds neither the label nor a single key of the vocabulary")
    void theDocumentIsCiphertext() {
        StoredPreference saved = saveBoard(COOK, LABEL, null);

        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.documentSchema()).isEqualTo(1);
        assertThat(rawDocuments(COOK))
                .singleElement()
                .asString()
                .isNotBlank()
                .doesNotContain(
                        "Jasur", "Karimov", "createdBy", "courier", "savedViews", "attention", "lateOnly", VIEW_ID);

        List<StoredPreference> read = kit.preferences.readAll(StaffKit.TENANT_A, COOK);
        assertThat(read).singleElement().satisfies(stored -> {
            assertThat(stored.surface()).isEqualTo(StaffPreferenceSurface.ORDER_BOARD);
            assertThat(stored.document().get("savedViews").get(0).get("label").asString())
                    .isEqualTo(LABEL);
            assertThat(stored.document().get("columns").get("show").get(0).asString())
                    .isEqualTo("createdBy");
        });
    }

    @Test
    @DisplayName("no column of the table other than the sealed document, the subject and the surface can hold text")
    void noOtherColumnCanHoldText() {
        List<String> textColumns = kit.jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                         WHERE table_schema = 'iam' AND table_name = 'staff_preferences'
                           AND data_type IN ('text', 'character varying', 'character', 'json', 'jsonb', 'ARRAY')
                         ORDER BY column_name
                        """).query(String.class).list();

        assertThat(textColumns)
                .as("a column added here is a column a name or a phone number can be written into")
                .containsExactly("principal_subject", "protected_document", "surface");

        saveBoard(COOK, LABEL, null);
        Map<String, Object> row = kit.jdbc
                .sql("SELECT principal_subject, surface FROM iam.staff_preferences")
                .query()
                .singleRow();
        assertThat(row).containsEntry("principal_subject", COOK).containsEntry("surface", "ORDER_BOARD");
    }

    @Test
    @DisplayName(
            "a ciphertext copied to another person's row fails to open: that surface reads as absent, the rest still read")
    void aMovedCiphertextDoesNotOpen() {
        saveBoard(COOK, LABEL, null);
        kit.preferences.write(
                StaffKit.TENANT_A,
                OTHER_COOK,
                StaffPreferenceSurface.ORDER_BOARD,
                json("{\"columns\":{\"hide\":[\"items\"]}}"),
                null);
        String cooks = rawDocuments(COOK).getFirst();
        kit.jdbc
                .sql("UPDATE iam.staff_preferences SET protected_document = :sealed WHERE principal_subject = :s")
                .param("sealed", cooks)
                .param("s", OTHER_COOK)
                .update();
        kit.preferenceCache.evict(StaffKit.TENANT_A, OTHER_COOK);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger service = (Logger) LoggerFactory.getLogger(StaffPreferencesService.class);
        service.addAppender(appender);
        try {
            assertThat(kit.preferences.readAll(StaffKit.TENANT_A, OTHER_COOK))
                    .as("a row whose sealed text was written for another row is not shown as this person's layout")
                    .isEmpty();
            assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK)).hasSize(1);
        } finally {
            service.detachAppender(appender);
            appender.stop();
        }
        assertThat(appender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("integrity"))
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).doesNotContain("Jasur", "Karimov"));
    }

    // -------------------------------------------------------------- versions

    @Test
    @DisplayName("a first write carries no version, a later one the version read, and a second first write is stale")
    void versionsSerialiseTwoDevices() {
        StoredPreference first = saveBoard(COOK, "Late", null);
        assertThat(first.version()).isEqualTo(1);

        assertThatThrownBy(() -> saveBoard(COOK, "Second device, first write", null))
                .isInstanceOfSatisfying(ApiException.class, stale -> {
                    assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                    assertThat(stale.properties())
                            .containsEntry("expectedVersion", 0L)
                            .containsEntry("currentVersion", 1L);
                });

        StoredPreference second = saveBoard(COOK, "Late deliveries", 1L);
        assertThat(second.version()).isEqualTo(2);

        assertThatThrownBy(() -> saveBoard(COOK, "Stale device", 1L))
                .isInstanceOfSatisfying(ApiException.class, stale -> {
                    assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                    assertThat(stale.properties())
                            .containsEntry("expectedVersion", 1L)
                            .containsEntry("currentVersion", 2L);
                });
        assertThatThrownBy(() ->
                        kit.preferences.write(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.SHELL, json("{}"), 3L))
                .as("a version for a document that does not exist is a stale read too")
                .isInstanceOfSatisfying(ApiException.class, stale -> {
                    assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                    assertThat(stale.properties()).containsEntry("currentVersion", 0L);
                });

        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.version()).isEqualTo(2);
                    assertThat(stored.document()
                                    .get("savedViews")
                                    .get(0)
                                    .get("label")
                                    .asString())
                            .isEqualTo("Late deliveries");
                });
    }

    @Test
    @DisplayName("two devices saving their first choice at once: exactly one wins and the other is told it is stale")
    void twoFirstWritesRaceToOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> writers = new ArrayList<>();
            for (String label : List.of("Laptop", "Tablet")) {
                writers.add(() -> {
                    try {
                        saveBoard(COOK, label, null);
                        return "saved";
                    } catch (ApiException refused) {
                        return refused.errorCode().name();
                    }
                });
            }
            List<String> outcomes = new ArrayList<>();
            for (Future<String> outcome : pool.invokeAll(writers)) {
                outcomes.add(outcome.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder("saved", "STALE_VERSION");
            assertThat(rawDocuments(COOK)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // --------------------------------------------------------------- isolation

    @Test
    @DisplayName("one subject in two tenants keeps two separate documents, and a colleague sees none of them")
    void documentsBelongToTenantAndSubject() {
        UUID memberInB = kit.activeMember(StaffKit.TENANT_B, COOK, "Shahlo", "Of-B", null);
        assertThat(memberInB).isNotNull();
        saveBoard(COOK, LABEL, null);
        kit.preferences.write(
                StaffKit.TENANT_B,
                COOK,
                StaffPreferenceSurface.ORDER_BOARD,
                json("{\"columns\":{\"hide\":[\"items\"]}}"),
                null);

        List<StoredPreference> inA = kit.preferences.readAll(StaffKit.TENANT_A, COOK);
        List<StoredPreference> inB = kit.preferences.readAll(StaffKit.TENANT_B, COOK);

        assertThat(inA)
                .singleElement()
                .satisfies(stored -> assertThat(stored.document().toString()).contains("savedViews"));
        assertThat(inB)
                .singleElement()
                .satisfies(stored -> assertThat(stored.document().toString())
                        .contains("items")
                        .doesNotContain("savedViews", "Jasur"));
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, OTHER_COOK))
                .as("a colleague of the same tenant")
                .isEmpty();
        assertThatThrownBy(() -> kit.preferences.readAll(StaffKit.TENANT_B, OTHER_COOK))
                .as("a subject with no record in the tenant asked about is not a person of that tenant")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        none -> assertThat(none.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName(
            "an account with no member row in the tenant -- a device, a support session -- can read, write and reset nothing")
    void aSubjectWithNoRecordHasNoPreferences() {
        kit.grant(StaffKit.TENANT_A, "a-kitchen-device", PlatformRole.KITCHEN_DEVICE, "LOCATION", StaffKit.LOCATION_1);

        assertThatThrownBy(() -> kit.preferences.readAll(StaffKit.TENANT_A, "a-kitchen-device"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        none -> assertThat(none.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> saveBoard("a-kitchen-device", "x", null))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        none -> assertThat(none.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> kit.preferences.reset(
                        StaffKit.TENANT_A, "a-kitchen-device", StaffPreferenceSurface.ORDER_BOARD))
                .isInstanceOf(ApiException.class);
        assertThat(kit.jdbc
                        .sql("SELECT count(*) FROM iam.staff_preferences")
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    // ------------------------------------------------------------------ branch

    @Test
    @DisplayName(
            "the default branch has to be one the person works at: a branch grant, a brand grant over it, or a tenant grant")
    void theDefaultBranchIsOneThePersonWorksAt() {
        kit.grant(StaffKit.TENANT_A, "brand-manager", PlatformRole.BRAND_MANAGER, "BRAND", StaffKit.BRAND_1);
        kit.activeMember(StaffKit.TENANT_A, "brand-manager", "Brand", "Manager", null);

        // A LOCATION grant: that branch and no other.
        assertThat(savedShell(COOK, StaffKit.LOCATION_1)).isNotNull();
        assertRefused(COOK, StaffKit.LOCATION_2);
        // A BRAND grant covers the brand's branches, not another brand's.
        assertThat(savedShell("brand-manager", StaffKit.LOCATION_2)).isNotNull();
        assertRefused("brand-manager", StaffKit.LOCATION_3);
        // A TENANT grant covers every branch of the tenant, and nobody's branch of another tenant.
        kit.activeMember(StaffKit.TENANT_A, OWNER, "Owner", "Person", null);
        assertThat(savedShell(OWNER, StaffKit.LOCATION_3)).isNotNull();
        assertRefused(OWNER, StaffKit.B_LOCATION);
        // A branch that does not exist at all.
        assertRefused(OWNER, UUID.fromString("0198a3c0-0000-7000-8000-00000000dead"));
    }

    private StoredPreference savedShell(String subject, UUID location) {
        @Nullable
        Long expected = kit.preferences.readAll(StaffKit.TENANT_A, subject).stream()
                .filter(stored -> stored.surface() == StaffPreferenceSurface.SHELL)
                .map(stored -> (long) stored.version())
                .findFirst()
                .orElse(null);
        return kit.preferences.write(
                StaffKit.TENANT_A,
                subject,
                StaffPreferenceSurface.SHELL,
                json("{\"defaultLocationId\":\"" + location + "\"}"),
                expected);
    }

    private void assertRefused(String subject, UUID location) {
        assertThatThrownBy(() -> savedShell(subject, location)).isInstanceOfSatisfying(ApiException.class, refused -> {
            assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(refused.properties()).containsEntry("field", "document.defaultLocationId");
        });
    }

    @Test
    @DisplayName(
            "a branch whose grant ended after it was chosen is not this store's to repair: the read still returns it")
    void aStaleDefaultBranchIsLeftToTheConsole() {
        savedShell(COOK, StaffKit.LOCATION_1);
        kit.jdbc.sql("""
                        UPDATE iam.grants
                           SET status = 'REVOKED', revoked_at = now(), revoked_by = 'test', revoked_reason = 'test'
                         WHERE principal_subject = :s
                        """).param("s", COOK).update();
        kit.preferenceCache.evict(StaffKit.TENANT_A, COOK);

        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK))
                .singleElement()
                .satisfies(stored -> assertThat(
                                stored.document().get("defaultLocationId").asString())
                        .isEqualTo(StaffKit.LOCATION_1.toString()));
    }

    // -------------------------------------------------------------------- caps

    @Test
    @DisplayName("a person holds documents for at most twelve surfaces")
    void aPersonHoldsAtMostTwelveSurfaces() {
        for (int index = 0; index < StaffPreferenceSchemas.MAX_SURFACES_PER_PERSON; index++) {
            UUID memberId = kit.store
                    .findBySubject(StaffKit.TENANT_A, COOK)
                    .orElseThrow()
                    .id();
            assertThat(memberId).isNotNull();
            kit.jdbc
                    .sql("""
                            INSERT INTO iam.staff_preferences
                                (id, tenant_id, principal_subject, surface, protected_document, document_schema)
                            VALUES (:id, :t, :s, :surface, 'x', 1)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("t", StaffKit.TENANT_A)
                    .param("s", COOK)
                    .param("surface", "FUTURE_SURFACE_" + index)
                    .update();
        }

        assertThatThrownBy(() -> saveBoard(COOK, "One too many", null))
                .isInstanceOfSatisfying(ApiException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(refused.properties()).containsEntry("field", "surface");
                });
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK))
                .as("rows for surfaces this release does not know are not shown and not an error")
                .isEmpty();
    }

    // --------------------------------------------------------- upgrade on read

    @Test
    @DisplayName("a document written under an older schema is upgraded when it is read, and stored as it was")
    void anOlderDocumentIsUpgradedOnRead() {
        saveBoard(COOK, LABEL, null);
        String sealedBefore = rawDocuments(COOK).getFirst();

        Map<StaffPreferenceSurface, SurfaceDefinition> definitions = new EnumMap<>(StaffPreferenceSurface.class);
        definitions.put(
                StaffPreferenceSurface.SHELL, new SurfaceDefinition(1, document -> (ObjectNode) document, List.of()));
        definitions.put(
                StaffPreferenceSurface.ORDER_BOARD,
                new SurfaceDefinition(2, document -> (ObjectNode) document, List.of(document -> {
                    ObjectNode copy = ((ObjectNode) document).deepCopy();
                    copy.put("upgraded", true);
                    return copy;
                })));
        StaffPreferencesService later = new StaffPreferencesService(
                kit.preferenceStore,
                kit.store,
                new StaffPreferenceSchemas(definitions),
                kit.preferenceCache,
                uz.horecaos.platform.support.TestProtection.envelope(),
                kit.meters,
                kit.clock);

        assertThat(later.readAll(StaffKit.TENANT_A, COOK)).singleElement().satisfies(stored -> {
            assertThat(stored.document().get("upgraded").asBoolean()).isTrue();
            assertThat(stored.documentSchema()).isEqualTo(2);
            assertThat(stored.version()).isEqualTo(1);
        });
        assertThat(rawDocuments(COOK).getFirst())
                .as("reading never rewrites the row; the next save does, at the new schema")
                .isEqualTo(sealedBefore);
    }

    // ------------------------------------------------------------------ reset

    @Test
    @DisplayName(
            "a reset empties the document and hides the surface; resetting what was never saved is the same quiet no-op")
    void aResetEmptiesTheDocument() {
        saveBoard(COOK, LABEL, null);
        String sealedBefore = rawDocuments(COOK).getFirst();

        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);
        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);
        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.SHELL);

        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK)).isEmpty();
        assertThat(rawDocuments(COOK))
                .as("what the person saved is gone from the row, which keeps only a version to count on")
                .singleElement()
                .satisfies(sealedAfter -> {
                    assertThat(sealedAfter).isNotEqualTo(sealedBefore);
                    assertThat(sealedAfter.length()).isLessThan(sealedBefore.length());
                });
        assertThat(kit.jdbc
                        .sql("SELECT reset_at IS NOT NULL FROM iam.staff_preferences")
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(saveBoard(COOK, "Fresh start", null).version())
                .as("a reset is a write: the version it leaves is the one the next write counts on")
                .isEqualTo(3);
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK))
                .singleElement()
                .satisfies(stored -> assertThat(stored.version()).isEqualTo(3));
    }

    @Test
    @DisplayName(
            "a reset does not take the version back to 1: a device that read before it cannot write over what is saved after it")
    void aResetKeepsTheVersionCounting() {
        saveBoard(COOK, "Read by the old tablet", null);
        saveBoard(COOK, "Edited on the laptop", 1L);
        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);

        StoredPreference fresh = saveBoard(COOK, "Fresh start", null);

        assertThat(fresh.version())
                .as("a document saved after a reset is a newer version than any a device read before it")
                .isGreaterThan(2);
        for (long heldByAStaleDevice : new long[] {1L, 2L}) {
            assertThatThrownBy(() -> saveBoard(COOK, "The old tablet's late write", heldByAStaleDevice))
                    .as("If-Match %d", heldByAStaleDevice)
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            stale -> assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION));
        }
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK))
                .singleElement()
                .satisfies(stored -> assertThat(stored.document()
                                .get("savedViews")
                                .get(0)
                                .get("label")
                                .asString())
                        .isEqualTo("Fresh start"));
    }

    @Test
    @DisplayName(
            "after a reset the surface is absent: a write that names a version is stale, one that names none is a first write")
    void aResetSurfaceIsAbsent() {
        saveBoard(COOK, "Gone", null);
        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);

        assertThatThrownBy(() -> saveBoard(COOK, "Names the version it read", 1L))
                .isInstanceOfSatisfying(ApiException.class, stale -> {
                    assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                    assertThat(stale.properties()).containsEntry("currentVersion", 0L);
                });
        assertThatThrownBy(() -> saveBoard(COOK, "Names the marker's own version", 2L))
                .as("the version a reset leaves is the platform's to count on, not a document a device can name")
                .isInstanceOf(ApiException.class);

        assertThat(saveBoard(COOK, "Names none", null).version()).isEqualTo(3);
    }

    @Test
    @DisplayName("a surface that was reset does not count against the twelve a person may hold")
    void aResetSurfaceIsNotCounted() {
        for (int index = 0; index < StaffPreferenceSchemas.MAX_SURFACES_PER_PERSON - 1; index++) {
            kit.jdbc
                    .sql("""
                            INSERT INTO iam.staff_preferences
                                (id, tenant_id, principal_subject, surface, protected_document, document_schema)
                            VALUES (:id, :t, :s, :surface, 'x', 1)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("t", StaffKit.TENANT_A)
                    .param("s", COOK)
                    .param("surface", "FUTURE_SURFACE_" + index)
                    .update();
        }
        saveBoard(COOK, "The twelfth", null);
        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);

        assertThat(savedShell(COOK, StaffKit.LOCATION_1))
                .as("eleven live documents and a marker are eleven, so a twelfth fits")
                .isNotNull();
    }

    // ----------------------------------------------------------- anonymisation

    @Test
    @DisplayName("anonymising a member deletes their preferences in the same transaction as the member's own update")
    void anonymisationDeletesThePreferences() {
        UUID id = kit.store.findBySubject(StaffKit.TENANT_A, COOK).orElseThrow().id();
        saveBoard(COOK, LABEL, null);
        savedShell(COOK, StaffKit.LOCATION_1);
        saveBoard(OTHER_COOK, "A colleague's view", null);
        int version = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().version();
        kit.members.endEmployment(StaffKit.TENANT_A, id, version, null, "Resigned", OWNER, "corr-end");

        assertThatThrownBy(() -> kit.tx.executeWithoutResult(status -> {
                    assertThat(kit.members.anonymise(StaffKit.TENANT_A, id, "retention", "job", "corr-anon"))
                            .isTrue();
                    assertThat(rawDocuments(COOK))
                            .as("gone inside the transaction")
                            .isEmpty();
                    throw new IllegalStateException("roll it back");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rawDocuments(COOK))
                .as("the preferences go with the member's own update or not at all")
                .hasSize(2);
        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().protectedFirstName())
                .isNotNull();

        kit.inTx(() -> kit.members.anonymise(StaffKit.TENANT_A, id, "retention", "job", "corr-anon"));

        assertThat(rawDocuments(COOK)).isEmpty();
        assertThat(rawDocuments(OTHER_COOK)).as("a colleague's are not touched").hasSize(1);
        assertThat(kit.store.find(StaffKit.TENANT_A, id).orElseThrow().protectedFirstName())
                .isNull();
        assertThat(kit.published).anySatisfy(change -> {
            assertThat(change.actionCode()).isEqualTo("staff.member.anonymised");
            assertThat(change.before()).containsEntry("preferences", "set");
            assertThat(change.after()).containsEntry("preferences", null);
        });
    }

    @Test
    @DisplayName("anonymising a member also removes the markers a reset left, and does not call a marker a document")
    void anonymisationRemovesResetMarkers() {
        UUID id = kit.store.findBySubject(StaffKit.TENANT_A, COOK).orElseThrow().id();
        saveBoard(COOK, LABEL, null);
        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);
        int version = kit.store.find(StaffKit.TENANT_A, id).orElseThrow().version();
        kit.members.endEmployment(StaffKit.TENANT_A, id, version, null, "Resigned", OWNER, "corr-end");

        kit.inTx(() -> kit.members.anonymise(StaffKit.TENANT_A, id, "retention", "job", "corr-anon"));

        assertThat(rawDocuments(COOK)).isEmpty();
        assertThat(kit.published).anySatisfy(change -> {
            assertThat(change.actionCode()).isEqualTo("staff.member.anonymised");
            assertThat(change.before()).containsEntry("preferences", null);
        });
    }

    // -------------------------------------------------------------------- cache

    @Test
    @DisplayName("a read is served from the cache, and a write, a reset and an anonymisation each drop it")
    void theCacheIsEvictedByEveryWrite() {
        saveBoard(COOK, "One", null);
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK)).hasSize(1);
        kit.jdbc.sql("DELETE FROM iam.staff_preferences").update();
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK))
                .as("the second read did not go to the database, which is what an accelerator is")
                .hasSize(1);

        kit.preferenceCache.evict(StaffKit.TENANT_A, COOK);
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK)).isEmpty();

        saveBoard(COOK, "Two", null);
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK))
                .as("a write evicts, so the next read is the new document")
                .singleElement()
                .satisfies(stored -> assertThat(stored.version()).isEqualTo(1));
        saveBoard(COOK, "Three", 1L);
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK))
                .singleElement()
                .satisfies(stored -> assertThat(stored.version()).isEqualTo(2));
        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);
        assertThat(kit.preferences.readAll(StaffKit.TENANT_A, COOK)).isEmpty();
    }

    @Test
    @DisplayName("a write never trusts the cache: a version check is against the database")
    void aWriteChecksTheDatabase() {
        saveBoard(COOK, "One", null);
        kit.preferences.readAll(StaffKit.TENANT_A, COOK);
        kit.jdbc.sql("UPDATE iam.staff_preferences SET version = 7").update();

        assertThatThrownBy(() -> saveBoard(COOK, "Two", 1L)).isInstanceOfSatisfying(ApiException.class, stale -> {
            assertThat(stale.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
            assertThat(stale.properties()).containsEntry("currentVersion", 7L);
        });
    }

    // ------------------------------------------------------------------ metrics

    @Test
    @DisplayName("the only measurement is what became of a write, with no identity, surface or document on it")
    void theMetricCarriesNothingElse() {
        saveBoard(COOK, LABEL, null);
        saveBoard(COOK, LABEL, 1L);
        assertThatThrownBy(() -> saveBoard(COOK, LABEL, 1L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> saveBoard(COOK, "998901234567", 2L)).isInstanceOf(ApiException.class);
        kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);

        Map<String, Double> byOutcome = new java.util.TreeMap<>();
        kit.meters.getMeters().forEach(meter -> {
            assertThat(meter.getId().getName()).startsWith("horecaos.iam.staff.preferences");
            assertThat(meter.getId().getTags()).singleElement().satisfies(tag -> {
                assertThat(tag.getKey()).isEqualTo("outcome");
                byOutcome.put(
                        tag.getValue(),
                        kit.meters
                                .get(meter.getId().getName())
                                .tag("outcome", tag.getValue())
                                .counter()
                                .count());
            });
        });
        assertThat(byOutcome)
                .containsEntry("created", 1.0)
                .containsEntry("updated", 1.0)
                .containsEntry("stale", 1.0)
                .containsEntry("refused", 1.0)
                .containsEntry("reset", 1.0);
    }

    // ---------------------------------------------------------------------- logs

    @Test
    @DisplayName("nothing a person saved reaches a log line, whatever the level")
    void nothingIsLogged() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level previous = root.getLevel();
        root.setLevel(ch.qos.logback.classic.Level.ALL);
        root.addAppender(appender);
        try {
            saveBoard(COOK, LABEL, null);
            kit.preferenceCache.evict(StaffKit.TENANT_A, COOK);
            kit.preferences.readAll(StaffKit.TENANT_A, COOK);
            assertThatThrownBy(() -> saveBoard(COOK, "998901234567", 1L)).isInstanceOf(ApiException.class);
            kit.preferences.reset(StaffKit.TENANT_A, COOK, StaffPreferenceSurface.ORDER_BOARD);
        } finally {
            root.detachAppender(appender);
            root.setLevel(previous);
            appender.stop();
        }
        List<ILoggingEvent> events;
        synchronized (appender) {
            events = List.copyOf(appender.list);
        }
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> {
            String text = event.getFormattedMessage() + java.util.Arrays.toString(event.getArgumentArray());
            assertThat(text).doesNotContain("Jasur", "Karimov", "998901234567", "savedViews", VIEW_ID);
        });
    }
}
