package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0029's export guard, the twin of {@link ErasureInventoryTests}: the next module that stores a phone number cannot do it
 * silently, and cannot keep it from the person it belongs to without saying why.
 *
 * <p>The tables are the ones the erasure guard already reads from the migrated database's own catalog (a customer key or a
 * PERSONAL-class column) together with every table an erasure participant declares. Each must be named by a participant's
 * {@link ExportsCustomerData} or by {@link ExportInventoryExemptions} with the reason it is not part of a subject-access
 * answer. A table in neither set fails the build with its name.
 *
 * <p>What this cannot prove is that a participant exports what it declares; {@code CustomerExportParticipantsTests} runs each
 * as the application role over real rows. What it proves is that somebody decided.
 */
class ExportInventoryTests {

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the export inventory");
        db = TestDatabase.migrated();
        jdbc = JdbcClient.create(db.dataSource());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    @DisplayName(
            "every table with a customer key or a PERSONAL column is exported by a participant or exempt, with a reason")
    void everyTableHasAnAnswer() {
        Set<String> unanswered = new TreeSet<>(required());
        unanswered.removeAll(exportedTables().keySet());
        unanswered.removeAll(ExportInventoryExemptions.REASONS.keySet());
        assertThat(unanswered)
                .as(
                        "tables holding a customer's data that no export participant reads and no exemption explains: add the"
                                + " table to a participant's @ExportsCustomerData, or to ExportInventoryExemptions with the reason"
                                + " it is not part of a subject-access answer")
                .isEmpty();
    }

    @Test
    @DisplayName("an exported table and an exemption never name the same table, and neither names a table that is gone")
    void declarationsAreNotStale() {
        Set<String> real = ErasureInventoryTests.allTables(jdbc);
        Map<String, String> exported = exportedTables();

        assertThat(exported.keySet().stream()
                        .filter(table -> !real.contains(table))
                        .map(table -> table + " (declared by " + exported.get(table) + ")")
                        .toList())
                .as("tables an export participant declares that do not exist in the migrated schema")
                .isEmpty();
        assertThat(ExportInventoryExemptions.REASONS.keySet().stream()
                        .filter(table -> !real.contains(table))
                        .toList())
                .as("exempted tables that do not exist in the migrated schema")
                .isEmpty();

        Set<String> both = new TreeSet<>(exported.keySet());
        both.retainAll(ExportInventoryExemptions.REASONS.keySet());
        assertThat(both)
                .as("a table cannot be exported by a participant and exempt from export at once")
                .isEmpty();

        Set<String> exemptButUnneeded = new TreeSet<>(ExportInventoryExemptions.REASONS.keySet());
        exemptButUnneeded.removeAll(required());
        assertThat(exemptButUnneeded)
                .as("exempted tables that no longer carry a customer key or a PERSONAL column: drop the exemption")
                .isEmpty();
    }

    @Test
    @DisplayName("every exemption says why, in a sentence")
    void everyExemptionHasAReason() {
        assertThat(ExportInventoryExemptions.REASONS.entrySet().stream()
                        .filter(entry -> entry.getValue().strip().length() < 60)
                        .map(Map.Entry::getKey)
                        .toList())
                .as("exemptions whose reason is too short to be one")
                .isEmpty();
    }

    @Test
    @DisplayName("every CustomerExportParticipant is a bean and carries its declaration, under a name of its own")
    void everyParticipantDeclaresItself() {
        List<String> problems = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        for (Class<?> participant : participantClasses()) {
            ExportsCustomerData declaration = participant.getAnnotation(ExportsCustomerData.class);
            if (declaration == null) {
                problems.add(participant.getName() + " has no @ExportsCustomerData");
            } else if (!names.add(declaration.name())) {
                problems.add(participant.getName() + " reuses the section name " + declaration.name());
            }
            if (participant.getAnnotation(Component.class) == null
                    && participant.getAnnotation(Service.class) == null) {
                problems.add(participant.getName() + " is not a @Component/@Service, so nothing would call it");
            }
        }
        assertThat(problems).isEmpty();
        assertThat(participantClasses())
                .as("the scan must find the participants at all, or the guard above is asserting over nothing")
                .hasSizeGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("the guard can fail: a new table with a customer key is found, and unanswered")
    void theGuardCanFail() {
        jdbc.sql("CREATE TABLE customer.zz_export_probe (id uuid PRIMARY KEY, customer_account_id uuid)")
                .update();
        try {
            assertThat(required()).contains("customer.zz_export_probe");
            Set<String> unanswered = new TreeSet<>(required());
            unanswered.removeAll(exportedTables().keySet());
            unanswered.removeAll(ExportInventoryExemptions.REASONS.keySet());
            assertThat(unanswered).contains("customer.zz_export_probe");
        } finally {
            jdbc.sql("DROP TABLE customer.zz_export_probe").update();
        }
    }

    // --------------------------------------------------------------------------------------- what is asked of the
    // build

    /** Every table the erasure guard knows about, whether the catalog found it or a participant declared it. */
    private static Set<String> required() {
        Set<String> required =
                new TreeSet<>(ErasureInventoryTests.inventory(jdbc).keySet());
        required.addAll(ErasureInventoryTests.declaredTables().keySet());
        return required;
    }

    /** Exported table to the participant that declares it. */
    private static Map<String, String> exportedTables() {
        Map<String, String> declared = new TreeMap<>();
        for (Class<?> participant : participantClasses()) {
            ExportsCustomerData declaration = participant.getAnnotation(ExportsCustomerData.class);
            if (declaration == null) {
                continue;
            }
            for (String table : declaration.tables()) {
                String previous = declared.put(table, declaration.name());
                if (previous != null && !previous.equals(declaration.name())) {
                    declared.put(table, previous + "+" + declaration.name());
                }
            }
        }
        return declared;
    }

    /** The production participants; package-private so the behaviour suite can insist on running each of them. */
    static List<Class<?>> participantClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(CustomerExportParticipant.class));
        List<Class<?>> classes = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("uz.horecaos.platform")) {
            try {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                boolean fromTests = type.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .getPath()
                        .contains("test-classes");
                if (!type.isInterface() && !fromTests) {
                    classes.add(type);
                }
            } catch (ClassNotFoundException missing) {
                throw new IllegalStateException(missing);
            }
        }
        classes.sort(Comparator.comparing(Class::getName));
        return new ArrayList<>(new LinkedHashSet<>(classes));
    }
}
