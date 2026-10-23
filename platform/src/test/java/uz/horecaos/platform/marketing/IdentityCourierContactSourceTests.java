package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.marketing.api.CourierContactSource;
import uz.horecaos.platform.marketing.application.IdentityCourierContactSource;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCourierBroadcastStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0182, D15: a courier's number is read from the identity provider when a message needs it, with a
 * recorded purpose, and is kept, logged and audited nowhere.
 *
 * <p>The identity provider is a fake that holds what a phone-first account holds: a {@code phone}
 * attribute or, failing that, a username that is the number. The properties under test are the ones
 * that matter to a courier and to the owner: nothing is read until the owner has said it may be, a
 * number is only ever one this platform sends to, an outage is never mistaken for «nobody has a
 * number», and the audit trail names how many and never which.
 */
class IdentityCourierContactSourceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private FakeAccounts accounts;
    private RecordingAudit audit;
    private JdbcCourierBroadcastStore store;
    private UUID courierType;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required");
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
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers, fulfillment.courier_groups CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'courier-contacts', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        courierType = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :tenantId, 'BIKE', 'Bicycle', 'BICYCLE')
                """).param("id", courierType).param("tenantId", TENANT).update();
        accounts = new FakeAccounts();
        audit = new RecordingAudit();
        store = new JdbcCourierBroadcastStore(jdbc);
    }

    @Test
    @DisplayName("until the owner has said a courier's number may be used, nothing is read from the identity provider")
    void darkUntilTheOwnerSaysSo() {
        UUID courier = courier("a");
        accounts.phone("a", "+998901112233");
        IdentityCourierContactSource source = source(false);

        assertThat(source.isWired()).isFalse();
        assertThat(source.addressable(TENANT, List.of(courier), "COURIER_BROADCAST_SEND"))
                .isEmpty();
        assertThat(source.number(TENANT, courier, "COURIER_BROADCAST_DELIVERY")).isEmpty();
        assertThat(accounts.reads).as("the identity provider was never asked").isEmpty();
        assertThat(audit.facts).isEmpty();
    }

    @Test
    @DisplayName("a courier is addressable when the identity provider holds a number the platform sends to")
    void aUsableNumberIsAddressable() {
        UUID attribute = courier("attr");
        UUID national = courier("national");
        UUID username = courier("username");
        UUID foreign = courier("foreign");
        UUID junk = courier("junk");
        UUID none = courier("none");
        UUID missing = courier("missing");
        accounts.phone("attr", "+998 90 111-22-33");
        accounts.phone("national", "90 111 22 44");
        accounts.username("username", "998901112255");
        accounts.phone("foreign", "+79161234567");
        accounts.phone("junk", "call me maybe");
        accounts.noPhone("none");

        IdentityCourierContactSource source = source(true);
        var addressable = source.addressable(
                TENANT, List.of(attribute, national, username, foreign, junk, none, missing), "COURIER_BROADCAST_SEND");

        assertThat(addressable)
                .as("the attribute in any spelling, the national nine digits, and a phone-first username; "
                        + "not a foreign number (SMS pumping), not text, not an account with none, not no account")
                .containsExactlyInAnyOrder(attribute, national, username);
    }

    @Test
    @DisplayName("the whole check is one read of the identity provider, with its purpose")
    void oneCallPerCheck() {
        UUID first = courier("one");
        UUID second = courier("two");
        accounts.phone("one", "+998901112233");
        accounts.phone("two", "+998901112244");

        source(true).addressable(TENANT, List.of(first, second), "COURIER_BROADCAST_SEND");

        assertThat(accounts.reads).containsExactly("COURIER_BROADCAST_SEND:one,two");
    }

    @Test
    @DisplayName("one courier's number comes back in the form the gateway takes, for one message")
    void aNumberForOneMessage() {
        UUID courier = courier("a");
        accounts.phone("a", "90 111 22 33");

        assertThat(source(true).number(TENANT, courier, "COURIER_BROADCAST_DELIVERY"))
                .contains("+998901112233");
        assertThat(source(true).number(TENANT, UUID.randomUUID(), "COURIER_BROADCAST_DELIVERY"))
                .as("a courier of no tenant of ours has no number to give")
                .isEmpty();
    }

    @Test
    @DisplayName("an identity provider that cannot be asked is an outage, never «nobody has a number»")
    void anOutageIsNotNoNumber() {
        UUID courier = courier("a");
        accounts.down = true;
        IdentityCourierContactSource source = source(true);

        assertThatThrownBy(() -> source.addressable(TENANT, List.of(courier), "COURIER_BROADCAST_SEND"))
                .isInstanceOf(CourierContactSource.UnavailableException.class);
        assertThatThrownBy(() -> source.number(TENANT, courier, "COURIER_BROADCAST_DELIVERY"))
                .isInstanceOf(CourierContactSource.UnavailableException.class);
    }

    @Test
    @DisplayName("the audit trail says how many couriers were checked and which one was read, and never a number")
    void theAuditTrailNamesNoNumber() {
        UUID courier = courier("a");
        UUID other = courier("b");
        accounts.phone("a", "+998901112233");
        accounts.phone("b", "+998901112244");
        IdentityCourierContactSource source = source(true);

        source.addressable(TENANT, List.of(courier, other), "COURIER_BROADCAST_SEND");
        source.number(TENANT, courier, "COURIER_BROADCAST_DELIVERY");

        assertThat(audit.facts)
                .extracting(AuditFact::actionCode)
                .containsExactly("MARKETING_COURIER_CONTACTS_CHECKED", "MARKETING_COURIER_CONTACT_REVEALED");
        assertThat(audit.facts.get(1).targetId()).isEqualTo(courier);
        assertThat(audit.facts.stream().map(Object::toString).collect(Collectors.joining()))
                .doesNotContain("901112233")
                .doesNotContain("901112244");
        assertThat(audit.facts.getFirst().reason()).contains("2 couriers").contains("COURIER_BROADCAST_SEND");
    }

    // ---------------------------------------------------------------- fixtures

    private IdentityCourierContactSource source(boolean cleared) {
        return new IdentityCourierContactSource(accounts, store, audit, CLOCK, cleared);
    }

    private UUID courier(String subject) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id, principal_subject,
                    display_reference, protected_full_name, status, version)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'encrypted-placeholder', 'ACTIVE', 1)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("typeId", courierType)
                .param("subject", subject)
                .param("reference", "K-" + subject)
                .update();
        return id;
    }

    /** A phone-first account as the identity provider holds it. */
    private static final class FakeAccounts implements StaffAccounts {
        private final Map<String, String> phones = new LinkedHashMap<>();
        private final Map<String, String> usernames = new LinkedHashMap<>();
        final List<String> reads = new CopyOnWriteArrayList<>();
        boolean down;

        void phone(String subject, String number) {
            phones.put(subject, number);
        }

        void username(String subject, String username) {
            usernames.put(subject, username);
        }

        void noPhone(String subject) {
            usernames.put(subject, subject);
        }

        @Override
        public Map<String, String> phones(Collection<String> subjectIds, String purpose) {
            if (down) {
                throw new DirectoryUnavailableException("down", new IllegalStateException());
            }
            List<String> sorted = new ArrayList<>(subjectIds);
            java.util.Collections.sort(sorted);
            reads.add(purpose + ":" + String.join(",", sorted));
            Map<String, String> found = new LinkedHashMap<>();
            for (String subject : subjectIds) {
                String number = phones.get(subject);
                if (number == null
                        && usernames.get(subject) != null
                        && usernames.get(subject).matches("\\+?\\d{9,15}")) {
                    number = usernames.get(subject);
                }
                if (number != null) {
                    found.put(subject, number);
                }
            }
            return found;
        }

        @Override
        public Optional<StaffAccount> find(String subjectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void completeSetup(String subjectId, String firstName, String lastName, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setPassword(String subjectId, String password) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void logoutEverywhere(String subjectId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingAudit implements AuditRecorder {
        final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
