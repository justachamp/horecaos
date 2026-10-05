package uz.horecaos.platform.iam.api.protection;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner.Finding;
import uz.horecaos.platform.iam.api.protection.ClassificationScanner.Source;

/**
 * ADR 0029 classification.
 *
 * <p>The two sources exist for different failure modes: a declaration survives
 * renaming and covers fields whose names give nothing away, while the heuristic
 * catches what nobody remembered to annotate.
 */
class ClassificationScannerTests {

    @Test
    void findsADeclaredClassification() {
        var findings = ClassificationScanner.scan(WithDeclaration.class, "Sample");

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().path()).isEqualTo("Sample.recipientHandle");
        assertThat(findings.getFirst().source()).isEqualTo(Source.DECLARED);
    }

    @Test
    void findsAFieldNobodyAnnotated() {
        var findings = ClassificationScanner.scan(WithoutDeclaration.class, "Sample");

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().path()).isEqualTo("Sample.customerPhone");
        assertThat(findings.getFirst().source()).isEqualTo(Source.NAME_HEURISTIC);
    }

    @Test
    void aDeclarationCatchesWhatANameNeverWould() {
        var findings = ClassificationScanner.scan(WithDeclaration.class, "Sample");

        assertThat(findings.getFirst().path())
                .as("a heuristic cannot know that recipientHandle holds a phone number")
                .contains("recipientHandle");
    }

    @Test
    void aDeclarationBeatsTheHeuristicRatherThanAddingToIt() {
        var findings = ClassificationScanner.scan(DeclaredPublic.class, "Sample");

        assertThat(findings)
                .as("a reviewed declaration is authoritative; a brand's published address is not personal")
                .isEmpty();
    }

    @Test
    void followsNestedRecords() {
        var findings = ClassificationScanner.scan(Nested.class, "Sample");

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().path()).isEqualTo("Sample.recipient.email");
    }

    @Test
    void classifyingATypeCoversEveryComponent() {
        var findings = ClassificationScanner.scan(WithClassifiedType.class, "Sample");

        assertThat(findings).extracting(ClassificationScanner.Finding::path).containsExactly("Sample.postal");
    }

    @Test
    void aCleanTypeProducesNothing() {
        assertThat(ClassificationScanner.scan(Clean.class, "Sample")).isEmpty();
    }

    @Test
    void internalAndPublicClassesDoNotRequireEncryption() {
        assertThat(DataClass.INTERNAL.requiresEncryption()).isFalse();
        assertThat(DataClass.PUBLIC.requiresEncryption()).isFalse();
        assertThat(DataClass.PERSONAL.requiresEncryption()).isTrue();
        assertThat(DataClass.FINANCIAL.requiresEncryption()).isTrue();
    }

    // ----------------------------------------------------- what a collection holds

    @Test
    void findsAClassifiedFieldInsideAListOfRecords() {
        var findings = ClassificationScanner.scan(WithRows.class, "Sample");

        assertThat(findings)
                .as("a list of records holds those records; the first scanner read the component's "
                        + "erased class, saw List, and stopped, so every list response scanned as clean")
                .extracting(Finding::path)
                .containsExactly("Sample.rows[].recipientHandle");
        assertThat(findings.getFirst().source()).isEqualTo(Source.DECLARED);
        assertThat(findings.getFirst().dataClass()).isEqualTo(DataClass.PERSONAL);
    }

    @Test
    void findsAFieldNobodyAnnotatedInsideAList() {
        var findings = ClassificationScanner.scan(WithContacts.class, "Sample");

        assertThat(findings).extracting(Finding::path).containsExactly("Sample.contacts[].email");
        assertThat(findings.getFirst().source()).isEqualTo(Source.NAME_HEURISTIC);
    }

    @Test
    void followsSetsMapValuesOptionalsAndArraysToo() {
        assertThat(ClassificationScanner.scan(WithSet.class, "Sample"))
                .extracting(Finding::path)
                .containsExactly("Sample.rows[].recipientHandle");
        assertThat(ClassificationScanner.scan(WithMapValues.class, "Sample"))
                .extracting(Finding::path)
                .containsExactly("Sample.byKey[].recipientHandle");
        assertThat(ClassificationScanner.scan(WithOptional.class, "Sample"))
                .extracting(Finding::path)
                .containsExactly("Sample.maybe[].recipientHandle");
        assertThat(ClassificationScanner.scan(WithArray.class, "Sample"))
                .extracting(Finding::path)
                .containsExactly("Sample.rows[].recipientHandle");
    }

    @Test
    void followsAListInsideAListInsideARecord() {
        assertThat(ClassificationScanner.scan(WithGroups.class, "Sample"))
                .extracting(Finding::path)
                .containsExactly("Sample.groups[][].recipientHandle");
    }

    @Test
    void aWildcardCollectionIsReadThroughItsBound() {
        assertThat(ClassificationScanner.scan(WithWildcard.class, "Sample"))
                .extracting(Finding::path)
                .containsExactly("Sample.rows[].recipientHandle");
    }

    @Test
    void aListOfScalarsIsClean() {
        assertThat(ClassificationScanner.scan(WithIdentifiers.class, "Sample"))
                .as("a list of order ids says nothing about a person")
                .isEmpty();
    }

    @Test
    void aDeclarationInsideAListStillBeatsTheHeuristic() {
        assertThat(ClassificationScanner.scan(WithPublicRows.class, "Sample"))
                .as("a reviewed declaration is authoritative inside a collection exactly as it is outside one")
                .isEmpty();
    }

    @Test
    void aClassifiedTypeInsideAListIsReportedAtTheCollection() {
        assertThat(ClassificationScanner.scan(WithPostals.class, "Sample"))
                .extracting(Finding::path)
                .containsExactly("Sample.postals");
    }

    @Test
    void twoListsOfTheSameTypeAreTwoPaths() {
        assertThat(ClassificationScanner.scan(WithTwoLists.class, "Sample"))
                .as("only an ancestor stops the walk; a type reached twice is two places a reviewer reads")
                .extracting(Finding::path)
                .containsExactlyInAnyOrder("Sample.current[].recipientHandle", "Sample.previous[].recipientHandle");
    }

    @Test
    void aRecordThatContainsItselfThroughAListStillTerminates() {
        assertThat(ClassificationScanner.scan(Tree.class, "Sample"))
                .extracting(Finding::path)
                .containsExactly("Sample.phone");
    }

    @Test
    void aGenericRecordIsReadWithItsArgumentsAndNotWithoutThem() {
        Type paged = componentType(WithPage.class, "page");

        assertThat(ClassificationScanner.scan(paged, "Page"))
                .as("a page of rows holds rows")
                .extracting(Finding::path)
                .containsExactly("Page.items[].recipientHandle");
        assertThat(ClassificationScanner.scan(Page.class, "Page"))
                .as("read bare, Page<T> says nothing about T: which is why a caller passes the "
                        + "parameterised type whenever it has one")
                .isEmpty();
        assertThat(ClassificationScanner.scan(componentType(WithPage.class, "cleanPage"), "Page"))
                .isEmpty();
    }

    @Test
    void anEnvelopeAroundASingleRecordIsReadThroughToIt() {
        assertThat(ClassificationScanner.scan(componentType(WithPage.class, "wrapped"), "Envelope"))
                .extracting(Finding::path)
                .containsExactly("Envelope.payload.recipientHandle");
    }

    @Test
    void theClassScanAndTheTypeScanAgreeOnAPlainRecord() {
        assertThat(ClassificationScanner.scan((Type) Nested.class, "Sample"))
                .isEqualTo(ClassificationScanner.scan(Nested.class, "Sample"));
    }

    private static Type componentType(Class<? extends Record> holder, String name) {
        for (RecordComponent component : holder.getRecordComponents()) {
            if (component.getName().equals(name)) {
                return component.getGenericType();
            }
        }
        throw new IllegalArgumentException("No component " + name + " on " + holder);
    }

    private record WithDeclaration(
            UUID id,

            @Classified(value = DataClass.PERSONAL, reason = "a phone number under another name")
            String recipientHandle) {}

    private record WithoutDeclaration(UUID id, String customerPhone) {}

    private record DeclaredPublic(
            UUID id,

            @Classified(value = DataClass.PUBLIC, reason = "a brand's published contact address")
            String address) {}

    private record Contact(String email) {}

    private record Nested(UUID id, Contact recipient) {}

    @Classified(value = DataClass.PERSONAL, reason = "every component is part of one address")
    private record PostalAddress(String line1, String city) {}

    private record WithClassifiedType(UUID id, PostalAddress postal) {}

    private record Clean(UUID id, String status, int quantity) {}

    private record Row(
            UUID id,

            @Classified(value = DataClass.PERSONAL, reason = "a handle")
            String recipientHandle) {}

    private record PublicRow(
            UUID id,

            @Classified(value = DataClass.PUBLIC, reason = "published")
            String address) {}

    private record NamedContact(String email) {}

    private record WithRows(UUID id, List<Row> rows) {}

    private record WithContacts(List<NamedContact> contacts) {}

    private record WithSet(Set<Row> rows) {}

    private record WithMapValues(Map<String, Row> byKey) {}

    private record WithOptional(Optional<Row> maybe) {}

    private record WithArray(Row[] rows) {}

    private record WithGroups(List<List<Row>> groups) {}

    private record WithWildcard(List<? extends Row> rows) {}

    private record WithIdentifiers(List<UUID> orderIds, Map<String, Long> totalsByStatus) {}

    private record WithPublicRows(List<PublicRow> rows) {}

    private record WithPostals(List<PostalAddress> postals) {}

    private record WithTwoLists(List<Row> current, List<Row> previous) {}

    private record Tree(String phone, List<Tree> children) {}

    private record Page<T>(List<T> items, String cursor) {}

    private record Envelope<T>(T payload) {}

    private record WithPage(Page<Row> page, Page<UUID> cleanPage, Envelope<Row> wrapped) {}
}
