package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.catalog.api.CatalogDraftChanged;
import uz.horecaos.platform.catalog.api.CatalogDraftChanged.Change;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.configuration.Ids;

/**
 * Says that a brand's draft changed (ADR 0016 "Events", ADR 0138 "Events"): the one place the catalog's
 * authoring services publish {@link CatalogDraftChanged} from.
 *
 * <p>Published inside the writing transaction as an in-process event, which {@code CatalogOutboxEventListener}
 * appends to the outbox {@code BEFORE_COMMIT}, so the fact and the change commit together or not at all.
 *
 * <p>Writes nest: creating a product classifies its variant, adding an option classifies the option. Telling a
 * consumer the same entity changed the same way three times in one commit is noise a re-projection then does
 * three times, so within one transaction an identical fact (the same brand, entity, change and channel) is
 * published once. Outside a transaction, which a test calling a service directly is, every call publishes.
 */
final class CatalogDraftSignal {

    private final ApplicationEventPublisher events;
    private final Clock clock;

    CatalogDraftSignal(ApplicationEventPublisher events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    void changed(UUID tenantId, UUID brandId, EntityType entityType, @Nullable UUID entityId, Change change) {
        changed(tenantId, brandId, entityType.name(), entityId, change, null);
    }

    void changed(
            UUID tenantId,
            UUID brandId,
            String entityType,
            @Nullable UUID entityId,
            Change change,
            @Nullable UUID channelId) {
        if (alreadySaid(tenantId, brandId, entityType, entityId, change, channelId)) {
            return;
        }
        events.publishEvent(new CatalogDraftChanged(
                Ids.newId(), tenantId, brandId, entityType, entityId, change, channelId, clock.instant()));
    }

    /** True when this transaction has already published exactly this fact. */
    @SuppressWarnings("unchecked")
    private boolean alreadySaid(
            UUID tenantId,
            UUID brandId,
            String entityType,
            @Nullable UUID entityId,
            Change change,
            @Nullable UUID channelId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        Set<String> said = (Set<String>) TransactionSynchronizationManager.getResource(this);
        if (said == null) {
            Set<String> fresh = new HashSet<>();
            TransactionSynchronizationManager.bindResource(this, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(CatalogDraftSignal.this);
                }
            });
            said = fresh;
        }
        return !said.add(tenantId + "|" + brandId + "|" + entityType + "|" + entityId + "|" + change + "|" + channelId);
    }
}
