package uz.horecaos.platform.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.api.CustomerAccountRef;
import uz.horecaos.platform.customers.api.CustomerIdentityPolicy;
import uz.horecaos.platform.customers.application.CustomerIdentityService;
import uz.horecaos.platform.customers.application.CustomerSession;
import uz.horecaos.platform.customers.application.CustomerSessionService;
import uz.horecaos.platform.customers.application.CustomerSessionService.Established;
import uz.horecaos.platform.customers.application.CustomerSessionService.Resolution;
import uz.horecaos.platform.customers.application.CustomerSessionStore;
import uz.horecaos.platform.customers.application.CustomerVerificationService;
import uz.horecaos.platform.customers.application.CustomerVerificationService.Redemption;
import uz.horecaos.platform.customers.domain.CustomerSessionToken;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore.AccountRow;

/**
 * The session itself: what it is, when it stops being one, and what it reaches
 * (ADR 0051).
 *
 * <p>No database. The three things worth asserting here are a hash, a clock and a
 * comparison, and none of them is easier to be sure of through a container.
 *
 * <p><strong>The clock is advanced.</strong> Every expiry assertion below moves
 * time forward rather than constructing a session that was already old — a
 * lifetime asserted without advancing a clock is asserted against an instant, and
 * would pass against an implementation that never expired anything.
 */
class CustomerSessionTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID SIBLING_BRAND = UUID.randomUUID();
    // Fixed, not random: theActorSubjectIsSafe asserts the subject carries no
    // phone-shaped digit run ("998"), and a random UUID coincidentally contains
    // that sequence roughly once in a few hundred runs — a flake with no bug.
    // This literal is chosen to contain no "998"; keep it that way.
    private static final UUID ACCOUNT = UUID.fromString("4ac0ff11-5e55-401c-8d0a-2b6c7e1f03d5");
    private static final Duration TTL = Duration.ofDays(30);

    private TickingClock clock;
    private InMemorySessionStore sessions;
    private CustomerVerificationService verification;
    private CustomerIdentityService identity;
    private JdbcCustomerStore customers;
    private CustomerSessionService service;

    @BeforeEach
    void setUp() {
        clock = new TickingClock(Instant.parse("2026-08-28T09:00:00Z"));
        sessions = new InMemorySessionStore();
        verification = mock(CustomerVerificationService.class);
        identity = mock(CustomerIdentityService.class);
        customers = mock(JdbcCustomerStore.class);

        service = new CustomerSessionService(
                verification, sessions, identity, customers, new DiscardingAuditRecorder(), clock, TTL);

        // Resolution follows the merge redirect; by default an account is its own
        // target, which is what an unmerged account is.
        when(identity.effective(any(), any()))
                .thenAnswer(call -> new CustomerAccountRef(call.getArgument(1), call.getArgument(0)));
    }

    // ------------------------------------------------------------- establishing

    @Test
    @DisplayName("the token that is handed out is not the value that is stored")
    void theStoredValueIsADigest() {
        Established established = establish(null);

        assertThat(established.token()).startsWith(CustomerSessionToken.PREFIX);
        assertThat(sessions.rows).hasSize(1);
        assertThat(sessions.rows.getFirst().tokenHash())
                .isNotEqualTo(established.token())
                .doesNotContain(established.token())
                .isEqualTo(CustomerSessionToken.hash(established.token()));
    }

    @Test
    @DisplayName("the partition comes off the account row, not off the tenant's current policy")
    void thePartitionIsTheAccountsOwn() {
        // The two agree today and disagree across a governed mode change, and the
        // account's own column is where the account actually is. Recomputing would
        // stamp a session with a partitioning its account is not in, which resolves
        // to nothing for the session's whole lifetime.
        establish(BRAND);

        assertThat(sessions.rows.getFirst().identityPartitionBrandId()).isEqualTo(BRAND);
    }

    @Test
    @DisplayName("two sign-ins are two sessions, so one sign-out does not end the other")
    void eachSignInIsItsOwnSession() {
        Established first = establish(null);
        Established second = establish(null);

        assertThat(second.token()).isNotEqualTo(first.token());

        service.endCurrent(first.token());

        assertThat(service.resolve(first.token()).state()).isEqualTo(Resolution.State.ENDED);
        assertThat(service.resolve(second.token()).state()).isEqualTo(Resolution.State.ACTIVE);
    }

    // ---------------------------------------------------------------- resolving

    @Test
    @DisplayName("a live token resolves to its own account, tenant and partition")
    void aLiveTokenResolves() {
        Established established = establish(BRAND);

        CustomerSession session = service.resolve(established.token()).session();

        assertThat(session.accountId()).isEqualTo(ACCOUNT);
        assertThat(session.tenantId()).isEqualTo(TENANT);
        assertThat(session.brandId()).isEqualTo(BRAND);
        assertThat(session.identityPartitionBrandId()).isEqualTo(BRAND);
        assertThat(session.expiresAt()).isEqualTo(clock.instant().plus(TTL));
    }

    @Test
    @DisplayName("a session that runs out of time ends, and says it ended")
    void timePassingEndsASession() {
        Established established = establish(null);
        assertThat(service.resolve(established.token()).state()).isEqualTo(Resolution.State.ACTIVE);

        // The clock moves. An expiry asserted without this is an assertion about
        // an instant, and would hold against code that never expired anything.
        clock.advance(TTL.minusSeconds(1));
        assertThat(service.resolve(established.token()).state())
                .as("one second before the boundary is still a session")
                .isEqualTo(Resolution.State.ACTIVE);

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.resolve(established.token()).state())
                .as("at the boundary it is over; expiry is not a strict inequality that "
                        + "lets a session live one tick past its own expires_at")
                .isEqualTo(Resolution.State.ENDED);
    }

    @Test
    @DisplayName("an ended session is not the same answer as one that never existed")
    void endedIsNotUnknown() {
        Established established = establish(null);
        clock.advance(TTL.plusDays(1));

        assertThat(service.resolve(established.token()).state()).isEqualTo(Resolution.State.ENDED);
        assertThat(service.resolve(CustomerSessionToken.issue().plaintext()).state())
                .as("a customer whose session expired mid-basket must not be answered the " + "way a stranger is")
                .isEqualTo(Resolution.State.UNKNOWN);
    }

    @Test
    @DisplayName("a value that is not shaped like one of ours never reaches the store")
    void aForeignBearerIsNotProbed() {
        // A staff JWT arriving here would otherwise cost a database probe on every
        // request, and — worse — the decision about which principal model a
        // request belongs to would depend on whether a query happened to miss.
        sessions.rows.clear();
        sessions.probes = 0;

        assertThat(service.resolve("eyJhbGciOiJSUzI1NiJ9.stuff.signature").state())
                .isEqualTo(Resolution.State.UNKNOWN);
        assertThat(service.resolve(null).state()).isEqualTo(Resolution.State.UNKNOWN);
        assertThat(service.resolve("").state()).isEqualTo(Resolution.State.UNKNOWN);
        assertThat(service.resolve(CustomerSessionToken.PREFIX).state())
                .as("the prefix on its own is not a token")
                .isEqualTo(Resolution.State.UNKNOWN);

        assertThat(sessions.probes).isZero();
    }

    @Test
    @DisplayName("a merged account resolves to the account that survived the merge")
    void aMergedAccountFollowsItsRedirect() {
        UUID survivor = UUID.randomUUID();
        Established established = establish(null);
        when(identity.effective(TENANT, ACCOUNT)).thenReturn(new CustomerAccountRef(survivor, TENANT));

        assertThat(service.resolve(established.token()).session().accountId())
                .as("two accounts can be joined while somebody is holding a session for "
                        + "one; addressing the merged-away row reads as their history "
                        + "vanishing")
                .isEqualTo(survivor);
    }

    // ------------------------------------------------------------- ending a session

    @Test
    @DisplayName("signing out twice is not an error")
    void signOutIsIdempotent() {
        Established established = establish(null);

        service.endCurrent(established.token());
        Instant firstEnd = sessions.rows.getFirst().revokedAt();

        clock.advance(Duration.ofMinutes(5));
        service.endCurrent(established.token());

        assertThat(sessions.rows.getFirst().revokedAt())
                .as("the first end is the true one; a timeline that moves is worse than none")
                .isEqualTo(firstEnd);
    }

    @Test
    @DisplayName("ending every session of an account ends all of them and nobody else's")
    void aLostHandsetHasAnAnswer() {
        Established mine = establish(null);
        Established alsoMine = establish(null);
        UUID somebodyElse = UUID.randomUUID();
        Established theirs = establish(null, somebodyElse);

        assertThat(service.endAllFor(TENANT, ACCOUNT)).isEqualTo(2);

        assertThat(service.resolve(mine.token()).state()).isEqualTo(Resolution.State.ENDED);
        assertThat(service.resolve(alsoMine.token()).state()).isEqualTo(Resolution.State.ENDED);
        assertThat(service.resolve(theirs.token()).state())
                .as("revocation is per account, and being in the same tenant is not being " + "the same person")
                .isEqualTo(Resolution.State.ACTIVE);
        assertThat(somebodyElse).isNotEqualTo(ACCOUNT);
    }

    // ------------------------------------------------------------ the identity mode

    @Test
    @DisplayName("under TENANT_SHARED a session covers every brand of its tenant")
    void aSharedSessionCoversTheTenant() {
        CustomerSession shared = session(null);

        assertThat(shared.covers(TENANT, BRAND, CustomerIdentityPolicy.TENANT_SHARED))
                .isTrue();
        assertThat(shared.covers(TENANT, SIBLING_BRAND, CustomerIdentityPolicy.TENANT_SHARED))
                .as("one account across the tenant is what the mode means")
                .isTrue();
    }

    @Test
    @DisplayName("under BRAND_ISOLATED a session covers only the brand it was minted at")
    void anIsolatedSessionCoversOneBrand() {
        CustomerSession isolated = session(BRAND);

        assertThat(isolated.covers(TENANT, BRAND, CustomerIdentityPolicy.BRAND_ISOLATED))
                .isTrue();
        assertThat(isolated.covers(TENANT, SIBLING_BRAND, CustomerIdentityPolicy.BRAND_ISOLATED))
                .as("separate businesses hold separate accounts for the same person")
                .isFalse();
    }

    @Test
    @DisplayName("a governed mode change stops a session matching rather than re-partitioning it")
    void aModeChangeEndsExistingSessions() {
        // The expected partition is computed from the policy in force now, and the
        // stored one from the account. Trusting the stored one alone would keep
        // serving the old partitioning for the life of every session — which under
        // a shift to BRAND_ISOLATED is exactly the cross-brand exposure the mode
        // was changed to end.
        CustomerSession mintedShared = session(null);

        assertThat(mintedShared.covers(TENANT, BRAND, CustomerIdentityPolicy.BRAND_ISOLATED))
                .isFalse();

        CustomerSession mintedIsolated = session(BRAND);
        assertThat(mintedIsolated.covers(TENANT, BRAND, CustomerIdentityPolicy.TENANT_SHARED))
                .isFalse();
    }

    @Test
    @DisplayName("a session never covers another tenant, whatever the mode")
    void aSessionIsOneTenant() {
        assertThat(session(null).covers(OTHER_TENANT, BRAND, CustomerIdentityPolicy.TENANT_SHARED))
                .isFalse();
        assertThat(session(BRAND).covers(OTHER_TENANT, BRAND, CustomerIdentityPolicy.BRAND_ISOLATED))
                .isFalse();
    }

    @Test
    @DisplayName("the actor subject is namespaced and carries no personal data")
    void theActorSubjectIsSafe() {
        // It reaches ADR 0031 idempotency rows and audit records. A phone number
        // here would put personal data in both, and a bare Keycloak-shaped subject
        // could collide with a realm one.
        String subject = session(null).actorSubject();

        assertThat(subject).startsWith("customer:").contains(ACCOUNT.toString());
        assertThat(subject).doesNotContain("998");
    }

    // ------------------------------------------------------------------ helpers

    private Established establish(UUID partition) {
        return establish(partition, ACCOUNT);
    }

    private Established establish(UUID partition, UUID accountId) {
        when(verification.redeemAsProvenNumber(eq(TENANT), eq(BRAND), anyString()))
                .thenReturn(new Redemption(new CustomerAccountRef(accountId, TENANT), true));
        when(customers.account(TENANT, accountId))
                .thenReturn(Optional.of(
                        new AccountRow(accountId, partition, "ACTIVE", null, null, null, 1, 1, clock.instant())));

        return service.establish(TENANT, BRAND, "a-grant");
    }

    private CustomerSession session(UUID partition) {
        return new CustomerSession(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                ACCOUNT,
                partition,
                clock.instant(),
                clock.instant().plus(TTL));
    }

    /**
     * The store's rules, and only its rules.
     *
     * <p>Liveness is deliberately not filtered here, exactly as the JDBC
     * implementation does not filter it: the service decides, from the two
     * timestamps, whether a session is live — which is what makes "ended" and
     * "never existed" separable answers.
     */
    private static final class InMemorySessionStore implements CustomerSessionStore {

        private final List<Row> rows = new ArrayList<>();
        private final Map<String, Row> byHash = new HashMap<>();
        private int probes;

        @Override
        public void insert(NewSession session) {
            Row row = new Row(
                    session.sessionId(),
                    session.tenantId(),
                    session.brandId(),
                    session.accountId(),
                    session.identityPartitionBrandId(),
                    session.tokenHash(),
                    session.issuedAt(),
                    session.expiresAt(),
                    null);
            rows.add(row);
            byHash.put(session.tokenHash(), row);
        }

        @Override
        public Optional<StoredSession> find(String tokenHash) {
            probes++;
            return Optional.ofNullable(byHash.get(tokenHash)).map(Row::stored);
        }

        @Override
        public boolean revoke(String tokenHash, Instant now) {
            Row row = byHash.get(tokenHash);
            if (row == null || row.revokedAt != null) {
                return false;
            }
            row.revokedAt = now;
            return true;
        }

        @Override
        public int revokeForAccount(UUID tenantId, UUID accountId, Instant now) {
            int ended = 0;
            for (Row row : rows) {
                if (row.tenantId.equals(tenantId) && row.accountId.equals(accountId) && row.revokedAt == null) {
                    row.revokedAt = now;
                    ended++;
                }
            }
            return ended;
        }

        @Override
        public int purgeEndedBefore(Instant cutoff, int limit) {
            List<Row> doomed = rows.stream()
                    .filter(row ->
                            row.expiresAt.isBefore(cutoff) || (row.revokedAt != null && row.revokedAt.isBefore(cutoff)))
                    .limit(limit)
                    .toList();
            doomed.forEach(row -> byHash.remove(row.tokenHash));
            rows.removeAll(doomed);
            return doomed.size();
        }

        private static final class Row {

            private final UUID id;
            private final UUID tenantId;
            private final UUID brandId;
            private final UUID accountId;
            private final UUID partition;
            private final String tokenHash;
            private final Instant issuedAt;
            private final Instant expiresAt;
            private Instant revokedAt;

            private Row(
                    UUID id,
                    UUID tenantId,
                    UUID brandId,
                    UUID accountId,
                    UUID partition,
                    String tokenHash,
                    Instant issuedAt,
                    Instant expiresAt,
                    Instant revokedAt) {
                this.id = id;
                this.tenantId = tenantId;
                this.brandId = brandId;
                this.accountId = accountId;
                this.partition = partition;
                this.tokenHash = tokenHash;
                this.issuedAt = issuedAt;
                this.expiresAt = expiresAt;
                this.revokedAt = revokedAt;
            }

            private StoredSession stored() {
                return new StoredSession(id, tenantId, brandId, accountId, partition, issuedAt, expiresAt, revokedAt);
            }

            private String tokenHash() {
                return tokenHash;
            }

            private UUID identityPartitionBrandId() {
                return partition;
            }

            private Instant revokedAt() {
                return revokedAt;
            }
        }
    }

    /** Audit is asserted where it is written, not here. */
    private static final class DiscardingAuditRecorder implements AuditRecorder {

        @Override
        public void record(AuditFact fact) {
            // Deliberately nothing.
        }
    }

    private static final class TickingClock extends Clock {

        private Instant now;

        private TickingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
