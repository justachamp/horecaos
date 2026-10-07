package uz.horecaos.platform.assistant.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.assistant.domain.QuestionClassifier;
import uz.horecaos.platform.assistant.domain.ReplyLocale;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcKnowledgeStore;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcKnowledgeStore.EntryRow;
import uz.horecaos.platform.assistant.infrastructure.persistence.JdbcKnowledgeStore.VersionRow;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.configuration.SearchText;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The tenant's own answers (ADR 0069): authored by the operations team,
 * versioned, scoped to the tenant, a brand or a location, in one language.
 *
 * <p>"Someone was answered with specific words at a specific time", so an entry is
 * never edited: publishing is appending the next version, retiring is appending a
 * version whose status is {@code RETIRED}, and the previous words stay readable
 * forever. Two operators publishing at once race for the same next version number
 * and exactly one wins -- the other is told the entry has moved (ADR 0031).
 *
 * <p>Retrieval reads only the asking tenant's entries, because every statement
 * names the tenant (see {@link JdbcKnowledgeStore}); cross-tenant retrieval would
 * be a tenant-isolation defect and not a relevance bug, and has its own test.
 */
@Service
public class KnowledgeService {

    /** How many entries one retrieval considers; a brand with more than this has more than a chat can use. */
    static final int RETRIEVAL_POOL = 300;

    /** How many matching entries one question may be answered from. */
    static final int MAX_MATCHES = 3;

    /** Fraction of the larger of the two term lists that must overlap before an entry is "about" a question. */
    private static final double MINIMUM_OVERLAP = 0.5;

    static final int LIST_LIMIT = 500;

    private final JdbcKnowledgeStore store;
    private final AuditRecorder audit;
    private final Clock clock;

    KnowledgeService(JdbcKnowledgeStore store, AuditRecorder audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ authoring

    /**
     * Creates an entry and publishes its first version.
     *
     * @param brandId    null for a tenant-scope entry
     * @param locationId null unless the entry is location-scope; requires a brand
     */
    @Transactional
    public EntryView create(
            UUID tenantId,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String locale,
            String questionForm,
            String answerBody,
            String actorSubject,
            String reason) {
        requireLocale(locale);
        requireText(questionForm, answerBody);
        if (brandId == null && locationId != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "A location-scope entry names its brand");
        }
        String scopeType = brandId == null ? "TENANT" : locationId == null ? "BRAND" : "LOCATION";
        UUID entryId = Ids.newId();
        Instant now = clock.instant();
        try {
            store.insertEntry(entryId, tenantId, scopeType, brandId, locationId, locale, actorSubject, now);
        } catch (DataIntegrityViolationException unknownLocation) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such location in this brand");
        }
        store.insertVersion(
                tenantId, entryId, 1, "PUBLISHED", questionForm.strip(), answerBody.strip(), actorSubject, reason, now);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("scope", scopeType);
        after.put("locale", locale);
        after.put("version", 1);
        after.put("status", "PUBLISHED");
        after.put("questionForm", questionForm.strip());
        after.put("answerBody", answerBody.strip());
        recordCreated(tenantId, brandId, entryId, actorSubject, reason, after);

