package uz.horecaos.platform.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The guard against the next hash that quietly loses a field (ADR 0027).
 *
 * <p>Five call sites hand-listed the fields they thought mattered and five of
 * them were wrong. What replaces the list is not a better list: it is that the
 * hash is taken from the command's own components, so the default for a new field
 * is covered, and leaving one out has to be written down.
 */
class ApprovalParametersTests {

    private record Command(
            UUID tenantId,
            long amountMinor,
            String currency,
            Channel channel,
            String providerReference,
            Instant executedAt,
            Duration validFor,
            String idempotencyKey) {}

    private enum Channel {
        PROVIDER_CONSOLE,
        CASH_DRAWER
    }

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120d01");

    /**
     * The property the whole design rests on: a component nobody named enters the
     * hash. A hand-written list fails in the other direction, which is how one
     * signature came to cover two different refunds.
     */
    @Test
    @DisplayName("every component that is not excluded changes the hash")
    void anUnnamedComponentIsCovered() {
        String base = hash(command());

        assertThat(hash(new Command(
                        UUID.randomUUID(),
                        500_000L,
                        "UZS",
                        Channel.PROVIDER_CONSOLE,
                        "CLICK-1",
                        Instant.parse("2026-08-25T14:00:00Z"),
                        Duration.ofDays(7),
                        "key-1")))
                .isNotEqualTo(base);
        assertThat(hash(new Command(
                        TENANT,
                        900_000L,
                        "UZS",
                        Channel.PROVIDER_CONSOLE,
                        "CLICK-1",
                        Instant.parse("2026-08-25T14:00:00Z"),
                        Duration.ofDays(7),
                        "key-1")))
                .isNotEqualTo(base);
        assertThat(hash(new Command(
                        TENANT,
                        500_000L,
                        "USD",
                        Channel.PROVIDER_CONSOLE,
                        "CLICK-1",
                        Instant.parse("2026-08-25T14:00:00Z"),
                        Duration.ofDays(7),
                        "key-1")))
                .isNotEqualTo(base);
        assertThat(hash(new Command(
                        TENANT,
                        500_000L,
                        "UZS",
                        Channel.CASH_DRAWER,
                        "CLICK-1",
                        Instant.parse("2026-08-25T14:00:00Z"),
                        Duration.ofDays(7),
                        "key-1")))
                .isNotEqualTo(base);
        assertThat(hash(new Command(
                        TENANT,
                        500_000L,
                        "UZS",
                        Channel.PROVIDER_CONSOLE,
                        "CLICK-2",
                        Instant.parse("2026-08-25T14:00:00Z"),
                        Duration.ofDays(7),
                        "key-1")))
                .isNotEqualTo(base);
        assertThat(hash(new Command(
                        TENANT,
                        500_000L,
                        "UZS",
                        Channel.PROVIDER_CONSOLE,
                        "CLICK-1",
                        Instant.parse("2026-08-24T14:00:00Z"),
                        Duration.ofDays(7),
                        "key-1")))
                .isNotEqualTo(base);
        assertThat(hash(new Command(
                        TENANT,
                        500_000L,
                        "UZS",
                        Channel.PROVIDER_CONSOLE,
                        "CLICK-1",
                        Instant.parse("2026-08-25T14:00:00Z"),
                        Duration.ofDays(365),
                        "key-1")))
                .isNotEqualTo(base);
    }

    @Test
    void anExcludedComponentDoesNotChangeTheHash() {
        assertThat(hash(new Command(
                        TENANT,
                        500_000L,
                        "UZS",
                        Channel.PROVIDER_CONSOLE,
                        "CLICK-1",
                        Instant.parse("2026-08-25T14:00:00Z"),
                        Duration.ofDays(7),
                        "key-2")))
                .isEqualTo(hash(command()));
    }

    @Test
    void theSameCommandAlwaysHashesTheSame() {
        assertThat(hash(command())).isEqualTo(hash(command()));
        assertThat(hash(command())).matches("^[0-9a-f]{64}$");
    }

