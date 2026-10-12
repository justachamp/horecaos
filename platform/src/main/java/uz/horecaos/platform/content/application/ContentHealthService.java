package uz.horecaos.platform.content.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.content.application.ContentGates.Mode;
import uz.horecaos.platform.content.application.ContentGates.Subject;
import uz.horecaos.platform.content.domain.ContentKind;
import uz.horecaos.platform.content.domain.ContentPlacement;
import uz.horecaos.platform.content.domain.ContentVisibility;
import uz.horecaos.platform.content.domain.FieldProblem;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore;
import uz.horecaos.platform.content.infrastructure.persistence.JdbcContentStore.LiveItem;

/**
 * What is wrong with what customers are being shown (ADR 0157, Staff API: {@code GET /health}).
 *
 * <p>Live items with an unresolved target, a promotion that is not live, a link host that is no
 * longer allowed, a language missing, an image that has not rendered. The storefront has already
 * degraded each of these quietly -- a link to {@code NONE}, a card dropped -- and the operator's
 * view of the same problems is this read, so a quiet degradation is never a silent one.
 */
@Service
public class ContentHealthService {

    private final JdbcContentStore store;
    private final ContentItemViewer viewer;
    private final ContentGates gates;
    private final Clock clock;

    public ContentHealthService(JdbcContentStore store, ContentItemViewer viewer, ContentGates gates, Clock clock) {
        this.store = store;
        this.viewer = viewer;
        this.gates = gates;
        this.clock = clock;
    }

    /** One live item with something wrong. */
    public record Unhealthy(
            UUID id,
            ContentKind kind,
            @Nullable ContentPlacement placement,
            String displayTitle,
            ContentVisibility visibility,
            List<FieldProblem> problems) {

        public Unhealthy {
            problems = List.copyOf(problems);
        }
    }

    @Transactional(readOnly = true)
    public List<Unhealthy> health(UUID tenantId, UUID brandId) {
        Instant now = clock.instant();
        List<LiveItem> live = store.publishedItems(tenantId, brandId, List.of());
        List<Subject> subjects = new ArrayList<>();
        Map<UUID, LiveItem> byId = new java.util.HashMap<>();
        for (LiveItem item : live) {
            byId.put(item.item().id(), item);
            viewer.subjectOfRevision(item.item(), item.revision()).ifPresent(subjects::add);
        }
        Map<UUID, List<FieldProblem>> problems = gates.evaluate(tenantId, brandId, subjects, Mode.HEALTH, now);
        List<Unhealthy> unhealthy = new ArrayList<>();
        for (LiveItem item : live) {
            List<FieldProblem> found = problems.getOrDefault(item.item().id(), List.of());
            if (!found.isEmpty()) {
                unhealthy.add(new Unhealthy(
                        item.item().id(),
                        item.item().kind(),
                        item.item().placement(),
                        item.item().displayTitle(),
                        ContentVisibility.of(
                                item.revision().startsAt(), item.revision().endsAt(), now),
                        found));
            }
        }
        return unhealthy;
    }
}
