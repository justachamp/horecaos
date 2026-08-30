package uz.horecaos.platform.migration.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * That the import flag is actually consulted (ADR 0024).
 *
 * <p>This file exists because of a specific defect rather than for completeness.
 * {@code ImportContext.isImporting()} shipped with exactly one occurrence in the
 * main sources — its own declaration — while {@code MigrationRunService.runAsImport}
 * carried a name and a Javadoc promising a suppression that did not happen. The
 * consequence, had an import port shipped in that state, is five years of order
 * confirmations to real phone numbers.
 *
 * <p>So the assertions here are deliberately structural. A behavioural test of one
 * adapter proves that adapter; what was wrong was that <em>no</em> adapter
 * consulted the flag, and only a test that enumerates them can notice that
 * happening again — including for the adapter somebody adds next year.
 */
class MigrationImportSuppressionTests {

    private static final Path MAIN = Path.of("src/main/java/uz/horecaos/platform");

    /**
     * The adapters ADR 0024's checklist names, and what each has to suppress.
     *
     * <p>Written out rather than derived. The list is the requirement — ADR 0024
     * names the outbox listener, notification delivery, payment intents, courier
     * booking, POS export, benefit consumption and inventory movements — and a
     * test that discovered the list from the code would pass whatever the code
     * happened to do.
     */
    private static final Map<String, ExternalEffect> REQUIRED_CONSUMERS = Map.ofEntries(
            Map.entry("integration/outbox/OrderingOutboxEventListener.java", ExternalEffect.OUTBOX_PUBLICATION),
            Map.entry("integration/outbox/TenancyOutboxEventListener.java", ExternalEffect.OUTBOX_PUBLICATION),
            // ADR 0010's availability fact, on the same footing as the two above.
            // A legacy image copied into the object store finalizes through the
            // ordinary lifecycle, so an estate of forty thousand photographs would
            // otherwise announce forty thousand new uploads for pictures that have
            // been on menus for years. Only the announcement is suppressed: the
            // derivative job is still written, because the renditions genuinely
            // are owed.
            Map.entry("integration/outbox/MediaOutboxEventListener.java", ExternalEffect.OUTBOX_PUBLICATION),
            Map.entry("notifications/application/OrderNotificationTrigger.java", ExternalEffect.CUSTOMER_NOTIFICATION),
            // The outbound half, and a different effect from the trigger above for
            // the same reason POS splits its two: not writing an intent is a
            // coherent state, while putting an SMS on the wire is not
            // withdrawable. One constant cannot be both skipped and refused, and
            // passing the skipped one to refuse() throws on every send.
            Map.entry(
                    "integration/camel/notification/NotificationGateway.java",
                    ExternalEffect.NOTIFICATION_PROVIDER_CALL),
            Map.entry("payments/application/PaymentIntentService.java", ExternalEffect.PAYMENT_COLLECTION),
            Map.entry("payments/application/PaymentAttemptService.java", ExternalEffect.PAYMENT_COLLECTION),
            Map.entry("integration/camel/payment/PaymentGateway.java", ExternalEffect.PAYMENT_COLLECTION),
            Map.entry("integration/camel/delivery/DeliveryGateway.java", ExternalEffect.COURIER_BOOKING),
            Map.entry("pos/application/PosOrderExportService.java", ExternalEffect.POS_ORDER_EXPORT),
            Map.entry("integration/camel/pos/PosGateway.java", ExternalEffect.POS_PROVIDER_CALL),
            Map.entry("commercial/application/UsageMeteringService.java", ExternalEffect.BENEFIT_CONSUMPTION),
            Map.entry("inventory/application/InventoryService.java", ExternalEffect.INVENTORY_MOVEMENT));

    @Test
    @DisplayName("every adapter ADR 0024 names consults the flag, with its own effect")
    void everyNamedAdapterConsultsTheFlag() {
        List<String> missing = new ArrayList<>();
        REQUIRED_CONSUMERS.forEach((relativePath, effect) -> {
            String source = read(MAIN.resolve(relativePath));
            if (!source.contains("ImportSuppression.")) {
                missing.add(relativePath + " does not consult ImportSuppression at all");
            } else if (!source.contains("ExternalEffect." + effect.name())) {
                missing.add(relativePath + " does not suppress " + effect);
            }
        });

        assertThat(missing)
                .as("ADR 0024: importing historical orders must not reach a customer, a provider, "
                        + "a till, or a stock level")
                .isEmpty();
    }

