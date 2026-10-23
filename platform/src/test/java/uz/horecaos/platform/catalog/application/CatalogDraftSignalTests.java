package uz.horecaos.platform.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.catalog.api.CatalogDraftChanged;
import uz.horecaos.platform.catalog.api.CatalogDraftChanged.Change;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;

/**
 * What {@code CatalogDraftChanged} coalesces, stated as the code does it (ADR 0016, ADR 0180).
 *
 * <p>ADR 0180's Specification once said the fact goes out "one per transaction however many writes it holds".
 * It does not, and a consumer sized from that sentence would be wrong by a factor of the batch: the signal drops a
 * fact only when it is <em>identical</em> to one this authoring service already published in this transaction (same
 * brand, entity type, entity, change and channel). Distinct entities are distinct facts, so a bulk classification of
 * N nodes is N facts and one batch fact. These tests pin that sentence, so the record and the code cannot drift
 * apart again.
 */
class CatalogDraftSignalTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    private final List<CatalogDraftChanged> published = new ArrayList<>();
    private final CatalogDraftSignal signal = new CatalogDraftSignal(
            event -> published.add((CatalogDraftChanged) event), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @BeforeEach
    void inATransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void outOfIt() {
        TransactionSynchronizationManager.unbindResourceIfPossible(signal);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("the same fact said again in one transaction is published once")
    void anIdenticalFactIsPublishedOnce() {
        UUID product = UUID.randomUUID();

        signal.changed(TENANT, BRAND, EntityType.PRODUCT, product, Change.UPDATED);
        signal.changed(TENANT, BRAND, EntityType.PRODUCT, product, Change.UPDATED);
        signal.changed(TENANT, BRAND, EntityType.PRODUCT, product, Change.UPDATED);

        assertThat(published).hasSize(1);
    }

    @Test
    @DisplayName("distinct entities, or the same entity changed another way, are distinct facts: one transaction can "
            + "carry many")
    void distinctFactsInOneTransactionAreAllPublished() {
        int nodes = 25;
        for (int node = 0; node < nodes; node++) {
            // What a REPLACE-mode bulk classification does for each node it rewrites...
            signal.changed(TENANT, BRAND, EntityType.VARIANT.name(), UUID.randomUUID(), Change.CLASSIFIED, null);
        }
        // ...and then once for the batch (entityId null).
        signal.changed(TENANT, BRAND, EntityType.VARIANT.name(), null, Change.CLASSIFIED, null);
        // Creating a product: the product itself, and the classification of its default variant.
        UUID product = UUID.randomUUID();
        signal.changed(TENANT, BRAND, EntityType.PRODUCT, product, Change.CREATED);
        signal.changed(TENANT, BRAND, EntityType.PRODUCT, product, Change.UPDATED);

        assertThat(published)
                .as("one fact per distinct entity and change, not one per transaction")
                .hasSize(nodes + 1 + 2);
        assertThat(published)
                .filteredOn(fact -> fact.entityId() == null)
                .as("the batch fact names no entity")
                .hasSize(1);
    }

    @Test
    @DisplayName("a channel is part of a fact's identity: the same entity on two channels is two facts")
    void theChannelIsPartOfTheFact() {
        UUID variant = UUID.randomUUID();

        signal.changed(
                TENANT, BRAND, EntityType.VARIANT.name(), variant, Change.CHANNEL_MEDIA_REPLACED, UUID.randomUUID());
        signal.changed(
                TENANT, BRAND, EntityType.VARIANT.name(), variant, Change.CHANNEL_MEDIA_REPLACED, UUID.randomUUID());

        assertThat(published).hasSize(2);
    }

    @Test
    @DisplayName("the next transaction says the fact again: what was said is forgotten when the transaction ends")
    void aNewTransactionSaysItAgain() {
        UUID category = UUID.randomUUID();
        signal.changed(TENANT, BRAND, EntityType.CATEGORY, category, Change.UPDATED);

        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        signal.changed(TENANT, BRAND, EntityType.CATEGORY, category, Change.UPDATED);

        assertThat(published).hasSize(2);
    }

    @Test
    @DisplayName("outside a transaction, which a service called directly is, every call publishes")
    void withoutATransactionEveryCallPublishes() {
        TransactionSynchronizationManager.clearSynchronization();
        UUID category = UUID.randomUUID();

        signal.changed(TENANT, BRAND, EntityType.CATEGORY, category, Change.UPDATED);
        signal.changed(TENANT, BRAND, EntityType.CATEGORY, category, Change.UPDATED);

        assertThat(published).hasSize(2);
    }

    @Test
    @DisplayName("each authoring service has its own memory: the same fact from two services in one transaction "
            + "is published twice")
    void eachAuthoringServiceHasItsOwnMemory() {
        CatalogDraftSignal other = new CatalogDraftSignal(
                event -> published.add((CatalogDraftChanged) event), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        UUID product = UUID.randomUUID();

        try {
            signal.changed(TENANT, BRAND, EntityType.PRODUCT, product, Change.UPDATED);
            other.changed(TENANT, BRAND, EntityType.PRODUCT, product, Change.UPDATED);

            assertThat(published).hasSize(2);
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(other);
        }
    }
}
