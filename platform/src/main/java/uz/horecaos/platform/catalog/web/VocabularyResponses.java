package uz.horecaos.platform.catalog.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import uz.horecaos.platform.catalog.application.VocabularyService.EntryView;
import uz.horecaos.platform.web.api.AggregateVersion;

/** The wire shapes the tags and ingredients controllers share (ADR 0174). */
final class VocabularyResponses {

    private VocabularyResponses() {}

    /**
     * One registry entry. {@code iconKey}, {@code showAsBadge} and {@code showAsFilter} are a tag's and read
     * as {@code null}, {@code false} and {@code false} on an ingredient.
     *
     * @param names the wording by locale, as authored; the default language of the tenant is always present
     * @param status {@code ACTIVE} or {@code ARCHIVED}
     */
    public record EntryResponse(
            UUID id,
            String code,
            @Nullable String iconKey,
            boolean showAsBadge,
            boolean showAsFilter,
            int sortOrder,
            String status,
            int version,
            Map<String, String> names) {

        static EntryResponse of(EntryView view) {
            return new EntryResponse(
                    view.row().id(),
                    view.row().code(),
                    view.row().iconKey(),
                    view.row().showAsBadge(),
                    view.row().showAsFilter(),
                    view.row().sortOrder(),
                    view.row().status(),
                    view.row().version(),
                    view.names());
        }
    }

    /** A product's ordered set. */
    public record ProductSetResponse(List<EntryResponse> items) {

        static ProductSetResponse of(List<EntryView> views) {
            return new ProductSetResponse(views.stream().map(EntryResponse::of).toList());
        }
    }

    /** The ids of a product's set, in the order they are shown. */
    public record ProductSetRequest(
            @jakarta.validation.constraints.NotNull List<UUID> ids) {}

    static ResponseEntity<EntryResponse> versioned(EntryView view) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(view.row().version()))
                .body(EntryResponse.of(view));
    }
}
