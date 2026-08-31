package uz.horecaos.platform.catalog.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.catalog.api.StopListPort;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.VariantAvailabilityRow;

/**
 * The {@code catalog.api} face of the 86 list's read side (ADR 0060 §3),
 * matching {@code OrderDecisionPortAdapter}'s shape: a translation layer
 * only, so {@code integration}'s {@code TelegramUpdateHandler} calls the
 * identical {@link CatalogAuthoringService#variantsAtLocation} the web
 * screen behind catalog.md §4.6 calls, never a module-local reinvention.
 *
 * <p>The locale is fixed rather than caller-supplied: {@link StopListPort}
 * carries no tenant locale (the bot's own default-locale configuration lives
 * in {@code integration}, and threading it through this port for one field
 * would be more plumbing than the v1 stop-list command needs); a tenant that
 * wants its product names in a specific script for the bot is a documented
 * follow-up, not a gap this record hides.
 */
@Component
public class StopListPortAdapter implements StopListPort {

    private static final String DEFAULT_LOCALE = "ru";

    /** One page's worth of a location's 86 list, rendered as one chat message (ADR 0060 §3). */
    private static final int MAX_ITEMS = 50;

    private final CatalogAuthoringService authoring;

    public StopListPortAdapter(CatalogAuthoringService authoring) {
        this.authoring = authoring;
    }

    @Override
    public List<Item> listAtLocation(UUID tenantId, UUID brandId, UUID locationId) {
        List<VariantAvailabilityRow> rows =
                authoring.variantsAtLocation(tenantId, brandId, locationId, DEFAULT_LOCALE, null, MAX_ITEMS);
        return rows.stream()
                .map(row -> new Item(row.variantId(), row.productName(), row.available()))
                .toList();
    }
}
