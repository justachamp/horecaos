package uz.horecaos.platform.reviews;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.customers.api.ConsentRecorder;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.staff.StaffMemberCards;
import uz.horecaos.platform.media.api.TenantImageCheck;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderDirectory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.pricing.api.BenefitGrantDirectory;
import uz.horecaos.platform.reviews.application.FeedbackPromptPolicyService;
import uz.horecaos.platform.reviews.application.FeedbackTagService;
import uz.horecaos.platform.reviews.application.PublicReviewCache;
import uz.horecaos.platform.reviews.application.PublicReviewService;
import uz.horecaos.platform.reviews.application.RecoveryCaseService;
import uz.horecaos.platform.reviews.application.RecoveryPolicyService;
import uz.horecaos.platform.reviews.application.ReviewCommentVault;
import uz.horecaos.platform.reviews.application.ReviewModerationService;
import uz.horecaos.platform.reviews.application.ReviewQueryService;
import uz.horecaos.platform.reviews.application.ReviewSubmissionService;
import uz.horecaos.platform.reviews.application.ReviewsErasureParticipant;
import uz.horecaos.platform.reviews.application.ReviewsMetrics;
import uz.horecaos.platform.reviews.application.ReviewsSettings;
import uz.horecaos.platform.reviews.application.StaffReferences;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcFeedbackTagStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcPublicationStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcRecoveryCaseStore;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewFactSource;
import uz.horecaos.platform.reviews.infrastructure.persistence.JdbcReviewStore;
import uz.horecaos.platform.reviews.spi.CompensationDirectory;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.StaffDirectories;
import uz.horecaos.platform.support.TestProtection;
import uz.horecaos.platform.tenancy.api.BranchDirectory;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.PolicyAuthor;
import uz.horecaos.platform.tenancy.api.PolicyKey;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * The reviews module wired by hand over a real database, with test doubles only where a collaborator
 * belongs to another module (ADR 0159).
 *
 * <p>Service-level tests that need a specific clock, a specific set of feature flags or a recording
 * event publisher build this instead of an application context: the services are the real ones, the
 * stores are the real SQL, and the field protection is the real envelope. What is faked is exactly what
 * the module's boundary makes it ask somebody else for -- staff members, consent decisions, the media
 * check, remedies and grants -- and each fake records what it was asked so a test can assert on the
 * question as well as the answer.
 */
public final class ReviewsWiring {

    public final JdbcClient jdbc;
    public final Clock clock;
    public final RecordingEvents events = new RecordingEvents();
    public final RecordingConsent consent = new RecordingConsent();
    public final InMemoryPolicies policies = new InMemoryPolicies();
    public final FakeStaffCards staffCards = new FakeStaffCards();
    public final StaffDirectories.Fake staffDirectory = StaffDirectories.fake();
    public final FakeImages images = new FakeImages();
    public final FakeRemedies remedies = new FakeRemedies();
    public final FakeGrants grants = new FakeGrants();
    public final FakeBranches branches = new FakeBranches();
    public final Map<String, Object> configuration = new LinkedHashMap<>();

    public final FieldProtection protection = TestProtection.envelope();
    public final JdbcReviewStore reviewStore;
    public final JdbcPublicationStore publicationStore;
    public final JdbcRecoveryCaseStore caseStore;
    public final JdbcFeedbackTagStore tagStore;
    public final OrderDirectory orders;
    public final AuditRecorder audit;
    public final ReviewCommentVault vault;
    public final ReviewsSettings settings;
    public final FeedbackTagService tags;
    public final RecoveryPolicyService recoveryPolicy;
    public final FeedbackPromptPolicyService promptPolicy;
    public final StaffReferences staff;
    public final PublicReviewCache cache;
    public final ReviewsMetrics metrics;
    public final RecoveryCaseService recovery;
    public final ReviewSubmissionService submission;
    public final ReviewQueryService query;
    public final ReviewModerationService moderation;
    public final PublicReviewService publicReviews;
    public final ReviewsErasureParticipant erasure;
    public final JdbcReviewFactSource factSource;

    private ReviewsWiring(JdbcClient jdbc, Clock clock, Map<String, Object> flags) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.configuration.putAll(flags);