    @Test
    @DisplayName("no declared effect is left with only its own declaration")
    void everyEffectHasAConsumer() throws IOException {
        // The generalisation of the bug: a constant added here and wired nowhere
        // reads exactly like a suppression that works.
        String adapters = String.join(
                "\n",
                REQUIRED_CONSUMERS.keySet().stream()
                        .map(path -> read(MAIN.resolve(path)))
                        .toList());

        List<ExternalEffect> unwired = Stream.of(ExternalEffect.values())
                .filter(effect -> !adapters.contains("ExternalEffect." + effect.name()))
                .toList();

        assertThat(unwired)
                .as("an effect nothing reads is the exact state isImporting() shipped in")
                .isEmpty();
    }

    @Test
    @DisplayName("the guards sit outside the migration module, where the effects are")
    void guardsAreInTheAdaptersAndNotOnlyInMigration() throws IOException {
        try (Stream<Path> sources = Files.walk(MAIN)) {
            List<String> callers = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(MAIN.resolve("migration")))
                    .filter(path -> read(path).contains("ImportSuppression."))
                    .map(path -> MAIN.relativize(path).toString())
                    .toList();

            assertThat(callers)
                    .as("the flag is read at the boundary where the platform reaches outside itself")
                    .containsExactlyInAnyOrderElementsOf(REQUIRED_CONSUMERS.keySet());
        }
    }

    @Test
    @DisplayName("a skipped effect answers false outside an import and true inside one")
    void skippedEffectsAreOnlySuppressedDuringAnImport() {
        UUID order = UUID.randomUUID();

        assertThat(ImportSuppression.suppress(ExternalEffect.OUTBOX_PUBLICATION, "Order", order))
                .as("a real customer's order must still publish")
                .isFalse();

        boolean suppressed = ImportContext.runAsImport(
                () -> ImportSuppression.suppress(ExternalEffect.OUTBOX_PUBLICATION, "Order", order));
        assertThat(suppressed).isTrue();

        assertThat(ImportSuppression.suppress(ExternalEffect.OUTBOX_PUBLICATION, "Order", order))
                .as("the binding is balanced, so the next request is unaffected")
                .isFalse();
    }

    @Test
    @DisplayName("a refused effect throws inside an import and does nothing outside one")
    void refusedEffectsFailTheRunRatherThanFabricatingAResult() {
        ImportSuppression.refuse(ExternalEffect.COURIER_BOOKING, "createShipment");

        Throwable refusal = catchThrowable(() -> ImportContext.runAsImport(() -> {
            ImportSuppression.refuse(ExternalEffect.COURIER_BOOKING, "createShipment");
            return null;
        }));

        assertThat(refusal)
                .isInstanceOf(ExternalEffectDuringImportException.class)
                .hasMessageContaining("createShipment")
                .hasMessageContaining("ADR 0024");
        assertThat(((ExternalEffectDuringImportException) refusal).effect()).isEqualTo(ExternalEffect.COURIER_BOOKING);
    }

    @Test
    @DisplayName("the two suppressions cannot be swapped for one another")
    void skippingARefusableEffectIsRejected() {
        // Skipping an effect whose callers need a result is how a fabricated
        // reservation id gets committed; failing a run for an effect that has a
        // truthful no-op stops a legitimate import. Neither is a judgement call at
        // the call site, so neither is available there.
        assertThat(catchThrowable(() ->
                        ImportSuppression.suppress(ExternalEffect.INVENTORY_MOVEMENT, "Order", UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refused");

        assertThat(catchThrowable(() -> ImportSuppression.refuse(ExternalEffect.OUTBOX_PUBLICATION, "append")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skipped");
    }

    @Test
    @DisplayName("suppression is decided before the effect, not after it")
    void guardsPrecedeTheEffectTheyStop() {
        // The ordering that matters in the two listeners: the ADR 0032 catalogue
        // check runs first, so an import cannot smuggle an uncatalogued contract
        // past it, and the outbox append does not happen at all.
        String ordering = read(MAIN.resolve("integration/outbox/OrderingOutboxEventListener.java"));
        assertThat(ordering.indexOf("EventCatalog.require"))
                .as("the flag suppresses external effects, never validation")
                .isLessThan(ordering.indexOf("ImportSuppression."));
        assertThat(ordering.indexOf("ImportSuppression."))
                .as("nothing is appended once the guard has answered")
                .isLessThan(ordering.indexOf("outbox.append"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + path, failure);
        }
    }
}