        return view(store.find(tenantId, entryId).orElseThrow());
    }

    /**
     * Appends the next version of an entry: new words, or -- with {@code retire}
     * -- the end of the entry's life.
     *
     * @param scopeBrandId the brand of the path the caller came in by, or null for
     *                     the tenant path; an entry outside it is "not found"
     * @param expectedVersion the version the caller last read (ADR 0031's If-Match)
     */
    @Transactional
    public EntryView publishNextVersion(
            UUID tenantId,
            @Nullable UUID scopeBrandId,
            UUID entryId,
            long expectedVersion,
            boolean retire,
            String questionForm,
            String answerBody,
            String actorSubject,
            String reason) {
        requireText(questionForm, answerBody);
        EntryRow entry = requireInScope(tenantId, scopeBrandId, entryId);
        VersionRow current = entry.current();
        if (current.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, current.version());
        }
        String status = retire ? "RETIRED" : "PUBLISHED";
        int next = current.version() + 1;
        try {
            store.insertVersion(
                    tenantId,
                    entryId,
                    next,
                    status,
                    questionForm.strip(),
                    answerBody.strip(),
                    actorSubject,
                    reason,
                    clock.instant());
        } catch (DuplicateKeyException lostTheRace) {
            throw ApiException.staleVersion(expectedVersion, next);
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("version", current.version());
        before.put("status", current.status());
        before.put("questionForm", current.questionForm());
        before.put("answerBody", current.answerBody());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("version", next);
        after.put("status", status);
        after.put("questionForm", questionForm.strip());
        after.put("answerBody", answerBody.strip());
        recordChanged(
                retire ? "assistant.knowledge.retired" : "assistant.knowledge.published",
                tenantId,
                entry.brandId(),
                entryId,
                actorSubject,
                reason,
                before,
                after);

        return view(store.find(tenantId, entryId).orElseThrow());
    }

    /**
     * Ends an entry's life by appending a {@code RETIRED} version that carries the
     * words it last said, so the history reads as "this was said, then withdrawn"
     * and never as an entry whose last version is blank.
     */
    @Transactional
    public EntryView retire(
            UUID tenantId,
            @Nullable UUID scopeBrandId,
            UUID entryId,
            long expectedVersion,
            String actorSubject,
            String reason) {
        EntryRow entry = requireInScope(tenantId, scopeBrandId, entryId);
        if ("RETIRED".equals(entry.current().status())) {
            throw new ApiException(ErrorCode.RESOURCE_CONFLICT, "This entry is already retired");
        }
        return publishNextVersion(
                tenantId,
                scopeBrandId,
                entryId,
                expectedVersion,
                true,
                entry.current().questionForm(),
                entry.current().answerBody(),
                actorSubject,
                reason);
    }

    @Transactional(readOnly = true)
    public List<EntryView> list(UUID tenantId, @Nullable UUID brandId) {
        List<EntryRow> rows = brandId == null
                ? store.listTenantScope(tenantId, LIST_LIMIT)
                : store.listBrandScope(tenantId, brandId, LIST_LIMIT);
        return rows.stream().map(KnowledgeService::view).toList();
    }

    @Transactional(readOnly = true)
    public EntryView get(UUID tenantId, @Nullable UUID scopeBrandId, UUID entryId) {
        return view(requireInScope(tenantId, scopeBrandId, entryId));
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(UUID tenantId, @Nullable UUID scopeBrandId, UUID entryId) {
        requireInScope(tenantId, scopeBrandId, entryId);
        return store.versions(tenantId, entryId).stream()
                .map(KnowledgeService::versionView)
                .toList();
    }

    // ------------------------------------------------------------------ retrieval

    /**
     * The entries that answer a question: published, in scope for the brand (and
     * the branches the question concerns), in one of the languages asked for, and
     * about what the customer asked.
     *
     * @param locationIds the branches the question is about; a location-scope entry
     *                    is only a candidate for one of these
     * @param locales     languages to read entries in, the reply language first
     */
    @Transactional(readOnly = true)
    public List<KnowledgeMatch> retrieve(
            UUID tenantId, UUID brandId, Collection<UUID> locationIds, List<String> locales, String question) {
        List<String> queryTerms = QuestionClassifier.significantTerms(question);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<EntryRow> candidates = store.retrievable(tenantId, brandId, locationIds, locales, RETRIEVAL_POOL);
        List<Scored> scored = new ArrayList<>();
        for (EntryRow candidate : candidates) {
            List<String> entryTerms =
                    QuestionClassifier.significantTerms(candidate.current().questionForm());
            if (entryTerms.isEmpty()) {
                continue;
            }
            int overlap =
                    SearchText.countMatches(queryTerms, candidate.current().questionForm());
            double ratio = overlap / (double) Math.max(queryTerms.size(), entryTerms.size());
            if (overlap >= 1 && ratio >= MINIMUM_OVERLAP) {
                scored.add(new Scored(candidate, ratio, languageRank(locales, candidate.locale())));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::ratio).reversed().thenComparingInt(Scored::languageRank))
                .limit(MAX_MATCHES)
                .map(scoredEntry -> new KnowledgeMatch(
                        scoredEntry.entry().id(),
                        scoredEntry.entry().current().version(),
                        scoredEntry.entry().scopeType(),
                        scoredEntry.entry().locale(),
                        scoredEntry.entry().current().questionForm(),
                        scoredEntry.entry().current().answerBody()))
                .toList();
    }

    // -------------------------------------------------------------------- helpers

    private EntryRow requireInScope(UUID tenantId, @Nullable UUID scopeBrandId, UUID entryId) {
        EntryRow entry = store.find(tenantId, entryId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such knowledge entry"));
        boolean inScope =
                scopeBrandId == null ? "TENANT".equals(entry.scopeType()) : scopeBrandId.equals(entry.brandId());
        if (!inScope) {
            // The same answer as an entry that never existed: a caller learns nothing
            // about entries outside the path they came in by.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such knowledge entry");
        }
        return entry;
    }

    private void recordCreated(
            UUID tenantId,
            @Nullable UUID brandId,
            UUID entryId,
            String actorSubject,
            String reason,
            Map<String, Object> after) {
        audit.record(auditFact("assistant.knowledge.created", tenantId, brandId, entryId, actorSubject, reason)
                .changed(ChangeDocuments.created(after))
                .correlatedBy(entryId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    private void recordChanged(
            String action,
            UUID tenantId,
            @Nullable UUID brandId,
            UUID entryId,
            String actorSubject,
            String reason,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(auditFact(action, tenantId, brandId, entryId, actorSubject, reason)
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(entryId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    private static AuditFact.Builder auditFact(
            String action, UUID tenantId, @Nullable UUID brandId, UUID entryId, String actorSubject, String reason) {
        return AuditFact.of(action, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(brandId == null ? ResourceScope.tenant(tenantId) : ResourceScope.brand(tenantId, brandId))
                .target("assistant.knowledge_entry", entryId)
                .because(reason)
                .usingCapability(Capability.ASSISTANT_KNOWLEDGE_MANAGE.code());
    }

    private static void requireLocale(String locale) {
        if (!ReplyLocale.SUPPORTED.contains(locale)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "locale must be one of ru, uz, en");
        }
    }

    private static void requireText(String questionForm, String answerBody) {
        if (questionForm.strip().length() < 3 || questionForm.strip().length() > 300) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "questionForm must be 3 to 300 characters");
        }
        if (answerBody.strip().isEmpty() || answerBody.strip().length() > 2000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "answerBody must be 1 to 2000 characters");
        }
        if (QuestionClassifier.significantTerms(questionForm).isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "questionForm names nothing a customer could ask about once greetings and question words "
                            + "are removed");
        }
    }

    private static int languageRank(List<String> locales, String locale) {
        int index = locales.indexOf(locale);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static EntryView view(EntryRow row) {
        VersionRow current = row.current();
        return new EntryView(
                row.id(),
                row.scopeType(),
                row.brandId(),
                row.locationId(),
                row.locale(),
                current.version(),
                current.status(),
                current.questionForm(),
                current.answerBody(),
                current.authoredBy(),
                current.publishedAt());
    }

    private static VersionView versionView(VersionRow row) {
        return new VersionView(
                row.version(),
                row.status(),
                row.questionForm(),
                row.answerBody(),
                row.authoredBy(),
                row.reason(),
                row.publishedAt());
    }

    private record Scored(EntryRow entry, double ratio, int languageRank) {}

    /** An entry with its current version. {@code version} is the aggregate version a caller echoes in If-Match. */
    public record EntryView(
            UUID id,
            String scope,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            String locale,
            int version,
            String status,
            String questionForm,
            String answerBody,
            String authoredBy,
            Instant publishedAt) {}

    public record VersionView(
            int version,
            String status,
            String questionForm,
            String answerBody,
            String authoredBy,
            String reason,
            Instant publishedAt) {}

    /** An entry retrieved for a question, with the version that was current -- what the turn's provenance records. */
    public record KnowledgeMatch(
            UUID entryId, int version, String scope, String locale, String questionForm, String answerBody) {}
}
