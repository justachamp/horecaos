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
import java.util.regex.Pattern;
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
import uz.horecaos.platform.customers.application.CustomerErasureService;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0029's erasure guard: the next module that stores a phone number cannot do it silently.
 *
 * <p>The inventory is read from the migrated database's own catalog, not from a list somebody keeps: every
 * ordinary table that has a column naming a customer account (by name, or by a foreign key to {@code
 * customer.customer_accounts}) or a column that holds a PERSONAL-class value (the envelope-encrypted
 * {@code *_encrypted} / {@code protected_*} families) must be named by a participant's {@link ErasesCustomerData}
 * or by {@link ErasureInventoryExemptions}, with the reason it does not need one. A table in neither set fails
 * the build with its name, so the person adding a column is told the question rather than left to remember it.
 *
 * <p>What this cannot prove is that a participant erases what it declares; each participant has its own test for
 * that. What it proves is the property the audit of 2026-08-26 kept finding missing: that somebody decided.
 */
class ErasureInventoryTests {

    /** Schemas that are the database's own. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("public", "information_schema");

    /** A column that names a customer account or another stable key to the same person. */
    private static final Pattern CUSTOMER_KEY_COLUMN = Pattern.compile("(^|_)customer(_account)?_id$"
            + "|(^|_)account_id$"
            + "|^recipient_account_id$"
            + "|(^|_)customer_pseudonym$"
            + "|(^|_)customer_subject_hash$"
            + "|^guest_reference_hash$"
            + "|phone_lookup_hash$"
            + "|(^|_)chat_id$");

    /** A column that holds a PERSONAL-class value under the envelope (ADR 0029), by the schema's own naming. */
    private static final Pattern PERSONAL_COLUMN =
            Pattern.compile("_encrypted$|_protected$|^protected_|^encrypted_|^body_protected$");

    /**
     * Tables that carry a person's words in a column no naming rule can recognise, found by reading the code. Each is a
     * claim the guard keeps honest: it must exist, and it needs an answer like any other.
     */
    private static final Map<String, String> PLAINTEXT_CARRIERS = Map.of(
            "ordering.order_amendment_commands",
            "payload_json (CHANGE_CONTACT and CHANGE_DELIVERY_ADDRESS carry a recipient name, phone and address)");