        this.reviewStore = new JdbcReviewStore(jdbc);
        this.publicationStore = new JdbcPublicationStore(jdbc);
        this.caseStore = new JdbcRecoveryCaseStore(jdbc);
        this.tagStore = new JdbcFeedbackTagStore(jdbc);
        this.orders = new JdbcOrderDirectory(new JdbcOrderStore(jdbc), jdbc);
        this.audit = AuditTrail.recorder(jdbc);

        this.vault = new ReviewCommentVault(protection);
        // The configuration map is read live, so a test flips a flag after wiring.
        this.settings = new ReviewsSettings(new LiveConfiguration(configuration));
        this.tags = new FeedbackTagService(tagStore, images, BrandLocaleLookup.platformFallback(), audit, clock);
        this.recoveryPolicy = new RecoveryPolicyService(policies, policies, audit, clock);
        this.promptPolicy = new FeedbackPromptPolicyService(policies, policies, branches, settings, audit, clock);
        this.staff = new StaffReferences(staffDirectory, staffCards);
        CacheManager caches = caches();
        this.cache = new PublicReviewCache(caches);
        this.metrics = new ReviewsMetrics(new SimpleMeterRegistry());
        this.recovery = new RecoveryCaseService(
                caseStore,
                reviewStore,
                vault,
                settings,
                recoveryPolicy,
                staff,
                tags,
                orders,
                grants,
                List.of(remedies),
                audit,
                events,
                metrics,
                clock);
        this.submission = new ReviewSubmissionService(
                reviewStore,
                publicationStore,
                orders,
                vault,
                tags,
                settings,
                consent,
                recovery,
                cache,
                events,
                metrics,
                clock);
        this.query = new ReviewQueryService(reviewStore, vault, publicationStore, caseStore, tags);
        this.moderation = new ReviewModerationService(
                reviewStore, publicationStore, vault, tags, consent, cache, audit, events, metrics, clock);
        this.publicReviews =
                new PublicReviewService(settings, reviewStore, publicationStore, vault, tags, branches, cache);
        this.erasure =
                new ReviewsErasureParticipant(reviewStore, publicationStore, caseStore, cache, events, metrics, clock);
        this.factSource = new JdbcReviewFactSource(jdbc);
    }

    /** The module wired over {@code jdbc} with both feature flags off, as a fresh tenant has them. */
    public static ReviewsWiring over(JdbcClient jdbc, Clock clock) {
        return new ReviewsWiring(jdbc, clock, Map.of());
    }

    /** The module wired with both feature flags on. */
    public static ReviewsWiring withFlagsOn(JdbcClient jdbc, Clock clock) {
        return new ReviewsWiring(
                jdbc, clock, Map.of("feature.service_recovery", true, "feature.feedback_settings", true));
    }

    public ReviewsWiring with(String key, Object value) {
        configuration.put(key, value);
        return this;
    }

    private static CacheManager caches() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        for (CacheRegistry registered : CacheRegistry.values()) {
            manager.registerCustomCache(
                    registered.cacheName(),
                    Caffeine.newBuilder()
                            .expireAfterWrite(registered.ttl())
                            .maximumSize(registered.maximumSize())
                            .build());
        }
        return manager;
    }

    // ----------------------------------------------------------------- doubles

    /** A clock a test advances. */
    public static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        public static MutableClock at(Instant start) {
            return new MutableClock(start);
        }

        public void advance(Duration by) {
            now = now.plus(by);
        }

        public void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Every Spring event the services published, in order. */
    public static final class RecordingEvents implements ApplicationEventPublisher {

        private final List<Object> published = new CopyOnWriteArrayList<>();

        @Override
        public void publishEvent(Object event) {
            published.add(event);
        }

        public List<Object> all() {
            return List.copyOf(published);
        }

        public <T> List<T> of(Class<T> type) {
            return published.stream().filter(type::isInstance).map(type::cast).toList();
        }

        public void clear() {
            published.clear();
        }
    }

    /** A consent recorder that remembers what it was told, standing in for ADR 0015's store. */
    public static final class RecordingConsent implements ConsentRecorder {

        /** One decision recorded through the port. */
        public record Decision(
                String kind,
                UUID id,
                UUID tenantId,
                UUID accountId,
                @Nullable UUID brandId,
                String purpose,
                String policyVersion,
                String source,
                @Nullable String evidenceReference) {}

        private final List<Decision> decisions = new CopyOnWriteArrayList<>();

        @Override
        public UUID recordGrant(
                UUID tenantId,
                UUID accountId,
                @Nullable UUID brandId,
                String purpose,
                @Nullable String channel,
                String policyVersion,
                String source,
                @Nullable String evidenceReference) {
            UUID id = UUID.randomUUID();
            decisions.add(new Decision(
                    "GRANTED", id, tenantId, accountId, brandId, purpose, policyVersion, source, evidenceReference));
            return id;
        }

        @Override
        public UUID recordWithdrawal(
                UUID tenantId,
                UUID accountId,
                @Nullable UUID brandId,
                String purpose,
                @Nullable String channel,
                String policyVersion,
                String source,
                @Nullable String evidenceReference) {
            UUID id = UUID.randomUUID();
            decisions.add(new Decision(
                    "WITHDRAWN", id, tenantId, accountId, brandId, purpose, policyVersion, source, evidenceReference));
            return id;
        }

        public List<Decision> decisions() {
            return List.copyOf(decisions);
        }
    }

    /** Policy documents held in memory, keyed by policy code and scope. */
    public static final class InMemoryPolicies implements PolicyResolver, PolicyAuthor {

        private final Map<String, Map<ResourceScope, Object>> documents = new LinkedHashMap<>();
        private final Map<String, Map<ResourceScope, Integer>> versions = new LinkedHashMap<>();

        public <P> void publish(PolicyKey<P> key, ResourceScope scope, P document) {
            documents.computeIfAbsent(key.code(), code -> new LinkedHashMap<>()).put(scope, document);
            versions.computeIfAbsent(key.code(), code -> new LinkedHashMap<>()).merge(scope, 1, Integer::sum);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <P> Optional<ResolvedPolicy<P>> resolve(PolicyKey<P> key, ResourceScope scope) {
            for (ResourceScope level : scope.chain()) {
                Object document = documents.getOrDefault(key.code(), Map.of()).get(level);
                if (document != null) {
                    int version = versions.getOrDefault(key.code(), Map.of()).getOrDefault(level, 1);
                    return Optional.of(new ResolvedPolicy<>(
                            key.code(),
                            UUID.nameUUIDFromBytes((key.code() + level).getBytes()),
                            version,
                            level.type(),
                            "hash",
                            (P) document));
                }
            }
            return Optional.empty();
        }

        @Override
        public <P> Optional<ResolvedPolicy<P>> pinned(PolicyKey<P> key, UUID policyId, int policyVersion) {
            return Optional.empty();
        }

        @Override
        public <P> ResolvedPolicy<P> author(
                PolicyKey<P> key, ResourceScope scope, P document, ActorRef authoredBy, String reason) {
            publish(key, scope, document);
            return resolve(key, scope).orElseThrow();
        }

        @Override
        public <P> ResolvedPolicy<P> author(
                PolicyKey<P> key,
                ResourceScope scope,
                P document,
                int expectedVersion,
                ActorRef authoredBy,
                String reason) {
            return author(key, scope, document, authoredBy, reason);
        }

        @Override
        public int currentVersion(PolicyKey<?> key, ResourceScope scope) {
            return versions.getOrDefault(key.code(), Map.of()).getOrDefault(scope, 0);
        }
    }

    /** Staff members a test registers. */
    public static final class FakeStaffCards implements StaffMemberCards {

        private final Map<UUID, Card> cards = new LinkedHashMap<>();

        public Card register(UUID memberId, String subject, String reference, @Nullable String name, String status) {
            Card card = new Card(memberId, subject, reference, name, null, status);
            cards.put(memberId, card);
            return card;
        }

        @Override
        public Map<UUID, Card> cardsOf(UUID tenantId, Collection<UUID> memberIds) {
            Map<UUID, Card> found = new LinkedHashMap<>();
            memberIds.forEach(id -> {
                if (cards.containsKey(id)) {
                    found.put(id, cards.get(id));
                }
            });
            return found;
        }

        @Override
        public List<Card> pickable(UUID tenantId) {
            return new ArrayList<>(cards.values());
        }
    }

    /** The media check, answering what a test says it should. */
    public static final class FakeImages implements TenantImageCheck {

        private final Map<UUID, Verdict> verdicts = new LinkedHashMap<>();
        public final List<UUID> asked = new ArrayList<>();

        public void answer(UUID assetId, Verdict verdict) {
            verdicts.put(assetId, verdict);
        }

        @Override
        public Verdict check(UUID tenantId, UUID assetId, int ratioWidth, int ratioHeight, long maximumBytes) {
            asked.add(assetId);
            return verdicts.getOrDefault(assetId, Verdict.UNKNOWN_ASSET);
        }
    }

    /** Remedies payments would describe, registered by a test. */
    public static final class FakeRemedies implements CompensationDirectory {

        private final Map<UUID, CompensationSummary> remedies = new LinkedHashMap<>();

        public void register(UUID tenantId, UUID refId, UUID orderId, long amountMinor, String currency) {
            remedies.put(refId, new CompensationSummary(kind(), refId, orderId, amountMinor, currency));
            tenants.put(refId, tenantId);
        }

        private final Map<UUID, UUID> tenants = new LinkedHashMap<>();

        @Override
        public String kind() {
            return "ORDER_REMEDY";
        }

        @Override
        public Optional<CompensationSummary> summary(UUID tenantId, UUID refId) {
            return tenantId.equals(tenants.get(refId)) ? Optional.ofNullable(remedies.get(refId)) : Optional.empty();
        }

        @Override
        public List<CompensationCandidate> candidatesForOrder(UUID tenantId, UUID orderId) {
            return remedies.values().stream()
                    .filter(remedy -> remedy.orderId().equals(orderId))
                    .filter(remedy -> tenantId.equals(tenants.get(remedy.refId())))
                    .map(remedy -> new CompensationCandidate(
                            kind(),
                            remedy.refId(),
                            "ORDER_REFUND",
                            "RECORDED",
                            remedy.amountMinor(),
                            remedy.currency(),
                            java.time.Instant.parse("2026-10-14T10:00:00Z")))
                    .toList();
        }
    }

    /** Grants pricing would describe, registered by a test. */
    public static final class FakeGrants implements BenefitGrantDirectory {

        private final Map<UUID, GrantSummary> grants = new LinkedHashMap<>();
        private final Map<UUID, UUID> tenants = new LinkedHashMap<>();

        public void register(UUID tenantId, GrantSummary grant) {
            grants.put(grant.grantId(), grant);
            tenants.put(grant.grantId(), tenantId);
        }

        @Override
        public Optional<GrantSummary> summary(UUID tenantId, UUID grantId) {
            return tenantId.equals(tenants.get(grantId)) ? Optional.ofNullable(grants.get(grantId)) : Optional.empty();
        }

        @Override
        public List<GrantListing> forCustomer(UUID tenantId, UUID brandId, UUID customerAccountId) {
            return grants.values().stream()
                    .filter(grant -> tenantId.equals(tenants.get(grant.grantId())))
                    .filter(grant -> grant.brandId().equals(brandId)
                            && grant.customerAccountId().equals(customerAccountId))
                    .map(grant -> new GrantListing(
                            grant.grantId(),
                            "FIXED_AMOUNT_OFF_ORDER",
                            20_000L,
                            "UZS",
                            grant.status(),
                            grant.sourceType(),
                            grant.sourceId(),
                            "ABCD",
                            java.time.Instant.parse("2026-10-14T10:00:00Z"),
                            null))
                    .toList();
        }
    }

    /** Branches a test registers (name and time zone). */
    public static final class FakeBranches implements BranchDirectory {

        private final List<Branch> branches = new ArrayList<>();

        public void register(UUID locationId, String name, String timezone) {
            branches.add(new Branch(locationId, name, null, null, null, null, null, null, timezone, Map.of()));
        }

        @Override
        public List<Branch> activeBranches(UUID tenantId, UUID brandId) {
            return List.copyOf(branches);
        }
    }

    /** A configuration resolver that reads its overrides live. */
    private static final class LiveConfiguration implements uz.horecaos.platform.tenancy.api.ConfigurationResolver {

        private final Map<String, Object> overrides;

        LiveConfiguration(Map<String, Object> overrides) {
            this.overrides = overrides;
        }

        @Override
        public <T> uz.horecaos.platform.tenancy.api.Resolved<T> resolve(
                uz.horecaos.platform.tenancy.api.ConfigurationKey<T> key, ResourceScope scope) {
            return new FakeConfigurationResolver(Map.copyOf(overrides)).resolve(key, scope);
        }

        @Override
        public uz.horecaos.platform.tenancy.api.ResolutionTrace explain(
                uz.horecaos.platform.tenancy.api.ConfigurationKey<?> key, ResourceScope scope) {
            return resolve(key, scope).trace();
        }
    }
}
