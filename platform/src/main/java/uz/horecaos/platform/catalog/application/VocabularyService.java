package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.Vocabularies;
import uz.horecaos.platform.catalog.domain.Vocabularies.Kind;
import uz.horecaos.platform.catalog.domain.Vocabularies.Refusals;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcVocabularyStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcVocabularyStore.AssignmentRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcVocabularyStore.EntryRow;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.BrandLocaleLookup;
import uz.horecaos.platform.tenancy.api.LocalizedLabels;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The tags and ingredients registries and their product assignments (ADR 0174, gap-map row 4.7): controlled
 * and bounded vocabularies, never free text.
 *
 * <p>A code is operator-chosen, unique per tenant and stable; a rename changes the name, not the code. An
 * archived entry keeps resolving on the products that carry it, disappears from pickers and from the next
 * publication, and frees nothing (its code stays taken). The counts are bounded so a runaway import or a script
 * cannot turn a vocabulary into a dump: 200 tags and 1,000 ingredients per tenant, twelve tags and forty
 * ingredients per product, each a stable refusal code.
 *
 * <p>An ingredient is a composition label and nothing else: not a modifier, not a bill of materials, no
 * allergen meaning. Nothing here prices, stocks or counts one.
 */
@Service
public class VocabularyService {

    private final JdbcVocabularyStore store;
    private final JdbcCatalogStore catalog;
    private final AuditRecorder audit;
    private final Clock clock;
    private final BrandLocaleLookup brandLocales;

    public VocabularyService(
            JdbcVocabularyStore store,
            JdbcCatalogStore catalog,
            AuditRecorder audit,
            Clock clock,
            BrandLocaleLookup brandLocales) {
        this.store = store;
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
        this.brandLocales = brandLocales;
    }

    /** An entry with its wording merged for the console. */
    public record EntryView(EntryRow row, Map<String, String> names) {}

    /**
     * What an author states when creating an entry. {@code iconKey}, {@code showAsBadge} and {@code
     * showAsFilter} are a tag's; an ingredient ignores them.
     */
    public record NewEntry(
            String code,
            @Nullable String iconKey,
            @Nullable Boolean showAsBadge,
            @Nullable Boolean showAsFilter,
            @Nullable Integer sortOrder,
            Map<String, String> names) {}

    public record EntryEdit(
            int expectedVersion,
            @Nullable String iconKey,
            @Nullable Boolean showAsBadge,
            @Nullable Boolean showAsFilter,
            @Nullable Integer sortOrder,
            @Nullable Map<String, String> names) {}

    // ------------------------------------------------------------------------------- registry

