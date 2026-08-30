package uz.horecaos.platform.iam.api.protection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
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
}
