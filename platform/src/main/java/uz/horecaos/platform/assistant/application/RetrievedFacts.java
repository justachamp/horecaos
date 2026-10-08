package uz.horecaos.platform.assistant.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import uz.horecaos.platform.assistant.api.RetrievedFact;
import uz.horecaos.platform.assistant.domain.FactKind;

/**
 * Everything retrieved for one turn: the facts the model composes from, the
 * provenance of each (ids and amounts, never words) for the ledger and the audit
 * trail, and the knowledge entry versions among them.
 *
 * @param customerSpecific true when any fact is about this one customer (their
 *        order), which makes a composed reply unfit to be served to anyone else
 *        from the cache
 */
record RetrievedFacts(
        List<Entry> entries,
        List<Map<String, Object>> knowledgeVersions,
        Set<UUID> locationIds,
        boolean customerSpecific) {

    /** Nothing retrieved: what a refusal before retrieval, or a retrieval that found nothing, carries. */
    static final RetrievedFacts EMPTY = new RetrievedFacts(List.of(), List.of(), Set.of(), false);

    RetrievedFacts {
        entries = List.copyOf(entries);
        knowledgeVersions = List.copyOf(knowledgeVersions);
        locationIds = Set.copyOf(locationIds);
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    List<RetrievedFact> facts() {
        return entries.stream().map(Entry::fact).toList();
    }

    /** What the ledger records of each fact: a stable shape of ids and amounts. */
    List<Map<String, Object>> provenance() {
        return entries.stream().map(Entry::provenance).toList();
    }

    /** Whether quoting any of these facts states something the platform will be held to. */
    boolean bindsThePlatform(Set<String> citedFactIds) {
        return entries.stream()
                .filter(entry -> citedFactIds.contains(entry.fact().id()))
                .anyMatch(entry -> entry.kind().bindsThePlatform());
    }

    /** One fact, its kind, and the provenance that names where it came from. */
    record Entry(RetrievedFact fact, FactKind kind, Map<String, Object> provenance) {}

    /** Builds a {@link RetrievedFacts}; ids are assigned in order, so a turn's facts are f1, f2, ... */
    static final class Builder {
        private final List<Entry> entries = new ArrayList<>();
        private final List<Map<String, Object>> knowledgeVersions = new ArrayList<>();
        private Set<UUID> locationIds = Set.of();
        private boolean customerSpecific;

        Builder add(FactKind kind, Map<String, String> attributes, Map<String, Object> provenance) {
            String id = "f" + (entries.size() + 1);
            Map<String, Object> described = new java.util.LinkedHashMap<>();
            described.put("id", id);
            described.put("kind", kind.name());
            described.putAll(provenance);
            entries.add(new Entry(new RetrievedFact(id, kind.name(), attributes), kind, described));
            return this;
        }

        Builder knowledgeVersion(UUID entryId, int version) {
            knowledgeVersions.add(Map.of("entryId", entryId.toString(), "version", version));
            return this;
        }

        Builder locations(Set<UUID> ids) {
            this.locationIds = ids;
            return this;
        }

        Builder customerSpecific() {
            this.customerSpecific = true;
            return this;
        }

        int size() {
            return entries.size();
        }

        RetrievedFacts build() {
            return new RetrievedFacts(entries, knowledgeVersions, locationIds, customerSpecific);
        }
    }
}