    @Transactional(readOnly = true)
    public List<EntryView> list(Kind kind, UUID tenantId) {
        Map<UUID, Map<String, String>> names = store.namesForTenant(kind, tenantId);
        return store.list(kind, tenantId).stream()
                .map(row -> new EntryView(row, names.getOrDefault(row.id(), Map.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public EntryView get(Kind kind, UUID tenantId, UUID id) {
        EntryRow row = require(kind, tenantId, id);
        return new EntryView(row, store.namesOf(kind, tenantId, id));
    }

    @Transactional
    public EntryView create(Kind kind, UUID tenantId, NewEntry command, String actorSubject) {
        if (!Vocabularies.CODE.matcher(command.code()).matches()) {
            throw refused(
                    ErrorCode.VALIDATION_FAILED,
                    Refusals.CODE_INVALID,
                    "A code is lower case letters, digits, '_' and '-', 1 to 32 characters, starting with a letter or digit");
        }
        boolean badge = kind.hasDisplayOptions() && Boolean.TRUE.equals(command.showAsBadge());
        boolean filter = kind.hasDisplayOptions() && Boolean.TRUE.equals(command.showAsFilter());
        String icon = kind.hasDisplayOptions() ? checkedIcon(command.iconKey()) : null;
        checkVisible(kind, badge, filter);
        Map<String, String> names = suppliedNames(command.names());
        requireDefaultName(tenantId, names);
        if (store.count(kind, tenantId) >= kind.maximumPerTenant()) {
            throw refused(
                    ErrorCode.UNPROCESSABLE_STATE,
                    Refusals.TENANT_LIMIT,
                    "A tenant holds at most %d %s".formatted(kind.maximumPerTenant(), plural(kind)));
        }
        Instant now = clock.instant();
        EntryRow row = new EntryRow(
                Ids.newId(),
                tenantId,
                command.code(),
                icon,
                badge,
                filter,
                command.sortOrder() == null ? 0 : command.sortOrder(),
                "ACTIVE",
                1,
                now,
                now);
        try {
            store.insert(kind, row);
        } catch (DuplicateKeyException taken) {
            throw refused(
                    ErrorCode.RESOURCE_CONFLICT,
                    Refusals.CODE_TAKEN,
                    "This tenant already has a %s with code '%s'".formatted(kind.auditName(), command.code()));
        }
        store.upsertNames(kind, tenantId, row.id(), names, now);

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("code", row.code());
        created.put("sortOrder", row.sortOrder());
        if (kind.hasDisplayOptions()) {
            created.put("iconKey", row.iconKey());
            created.put("showAsBadge", row.showAsBadge());
            created.put("showAsFilter", row.showAsFilter());
        }
        created.put("locales", names.keySet().stream().sorted().collect(Collectors.joining(",")));
        record(kind, "created", tenantId, row.id(), Map.of(), created, now, actorSubject);
        return new EntryView(row, names);
    }

    @Transactional
    public EntryView update(Kind kind, UUID tenantId, UUID id, EntryEdit command, String actorSubject) {
        EntryRow existing = require(kind, tenantId, id);
        if (existing.version() != command.expectedVersion()) {
            throw ApiException.staleVersion(command.expectedVersion(), existing.version());
        }
        boolean badge = kind.hasDisplayOptions()
                ? (command.showAsBadge() == null ? existing.showAsBadge() : command.showAsBadge())
                : false;
        boolean filter = kind.hasDisplayOptions()
                ? (command.showAsFilter() == null ? existing.showAsFilter() : command.showAsFilter())
                : false;
        String icon = kind.hasDisplayOptions()
                ? (command.iconKey() == null ? existing.iconKey() : checkedIcon(command.iconKey()))
                : null;
        checkVisible(kind, badge, filter);
        Map<String, String> names = command.names() == null ? Map.of() : suppliedNames(command.names());
        Map<String, String> before = store.namesOf(kind, tenantId, id);
        if (!names.isEmpty()) {
            // An edit never deletes a locale; it can only change the ones it names, and the default stays.
            Map<String, String> after = new LinkedHashMap<>(before);
            after.putAll(names);
            requireDefaultName(tenantId, after);
        }
        int sortOrder = command.sortOrder() == null ? existing.sortOrder() : command.sortOrder();
        Instant now = clock.instant();
        int version = store.update(
                        kind,
                        tenantId,
                        id,
                        icon,
                        badge,
                        filter,
                        sortOrder,
                        existing.status(),
                        command.expectedVersion(),
                        now)
                .orElseThrow(() -> ApiException.staleVersion(command.expectedVersion(), existing.version() + 1));
        store.upsertNames(kind, tenantId, id, names, now);

        Map<String, Object> was = new LinkedHashMap<>();
        Map<String, Object> is = new LinkedHashMap<>();
        was.put("sortOrder", existing.sortOrder());
        is.put("sortOrder", sortOrder);
        if (kind.hasDisplayOptions()) {
            was.put("iconKey", existing.iconKey());
            is.put("iconKey", icon);
            was.put("showAsBadge", existing.showAsBadge());
            is.put("showAsBadge", badge);
            was.put("showAsFilter", existing.showAsFilter());
            is.put("showAsFilter", filter);
        }
        // Wording is catalogue content and is not put in the audit document: the locales that changed are.
        was.put("renamedLocales", "");
        is.put(
                "renamedLocales",
                names.entrySet().stream()
                        .filter(name -> !name.getValue().equals(before.get(name.getKey())))
                        .map(Map.Entry::getKey)
                        .sorted()
                        .collect(Collectors.joining(",")));
        record(kind, "updated", tenantId, id, was, is, now, actorSubject);

        Map<String, String> merged = new LinkedHashMap<>(before);
        merged.putAll(names);
        return new EntryView(
                new EntryRow(
                        existing.id(),
                        existing.tenantId(),
                        existing.code(),
                        icon,
                        badge,
                        filter,
                        sortOrder,
                        existing.status(),
                        version,
                        existing.createdAt(),
                        now),
                merged);
    }

    /** Archived, never deleted: the entry keeps resolving on the products that carry it. */
    @Transactional
    public EntryView archive(Kind kind, UUID tenantId, UUID id, int expectedVersion, String actorSubject) {
        EntryRow existing = require(kind, tenantId, id);
        if (existing.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, existing.version());
        }
        Instant now = clock.instant();
        int version = store.update(
                        kind,
                        tenantId,
                        id,
                        existing.iconKey(),
                        existing.showAsBadge(),
                        existing.showAsFilter(),
                        existing.sortOrder(),
                        "ARCHIVED",
                        expectedVersion,
                        now)
                .orElseThrow(() -> ApiException.staleVersion(expectedVersion, existing.version() + 1));
        record(
                kind,
                "archived",
                tenantId,
                id,
                Map.of("status", existing.status()),
                Map.of("status", "ARCHIVED"),
                now,
                actorSubject);
        return new EntryView(
                new EntryRow(
                        existing.id(),
                        existing.tenantId(),
                        existing.code(),
                        existing.iconKey(),
                        existing.showAsBadge(),
                        existing.showAsFilter(),
                        existing.sortOrder(),
                        "ARCHIVED",
                        version,
                        existing.createdAt(),
                        now),
                store.namesOf(kind, tenantId, id));
    }

    // ------------------------------------------------------------------------------ assignments

    /** The product's ordered set, with archived entries marked as such (they still resolve there). */
    @Transactional(readOnly = true)
    public List<EntryView> forProduct(Kind kind, UUID tenantId, UUID brandId, UUID productId) {
        requireProduct(tenantId, brandId, productId);
        List<AssignmentRow> assigned = store.assignments(kind, tenantId, brandId, productId);
        Map<UUID, EntryRow> rows =
                store
                        .findAll(
                                kind,
                                tenantId,
                                assigned.stream().map(AssignmentRow::entryId).toList())
                        .stream()
                        .collect(Collectors.toMap(EntryRow::id, row -> row));
        Map<UUID, Map<String, String>> names = store.namesForTenant(kind, tenantId);
        List<EntryView> ordered = new ArrayList<>();
        for (AssignmentRow assignment : assigned) {
            EntryRow row = rows.get(assignment.entryId());
            if (row != null) {
                ordered.add(new EntryView(row, names.getOrDefault(row.id(), Map.of())));
            }
        }
        return ordered;
    }

    /**
     * Replaces the product's set with these entries in this order.
     *
     * <p>A tag the tenant has archived cannot be newly assigned, but one the product already carries may stay in
     * the set while the author changes the rest; it is not silently dropped from the product's history.
     */
    @Transactional
    public List<EntryView> setForProduct(
            Kind kind, UUID tenantId, UUID brandId, UUID productId, List<UUID> orderedEntryIds, String actorSubject) {
        requireProduct(tenantId, brandId, productId);
        Set<UUID> distinct = new LinkedHashSet<>(orderedEntryIds);
        if (distinct.size() != orderedEntryIds.size()) {
            throw refused(
                    ErrorCode.VALIDATION_FAILED,
                    Refusals.ENTRY_DUPLICATED,
                    "A %s appears more than once in the set".formatted(kind.auditName()));
        }
        if (orderedEntryIds.size() > kind.maximumPerProduct()) {
            throw refused(
                    ErrorCode.UNPROCESSABLE_STATE,
                    Refusals.PRODUCT_LIMIT,
                    "A product carries at most %d %s".formatted(kind.maximumPerProduct(), plural(kind)));
        }
        List<AssignmentRow> current = store.assignments(kind, tenantId, brandId, productId);
        Set<UUID> alreadyAssigned = current.stream().map(AssignmentRow::entryId).collect(Collectors.toSet());
        Map<UUID, EntryRow> rows = store.findAll(kind, tenantId, orderedEntryIds).stream()
                .collect(Collectors.toMap(EntryRow::id, row -> row));
        for (UUID entryId : orderedEntryIds) {
            EntryRow row = rows.get(entryId);
            if (row == null) {
                // Another tenant's entry is indistinguishable from one that does not exist.
                throw refused(
                        ErrorCode.UNPROCESSABLE_STATE,
                        Refusals.ENTRY_UNKNOWN,
                        "No such %s for this tenant".formatted(kind.auditName()));
            }
            if ("ARCHIVED".equals(row.status()) && !alreadyAssigned.contains(entryId)) {
                throw refused(
                        ErrorCode.UNPROCESSABLE_STATE,
                        Refusals.ENTRY_ARCHIVED,
                        "An archived %s cannot be newly assigned".formatted(kind.auditName()));
            }
        }
        Instant now = clock.instant();
        store.replaceAssignments(kind, tenantId, brandId, productId, orderedEntryIds, now);

        Map<String, Object> was = new LinkedHashMap<>();
        was.put("count", current.size());
        was.put("ids", current.stream().map(a -> a.entryId().toString()).collect(Collectors.joining(",")));
        Map<String, Object> is = new LinkedHashMap<>();
        is.put("count", orderedEntryIds.size());
        is.put("ids", orderedEntryIds.stream().map(UUID::toString).collect(Collectors.joining(",")));
        audit.record(AuditFact.of("catalog.product." + kind.auditName() + "s-set", AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.brand(tenantId, brandId))
                .target("Product", productId)
                .because("Set a product's " + plural(kind))
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(ChangeDocuments.diff(was, is))
                .correlatedBy(productId.toString())
                .occurredAt(now)
                .build());
        Map<UUID, Map<String, String>> names = store.namesForTenant(kind, tenantId);
        List<EntryView> saved = new ArrayList<>();
        for (UUID id : orderedEntryIds) {
            EntryRow row = rows.get(id);
            if (row != null) {
                saved.add(new EntryView(row, names.getOrDefault(id, Map.of())));
            }
        }
        return saved;
    }

    // ----------------------------------------------------------------------------- internals

    private EntryRow require(Kind kind, UUID tenantId, UUID id) {
        return store.find(kind, tenantId, id)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No such %s for this tenant".formatted(kind.auditName())));
    }

    private void requireProduct(UUID tenantId, UUID brandId, UUID productId) {
        if (!catalog.entityExistsInBrand(tenantId, brandId, EntityType.PRODUCT, productId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such product in this brand");
        }
    }

    private static @Nullable String checkedIcon(@Nullable String iconKey) {
        if (iconKey == null || iconKey.isBlank()) {
            return null;
        }
        if (!Vocabularies.TAG_ICONS.contains(iconKey)) {
            throw refused(
                    ErrorCode.VALIDATION_FAILED,
                    Refusals.ICON_UNKNOWN,
                    "The badge icon is one of " + String.join(", ", Vocabularies.TAG_ICONS));
        }
        return iconKey;
    }

    private static void checkVisible(Kind kind, boolean badge, boolean filter) {
        if (kind.hasDisplayOptions() && !badge && !filter) {
            throw refused(
                    ErrorCode.VALIDATION_FAILED,
                    Refusals.NOT_VISIBLE,
                    "A tag is shown as a badge, offered as a filter, or both");
        }
    }

    private static Map<String, String> suppliedNames(@Nullable Map<String, String> names) {
        try {
            return LocalizedLabels.supplied(null, null, null, names, Vocabularies.MAX_NAME_LENGTH);
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    private void requireDefaultName(UUID tenantId, Map<String, String> names) {
        String defaultLocale = brandLocales.tenantLocaleSet(tenantId).defaultLocale();
        if (!names.containsKey(defaultLocale)) {
            throw refused(
                    ErrorCode.VALIDATION_FAILED,
                    Refusals.NAME_MISSING,
                    "A name is needed in the tenant's default language (%s)".formatted(defaultLocale));
        }
    }

    private void record(
            Kind kind,
            String verb,
            UUID tenantId,
            UUID id,
            Map<String, Object> before,
            Map<String, Object> after,
            Instant at,
            String actorSubject) {
        Map<String, Object> document = ChangeDocuments.diff(before, after);
        audit.record(AuditFact.of("catalog." + kind.auditName() + "." + verb, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.tenant(tenantId))
                .target(kind == Kind.TAG ? "Tag" : "Ingredient", id)
                .because(verb.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + verb.substring(1) + " a "
                        + kind.auditName())
                .usingCapability(Capability.CATALOG_AUTHOR.code())
                .changed(document)
                .correlatedBy(id.toString())
                .occurredAt(at)
                .build());
    }

    private static String plural(Kind kind) {
        return kind == Kind.TAG ? "tags" : "ingredients";
    }

    private static ApiException refused(ErrorCode code, String findingCode, String detail) {
        return new ApiException(code, detail, Map.of("findingCode", findingCode));
    }
}