    private static TestDatabase.Handle db;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the erasure inventory");
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
            "every table with a customer key or a PERSONAL column is erased by a participant or exempt, with a reason")
    void everyTableHasAnAnswer() {
        Set<String> unanswered = new TreeSet<>(inventory().keySet());
        unanswered.removeAll(declaredTables().keySet());
        unanswered.removeAll(ErasureInventoryExemptions.REASONS.keySet());

        Map<String, Set<String>> inventory = inventory();
        List<String> report = unanswered.stream()
                .map(table -> table + "  (" + String.join(", ", inventory.get(table)) + ")")
                .toList();
        assertThat(report)
                .as("tables holding a customer key or a PERSONAL value that no participant erases and no exemption"
                        + " explains: add the table to a participant's @ErasesCustomerData, or to"
                        + " ErasureInventoryExemptions with the reason it needs no erasure")
                .isEmpty();
    }

    @Test
    @DisplayName("a declared table and an exemption never name the same table, and neither names a table that is gone")
    void declarationsAreNotStale() {
        Set<String> real = allTables();
        Map<String, String> declared = declaredTables();

        List<String> staleDeclarations = declared.keySet().stream()
                .filter(table -> !real.contains(table))
                .map(table -> table + " (declared by " + declared.get(table) + ")")
                .toList();
        assertThat(staleDeclarations)
                .as("tables a participant declares that do not exist in the migrated schema")
                .isEmpty();

        List<String> staleExemptions = ErasureInventoryExemptions.REASONS.keySet().stream()
                .filter(table -> !real.contains(table))
                .toList();
        assertThat(staleExemptions)
                .as("exempted tables that do not exist in the migrated schema")
                .isEmpty();

        Set<String> both = new TreeSet<>(declared.keySet());
        both.retainAll(ErasureInventoryExemptions.REASONS.keySet());
        assertThat(both)
                .as("a table cannot be erased by a participant and exempt from erasure at once")
                .isEmpty();

        Set<String> exemptButUnneeded = new TreeSet<>(ErasureInventoryExemptions.REASONS.keySet());
        exemptButUnneeded.removeAll(inventory().keySet());
        assertThat(exemptButUnneeded)
                .as("exempted tables that no longer carry a customer key or a PERSONAL column: drop the exemption")
                .isEmpty();
    }

    @Test
    @DisplayName("every exemption says why, in a sentence")
    void everyExemptionHasAReason() {
        List<String> thin = ErasureInventoryExemptions.REASONS.entrySet().stream()
                .filter(entry -> entry.getValue().strip().length() < 40)
                .map(Map.Entry::getKey)
                .toList();
        assertThat(thin).as("exemptions whose reason is too short to be one").isEmpty();
    }

    @Test
    @DisplayName("every CustomerErasureParticipant is a bean and carries its declaration")
    void everyParticipantDeclaresItself() {
        List<String> problems = new ArrayList<>();
        for (Class<?> participant : participantClasses()) {
            if (participant.getAnnotation(ErasesCustomerData.class) == null) {
                problems.add(participant.getName() + " has no @ErasesCustomerData");
            }
            if (participant.getAnnotation(Component.class) == null
                    && participant.getAnnotation(Service.class) == null) {
                problems.add(participant.getName() + " is not a @Component/@Service, so nothing would call it");
            }
        }
        assertThat(problems).isEmpty();
        assertThat(participantClasses())
                .as("the scan must find the participants at all, or the guard above is asserting over nothing")
                .hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the guard can fail: a new table with a customer key is found, and unanswered")
    void theGuardCanFail() {
        jdbc.sql("CREATE TABLE customer.zz_guard_probe (id uuid PRIMARY KEY, customer_account_id uuid)")
                .update();
        jdbc.sql("CREATE TABLE customer.zz_guard_probe_secret (id uuid PRIMARY KEY, note_encrypted text)")
                .update();
        try {
            Set<String> found = inventory().keySet();
            assertThat(found).contains("customer.zz_guard_probe", "customer.zz_guard_probe_secret");

            Set<String> unanswered = new TreeSet<>(found);
            unanswered.removeAll(declaredTables().keySet());
            unanswered.removeAll(ErasureInventoryExemptions.REASONS.keySet());
            assertThat(unanswered).contains("customer.zz_guard_probe", "customer.zz_guard_probe_secret");
        } finally {
            jdbc.sql("DROP TABLE customer.zz_guard_probe").update();
            jdbc.sql("DROP TABLE customer.zz_guard_probe_secret").update();
        }
    }

    // ------------------------------------------------------------------------------ what the database says

    /** Every ordinary, non-partition table, as {@code schema.table}. */
    private static Set<String> allTables() {
        return new TreeSet<>(jdbc.sql("""
                        SELECT n.nspname || '.' || c.relname
                          FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition
                           AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                           AND n.nspname NOT IN ('public', 'information_schema')
                        """).query(String.class).list());
    }

    /** Table to the columns that put it in the inventory. */
    private static Map<String, Set<String>> inventory() {
        Map<String, Set<String>> found = new TreeMap<>();
        jdbc.sql("""
                        SELECT n.nspname || '.' || c.relname AS tbl, a.attname AS col, format_type(a.atttypid, NULL) AS typ
                          FROM pg_attribute a
                          JOIN pg_class c ON c.oid = a.attrelid
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE a.attnum > 0 AND NOT a.attisdropped
                           AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                           AND n.nspname NOT LIKE 'pg\\_%' ESCAPE '\\'
                           AND n.nspname NOT IN ('public', 'information_schema')
                        """)
                .query((row, number) -> new String[] {row.getString("tbl"), row.getString("col"), row.getString("typ")})
                .list()
                .forEach(column -> {
                    boolean text = column[2].equals("text") || column[2].startsWith("character varying");
                    boolean customerKey = CUSTOMER_KEY_COLUMN.matcher(column[1]).find();
                    boolean personal =
                            text && PERSONAL_COLUMN.matcher(column[1]).find();
                    if (customerKey || personal) {
                        found.computeIfAbsent(column[0], key -> new TreeSet<>()).add(column[1]);
                    }
                });
        PLAINTEXT_CARRIERS.forEach((table, why) ->
                found.computeIfAbsent(table, key -> new TreeSet<>()).add(why));
        // A foreign key to the customer account is a customer key whatever the column is called.
        jdbc.sql("""
                        SELECT k.conrelid::regclass::text AS tbl, a.attname AS col
                          FROM pg_constraint k
                          JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = ANY (k.conkey)
                         WHERE k.contype = 'f' AND k.confrelid = 'customer.customer_accounts'::regclass
                           AND a.attname <> 'tenant_id'
                        """)
                .query((row, number) -> new String[] {row.getString("tbl"), row.getString("col")})
                .list()
                .forEach(column -> {
                    if (!column[0].startsWith("public.")) {
                        found.computeIfAbsent(column[0], key -> new TreeSet<>()).add(column[1]);
                    }
                });
        return found;
    }

    // --------------------------------------------------------------------- what the code declares

    /** Declared table to the participant that declares it. */
    private static Map<String, String> declaredTables() {
        Map<String, String> declared = new TreeMap<>();
        for (Class<?> participant : participantClasses()) {
            ErasesCustomerData declaration = participant.getAnnotation(ErasesCustomerData.class);
            if (declaration == null) {
                continue;
            }
            for (String table : declaration.tables()) {
                String previous = declared.put(table, declaration.name());
                if (previous != null && !previous.equals(declaration.name())) {
                    // Two participants may legitimately touch one table (customers and a lead desk), but the
                    // proof reads better when they say so; keep both names.
                    declared.put(table, previous + "+" + declaration.name());
                }
            }
        }
        for (String table : CustomerErasureService.OWN_TABLES) {
            declared.put(table, "customers");
        }
        return declared;
    }

    /** The production participants: package-private so the behaviour suite can insist on running each of them. */
    static List<Class<?>> participantClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(CustomerErasureParticipant.class));
        List<Class<?>> classes = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("uz.horecaos.platform")) {
            try {
                Class<?> type = Class.forName(candidate.getBeanClassName());
                // Production participants only: a test double implementing the seam is not a bean anything calls.
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