    /**
     * The same defect one level down. A join on a separator lets
     * {@code ("a", "b|c")} and {@code ("a|b", "c")} produce identical material, so
     * every segment carries its own length.
     */
    @Test
    void aValueContainingTheSeparatorCannotBeSplitDifferently() {
        assertThat(ApprovalParameters.none()
                        .and("left", "a")
                        .and("right", "b|c")
                        .hash())
                .isNotEqualTo(ApprovalParameters.none()
                        .and("left", "a|b")
                        .and("right", "c")
                        .hash());
    }

    /** An absent reference and a blank one are different claims about the cabinet. */
    @Test
    void aNullIsNotAnEmptyString() {
        assertThat(ApprovalParameters.none().and("providerReference", null).hash())
                .isNotEqualTo(
                        ApprovalParameters.none().and("providerReference", "").hash());
    }

    /**
     * Forgetting the exclusions and having none must not look alike. An empty
     * call is the author saying every component is covered; silence is the author
     * saying nothing, and silence is what the old call sites said.
     */
    @Test
    void hashingWithoutDeclaringTheExclusionsIsRefused() {
        assertThatThrownBy(() -> ApprovalParameters.of(command()).hash())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("excluding");

        assertThat(ApprovalParameters.of(command()).excluding().hash())
                .as("naming no exclusions is a statement, and it is allowed")
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void anExclusionThatNamesNoComponentIsRefused() {
        assertThatThrownBy(() -> ApprovalParameters.of(command()).excluding("idempotency_key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    /**
     * The second half of the drift guard. A component whose type has no canonical
     * form is rejected at the first call rather than hashed through
     * {@code Object.toString}, which would differ on every submission and leave an
     * approval that can never be matched — a control that fails closed and looks
     * like a control that works.
     */
    @Test
    void aComponentWithNoCanonicalFormIsRefusedRatherThanHashedByIdentity() {
        record WithLines(UUID tenantId, List<String> lines) {}

        assertThatThrownBy(() -> ApprovalParameters.of(new WithLines(TENANT, List.of("a")))
                        .excluding()
                        .hash())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lines");
    }

    @Test
    void floatingPointIsNotACanonicalFormForMoney() {
        record WithDouble(UUID tenantId, double amount) {}

        assertThatThrownBy(() -> ApprovalParameters.of(new WithDouble(TENANT, 1.5))
                        .excluding()
                        .hash())
                .as("money here is integer minor units; a hash over a double depends on the parse")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coveredComponentsReportsWhatTheHashWouldBindTo() {
        assertThat(ApprovalParameters.coveredComponents(Command.class, "idempotencyKey"))
                .containsExactly(
                        "tenantId",
                        "amountMinor",
                        "currency",
                        "channel",
                        "providerReference",
                        "executedAt",
                        "validFor");
    }

    @Test
    void anAddedSegmentBindsAsWell() {
        assertThat(ApprovalParameters.of(command())
                        .excluding("idempotencyKey")
                        .and("remedyType", "ORDER_REFUND")
                        .hash())
                .isNotEqualTo(ApprovalParameters.of(command())
                        .excluding("idempotencyKey")
                        .and("remedyType", "DELIVERY_FEE_REIMBURSEMENT")
                        .hash());
    }

    @Test
    void aSegmentSuppliedTwiceIsAMistakeRatherThanTheLastOneWinning() {
        assertThatThrownBy(() -> ApprovalParameters.none().and("type", "A").and("type", "B"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Command command() {
        return new Command(
                TENANT,
                500_000L,
                "UZS",
                Channel.PROVIDER_CONSOLE,
                "CLICK-1",
                Instant.parse("2026-08-25T14:00:00Z"),
                Duration.ofDays(7),
                "key-1");
    }

    private static String hash(Command command) {
        return ApprovalParameters.of(command).excluding("idempotencyKey").hash();
    }
}
