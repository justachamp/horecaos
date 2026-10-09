package uz.horecaos.platform.content.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Collects {@link DocumentFacts} while a document is read; one per validation. */
final class DocumentContext {

    final DocumentProblems problems = new DocumentProblems();
    private final List<DocumentFacts.AssetUse> assets = new ArrayList<>();
    private final Set<UUID> productIds = new LinkedHashSet<>();
    private final Set<UUID> categoryIds = new LinkedHashSet<>();
    private final Set<UUID> promotionIds = new LinkedHashSet<>();
    private final List<DocumentFacts.ExternalRef> refs = new ArrayList<>();
    private final Set<String> locales = new LinkedHashSet<>();

    void asset(
            UUID id,
            MediaRole role,
            int position,
            String field,
            @Nullable UUID poster,
            @Nullable String declaredKind,
            double ratio,
            int minWidthPx) {
        assets.add(new DocumentFacts.AssetUse(id, role, position, field, poster, declaredKind, ratio, minWidthPx));
        if (poster != null) {
            assets.add(new DocumentFacts.AssetUse(
                    poster, MediaRole.POSTER, position, field + ".poster", null, "IMAGE", 0, 0));
        }
    }

    void product(UUID id) {
        productIds.add(id);
    }

    void category(UUID id) {
        categoryIds.add(id);
    }

    void promotion(UUID id) {
        promotionIds.add(id);
    }

    void ref(String field, ExternalUrls.Parsed parsed) {
        refs.add(new DocumentFacts.ExternalRef(field, parsed.host(), parsed.url()));
    }

    void locale(String tag) {
        locales.add(tag);
    }

    DocumentFacts facts() {
        return new DocumentFacts(assets, productIds, categoryIds, promotionIds, refs, locales);
    }
}
