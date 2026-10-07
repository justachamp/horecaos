package uz.horecaos.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.assistant.application.KnowledgeService.EntryView;
import uz.horecaos.platform.assistant.application.KnowledgeService.KnowledgeMatch;
import uz.horecaos.platform.conversations.api.ConversationParticipant;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The tenant's own answers (ADR 0069): versioned, never edited, scoped, and read
 * only by the tenant that wrote them. The isolation tests here are the ones the
 * ADR names -- cross-tenant retrieval is "a tenant-isolation defect, not a
 * relevance bug" -- and each is built so that a retrieval that forgot the tenant
 * would fail it.
 */
class KnowledgeServiceTests {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private AssistantFixture a;
    private AssistantFixture b;
    private KnowledgeService knowledge;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql(
                        "TRUNCATE TABLE assistant.turns, assistant.knowledge_entry_versions, assistant.knowledge_entries CASCADE")
                .update();
        AuditTrail.clear(jdbc);
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        a = new AssistantFixture(jdbc, NOW);
        b = new AssistantFixture(jdbc, NOW);
        a.seedTenancy();
        b.seedTenancy();
        knowledge = a.knowledge;
    }

    private EntryView parking(String locale, String answer) {
        return knowledge.create(
                a.tenantId, a.brandId, null, locale, "Is there parking at your restaurant?", answer, "author-1", "FAQ");
    }

    private List<KnowledgeMatch> ask(AssistantFixture who, String question, String... locales) {
        return who.knowledge.retrieve(
                who.tenantId,
                who.brandId,
                Set.of(who.chilonzor),
                List.of(locales.length == 0 ? new String[] {"en"} : locales),
                question);
    }

    // ----------------------------------------------------------------- versions

    @Test
    @DisplayName(
            "an entry is created at version 1, and publishing appends version 2 without touching what version 1 said")
    void versionsAreAppendedNeverEdited() {
        EntryView created = parking("en", "Yes, free parking for 20 cars behind the building.");
        assertThat(created.version()).isEqualTo(1);
        assertThat(created.status()).isEqualTo("PUBLISHED");

        EntryView second = knowledge.publishNextVersion(
                a.tenantId,
                a.brandId,
                created.id(),
                1,
                false,
                "Is there parking at your restaurant?",
                "Yes, free parking for 30 cars.",
                "author-2",
                "Capacity grew");

        assertThat(second.version()).isEqualTo(2);
        assertThat(knowledge.versions(a.tenantId, a.brandId, created.id()))
                .extracting(version -> version.version() + ":" + version.answerBody() + ":" + version.authoredBy())
                .containsExactly(
                        "2:Yes, free parking for 30 cars.:author-2",
                        "1:Yes, free parking for 20 cars behind the building.:author-1");
        assertThat(ask(a, "parking?").getFirst().answerBody()).isEqualTo("Yes, free parking for 30 cars.");
    }

    @Test
    @DisplayName(
            "publishing from a stale version is refused with the current one, and two writers racing for one number get exactly one winner")
    void concurrentPublishingHasOneWinner() {
        EntryView created = parking("en", "Free parking.");
        knowledge.publishNextVersion(
                a.tenantId,
                a.brandId,
                created.id(),
                1,
                false,
                "Is there parking?",
                "Free parking, 20 spaces.",
                "author-1",
                "Update");

        assertThatThrownBy(() -> knowledge.publishNextVersion(
                        a.tenantId,
                        a.brandId,
                        created.id(),
                        1,
                        false,
                        "Is there parking?",
                        "Paid parking.",
                        "author-2",
                        "Stale"))
                .isInstanceOfSatisfying(ApiException.class, failure -> {
                    assertThat(failure.errorCode()).isEqualTo(ErrorCode.STALE_VERSION);
                    assertThat(failure.properties()).containsEntry("currentVersion", 2L);
                });
        assertThat(knowledge.get(a.tenantId, a.brandId, created.id()).answerBody())
                .isEqualTo("Free parking, 20 spaces.");
    }

    @Test
    @DisplayName(
            "retiring appends a RETIRED version carrying the last words, the assistant stops using it, and retiring twice is a conflict")
    void retiring() {
        EntryView created = parking("en", "Free parking.");
        assertThat(ask(a, "is there parking")).hasSize(1);

        EntryView retired = knowledge.retire(a.tenantId, a.brandId, created.id(), 1, "author-1", "Closed the lot");

        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.version()).isEqualTo(2);
        assertThat(retired.answerBody()).isEqualTo("Free parking.");
        assertThat(ask(a, "is there parking")).isEmpty();
        assertThat(knowledge.versions(a.tenantId, a.brandId, created.id())).hasSize(2);
        assertThatThrownBy(() -> knowledge.retire(a.tenantId, a.brandId, created.id(), 2, "author-1", "again"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    @DisplayName("every authoring action is an ADR 0027 fact with who, why, and the words before and after")
    void authoringIsAudited() {
        EntryView created = parking("en", "Free parking.");
        knowledge.publishNextVersion(
                a.tenantId,
                a.brandId,
                created.id(),
                1,
                false,
                "Is there parking?",
                "Free parking, 20 spaces.",
                "author-2",
                "Counted the spaces");
        knowledge.retire(a.tenantId, a.brandId, created.id(), 2, "author-3", "Closed");

        AuditTrail.Fact creation = AuditTrail.only(jdbc, "assistant.knowledge.created");
        assertThat(creation.actorSubject()).isEqualTo("author-1");
        assertThat(creation.reason()).isEqualTo("FAQ");
        assertThat(creation.after("answerBody").asString()).isEqualTo("Free parking.");

        AuditTrail.Fact publication = AuditTrail.only(jdbc, "assistant.knowledge.published");
        assertThat(publication.before("answerBody").asString()).isEqualTo("Free parking.");
        assertThat(publication.after("answerBody").asString()).isEqualTo("Free parking, 20 spaces.");
        assertThat(publication.after("version").asInt()).isEqualTo(2);
        assertThat(publication.reason()).isEqualTo("Counted the spaces");

        assertThat(AuditTrail.only(jdbc, "assistant.knowledge.retired")
                        .after("status")
                        .asString())
                .isEqualTo("RETIRED");
    }

    // ----------------------------------------------------------------- isolation

    @Test
    @DisplayName(
            "another tenant's entry is never retrieved, however well it matches, and the asking tenant is told it has none")
    void retrievalNeverCrossesTenants() {
        b.knowledge.create(
                b.tenantId,
                b.brandId,
                null,
                "en",
                "Is there parking at your restaurant?",
                "B's answer: parking at the mall.",
                "b-author",
                "FAQ");
        b.knowledge.create(
                b.tenantId,
                null,
                null,
                "en",
                "Is there parking at your restaurant?",
                "B's tenant-wide answer.",
                "b-author",
                "FAQ");

        assertThat(ask(a, "is there parking at your restaurant"))
                .as("a has written nothing")
                .isEmpty();
        assertThat(ask(b, "is there parking at your restaurant")).hasSize(2);

        // A forged brand id on A's side: B's brand, A's tenant. Tenant is in every statement.
        assertThat(a.knowledge.retrieve(
                        a.tenantId, b.brandId, Set.of(), List.of("en"), "is there parking at your restaurant"))
                .isEmpty();
    }

    @Test
    @DisplayName("an entry id from another tenant is simply not found, from every read and every write")
    void entryIdsAreTenantScoped() {
        EntryView theirs =
                b.knowledge.create(b.tenantId, b.brandId, null, "en", "Is there parking?", "Yes.", "b-author", "FAQ");

        assertThatThrownBy(() -> knowledge.get(a.tenantId, a.brandId, theirs.id()))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> knowledge.versions(a.tenantId, a.brandId, theirs.id()))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> knowledge.publishNextVersion(
                        a.tenantId,
                        a.brandId,
                        theirs.id(),
                        1,
                        false,
                        "Is there parking?",
                        "Hijacked.",
                        "a-author",
                        "x"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> knowledge.retire(a.tenantId, a.brandId, theirs.id(), 1, "a-author", "x"))
                .isInstanceOf(ApiException.class);
        assertThat(b.knowledge.get(b.tenantId, b.brandId, theirs.id()).answerBody())
                .isEqualTo("Yes.");
    }

    @Test
    @DisplayName(
            "scope: a tenant entry reaches every brand, a brand entry only its own, a location entry only a question about that branch")
    void scopes() {
        knowledge.create(
                a.tenantId, null, null, "en", "Do you accept cash?", "Yes, cash is welcome everywhere.", "a", "FAQ");
        knowledge.create(
                a.tenantId, a.brandId, null, "en", "Do you have a kids menu?", "Yes, a kids menu.", "a", "FAQ");
        knowledge.create(
                a.tenantId, a.brandId, a.yunusabad, "en", "Is there parking?", "Yunusabad has parking.", "a", "FAQ");

        UUID otherBrand = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', :slug, 'Other', 'ACTIVE', 0)
                """)
                .param("id", otherBrand)
                .param("tenantId", a.tenantId)
                .param("slug", "other-" + otherBrand)
                .update();

        assertThat(a.knowledge.retrieve(a.tenantId, otherBrand, Set.of(), List.of("en"), "do you accept cash"))
                .as("tenant scope reaches every brand")
                .hasSize(1);
        assertThat(a.knowledge.retrieve(a.tenantId, otherBrand, Set.of(), List.of("en"), "do you have a kids menu"))
                .as("a brand entry does not reach a sibling brand")
                .isEmpty();
        assertThat(a.knowledge.retrieve(a.tenantId, a.brandId, Set.of(a.chilonzor), List.of("en"), "is there parking"))
                .as("a location entry needs the branch to be in scope")
                .isEmpty();
        assertThat(a.knowledge.retrieve(a.tenantId, a.brandId, Set.of(a.yunusabad), List.of("en"), "is there parking"))
                .singleElement()
                .satisfies(match -> {
                    assertThat(match.scope()).isEqualTo("LOCATION");
                    assertThat(match.answerBody()).isEqualTo("Yunusabad has parking.");
                });
    }

    @Test
    @DisplayName(
            "a location outside the brand cannot hold an entry: the database refuses it, and the caller is told so")
    void aForeignLocationIsRefused() {
        assertThatThrownBy(() -> knowledge.create(
                        a.tenantId, a.brandId, b.chilonzor, "en", "Is there parking?", "Yes.", "a", "FAQ"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // ------------------------------------------------------------------ matching

    @Test
    @DisplayName("an entry answers the question it is about in the customer's own words, and not one it is not about")
    void matching() {
        knowledge.create(
                a.tenantId,
                a.brandId,
                null,
                "en",
                "How many people does the family set feed?",
                "The family set feeds four to five people.",
                "a",
                "FAQ");

        assertThat(ask(a, "Does the family set feed four?"))
                .singleElement()
                .satisfies(match -> assertThat(match.answerBody()).contains("four to five"));
        assertThat(ask(a, "how big is the family set")).hasSize(1);
        assertThat(ask(a, "Do you have a vegan menu?")).isEmpty();
        assertThat(ask(a, "Is there parking?")).isEmpty();
    }

    @Test
    @DisplayName(
            "an entry in another language is used when the reply language has none, but the reply language's own comes first")
    void localePreference() {
        knowledge.create(
                a.tenantId, a.brandId, null, "ru", "Есть ли у вас парковка?", "Да, бесплатная парковка.", "a", "FAQ");
        knowledge.create(a.tenantId, a.brandId, null, "en", "Is there parking?", "Yes, free parking.", "a", "FAQ");

        assertThat(ask(a, "parking", "en", "ru"))
                .first()
                .satisfies(match -> assertThat(match.locale()).isEqualTo("en"));
        assertThat(ask(a, "парковка", "ru", "en"))
                .first()
                .satisfies(match -> assertThat(match.locale()).isEqualTo("ru"));
    }

    // --------------------------------------------------------------- validation

    @Test
    @DisplayName(
            "an entry that names nothing a customer could ask about, or in a language the platform does not answer in, is refused")
    void validation() {
        assertThatThrownBy(
                        () -> knowledge.create(a.tenantId, a.brandId, null, "en", "Do you have?", "Yes.", "a", "FAQ"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() ->
                        knowledge.create(a.tenantId, a.brandId, null, "fr", "Is there parking?", "Oui.", "a", "FAQ"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () -> knowledge.create(a.tenantId, a.brandId, null, "en", "Is there parking?", " ", "a", "FAQ"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> knowledge.create(
                        a.tenantId, a.brandId, null, "en", "Is there parking?", "x".repeat(2001), "a", "FAQ"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() ->
                        knowledge.create(a.tenantId, null, a.chilonzor, "en", "Is there parking?", "Yes.", "a", "FAQ"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the knowledge store cannot rewrite or delete: no statement in the stores is an UPDATE or a DELETE")
    void theStoresAreAppendOnly() throws Exception {
        for (String store : List.of("JdbcKnowledgeStore", "JdbcTurnStore")) {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/uz/horecaos/platform/assistant/infrastructure/persistence/" + store + ".java"));
            assertThat(source).as(store).doesNotContain("UPDATE assistant").doesNotContain("DELETE FROM assistant");
        }
        assertThat(jdbc.sql("""
                        SELECT privilege_type FROM information_schema.role_table_grants
                         WHERE table_schema = 'assistant' AND grantee = 'horecaos_application'
                         ORDER BY table_name, privilege_type
                        """).query(String.class).list())
                .as("the application role holds SELECT and INSERT on all three tables and nothing more")
                .containsOnly("SELECT", "INSERT")
                .hasSize(6);
    }

    @SuppressWarnings("unused")
    private static ConversationParticipant.Author unused() {
        return ConversationParticipant.Author.CUSTOMER;
    }

    @SuppressWarnings("unused")
    private static Duration unusedDuration() {
        return Duration.ZERO;
    }
}
