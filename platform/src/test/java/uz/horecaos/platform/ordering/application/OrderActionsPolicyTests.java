package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.ordering.domain.OrderStateMachine;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;

/**
 * {@code actions[]} against every status, fulfilment mode and grant (orders.md
 * §4.2).
 *
 * <p>Every assertion here is written against the exact call the mutating
 * endpoint makes, not against a hand-written expected table, which is what
 * makes this a drift test rather than a description of {@link
 * OrderActionsPolicy}'s own code read back at itself:
 *
 * <ul>
 *   <li>{@code decide()} accepts APPROVE/REJECT exactly when {@code status ==
 *       AWAITING_APPROVAL} and the caller holds {@code ORDER_APPROVE} — the
 *       same predicate this test applies.
 *   <li>{@code advance()} accepts a target exactly when {@link
 *       OrderStateMachine#permits(OrderStatus, OrderStatus, FulfillmentMode)}
 *       says so and the caller holds {@code ORDER_ADVANCE} — the very method
 *       this test calls to build its expectation.
 *   <li>{@code cancel()}'s reasonless path accepts exactly when {@link
 *       OrderActionsPolicy#canCancelWithoutReason} says so <em>and</em> {@link
 *       OrderStateMachine#permits(OrderStatus, OrderStatus)} allows the
 *       CANCELLED edge <em>and</em> the caller holds {@code ORDER_CANCEL} —
 *       {@code OrderStateService.cancel} was refactored to call {@code
 *       canCancelWithoutReason} directly (rather than repeating its own copy of
 *       the CONFIRMED/PREPARING/READY/FULFILLING list), so this test and the
 *       production guard are reading the same method, not two that happen to
 *       agree today.
 * </ul>
 *
 * <p>A hand-maintained "expected actions per status" table would drift the
 * moment somebody added a transition to {@code OrderStateMachine} without
 * remembering to update it — which is exactly the failure mode orders.md §4.2
 * exists to rule out on the client. Driving both sides of every assertion from
 * {@code OrderStateMachine} and {@code OrderActionsPolicy}'s own extracted
 * guard is what keeps that failure mode out of this test too.
 *
 * <p><b>Wave P05 (gap map 1.2a).</b> Before this wave {@code availableFor} took
 * no principal, so {@code LOCATION_STAFF} — which holds {@code
 * ORDER_APPROVE}/{@code ORDER_ADVANCE} and not {@code ORDER_CANCEL} — was
 * offered «Отменить» on every open order and refused with a 403. The tests
 * below in the "principal-aware" section assert the fix directly, reading
 * each role's real grant set from {@link PlatformRole} rather than a
 * hand-typed capability list, for the same drift-proofing reason as above.
 */
class OrderActionsPolicyTests {

    /** The only capabilities {@link OrderActionsPolicy#availableFor} reads. */
    private static final Set<Capability> ALL_ACTION_CAPS = EnumSet.of(
            Capability.ORDER_APPROVE, Capability.ORDER_ADVANCE, Capability.ORDER_CANCEL, Capability.ORDER_AMEND);

    @Test
    void theScanCoversEveryStatusAndMode() {
        // A test that silently iterated zero combinations would pass forever.
        int combinations = OrderStatus.values().length * FulfillmentMode.values().length;
        assertThat(combinations).isPositive();
    }

    @Test
    void approveAndRejectAppearExactlyOnAwaitingApprovalWhenApproveIsGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderAction> actions = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS);
                boolean hasApprove = actions.stream().anyMatch(a -> a.code() == OrderActionCode.APPROVE);
                boolean hasReject = actions.stream().anyMatch(a -> a.code() == OrderActionCode.REJECT);

                if (status == OrderStatus.AWAITING_APPROVAL) {
                    assertThat(hasApprove)
                            .as("%s/%s offers APPROVE", status, mode)
                            .isTrue();
                    assertThat(hasReject)
                            .as("%s/%s offers REJECT", status, mode)
                            .isTrue();
                } else {
                    assertThat(hasApprove)
                            .as("%s/%s must not offer APPROVE", status, mode)
                            .isFalse();
                    assertThat(hasReject)
                            .as("%s/%s must not offer REJECT", status, mode)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void everyAdvanceTargetIsExactlyWhatTheStateMachinePermitsWhenAdvanceIsGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderStatus> offered = targetsOf(status, mode);

                // The exact call OrderStateService.advance makes: permits(from, to,
                // mode). CANCELLED is carved out because it is offered under its own
                // action code, not ADVANCE.
                List<OrderStatus> expected = OrderStateMachine.transitionsFrom(status).stream()
                        .filter(target -> target != OrderStatus.CANCELLED)
                        .filter(target -> OrderStateMachine.permits(status, target, mode))
                        .toList();

                assertThat(offered)
                        .as("advance targets for %s/%s", status, mode)
                        .containsExactlyInAnyOrderElementsOf(expected);
            }
        }
    }

    /**
     * The one status where fulfilment mode actually changes the answer: a
     * pickup or dine-in order must never be offered {@code READY -> FULFILLING}
     * and a delivery order must never be offered {@code READY -> COMPLETED} —
     * exactly the split {@code OrderStateMachine.permits(from, to, mode)}
     * enforces in the mutating endpoint.
     */
    @Test
    void readyOffersTheModeAppropriateAdvanceOnly() {
        assertThat(targetsOf(OrderStatus.READY, FulfillmentMode.DELIVERY)).containsExactly(OrderStatus.FULFILLING);
        assertThat(targetsOf(OrderStatus.READY, FulfillmentMode.PICKUP)).containsExactly(OrderStatus.COMPLETED);
        assertThat(targetsOf(OrderStatus.READY, FulfillmentMode.DINE_IN)).containsExactly(OrderStatus.COMPLETED);
    }

    /**
     * {@code COMPLETE} (wave P09, gap map {@code 1.2j}) is offered exactly
     * wherever the generic {@code ADVANCE} entry above would offer a
     * {@code COMPLETED} target — the same {@link OrderStateMachine#permits}
     * call, so the two can never disagree about when completion is legal.
     */
    @Test
    void completeAppearsExactlyWhereTheStateMachinePermitsCompletionWhenAdvanceIsGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                boolean offered = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS).stream()
                        .anyMatch(a -> a.code() == OrderActionCode.COMPLETE);
                boolean expected = OrderStateMachine.permits(status, OrderStatus.COMPLETED, mode);

                assertThat(offered).as("COMPLETE for %s/%s", status, mode).isEqualTo(expected);
            }
        }
    }

    /**
     * The pairing {@code OrderActionCode}'s own doc names: wherever the read
     * model offers the generic {@code ADVANCE} entry to {@code COMPLETED}, it
     * also offers {@code COMPLETE} beside it — never one without the other,
     * so a client that only recognises the older code still has a working
     * button and a client that prefers the newer one always finds it.
     */
    @Test
    void completeNeverAppearsWithoutTheAdvanceCompletedEntryOrViceVersa() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderAction> actions = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS);
                boolean hasComplete = actions.stream().anyMatch(a -> a.code() == OrderActionCode.COMPLETE);
                boolean hasAdvanceCompleted = actions.stream()
                        .anyMatch(
                                a -> a.code() == OrderActionCode.ADVANCE && a.targetStatus() == OrderStatus.COMPLETED);

                assertThat(hasComplete)
                        .as("%s/%s COMPLETE vs ADVANCE(COMPLETED)", status, mode)
                        .isEqualTo(hasAdvanceCompleted);
            }
        }
    }

    /**
     * Wave P09 (gap map {@code 1.2k}) widened the gate: {@code CANCEL} is
     * offered wherever {@link OrderStateMachine} has any edge to {@code
     * CANCELLED} at all, not only where a <em>reasonless</em> cancellation
     * would be accepted. {@link #theReasonlessGuardStaysNarrowEvenThoughTheActionIsOfferedMoreWidely}
     * is the companion assertion that the narrower guard itself is untouched.
     */
    @Test
    void cancelAppearsExactlyWhereTheStateMachineHasAnEdgeToCancelledWhenCancelIsGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                boolean offered = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS).stream()
                        .anyMatch(a -> a.code() == OrderActionCode.CANCEL);

                boolean expected = OrderStateMachine.permits(status, OrderStatus.CANCELLED);

                assertThat(offered).as("cancel for %s/%s", status, mode).isEqualTo(expected);
            }
        }
    }

    /**
     * Names the concrete rule orders.md §0.3/§1.1/§4.5 documents post-wave-P09:
     * every non-terminal status is cancellable now that the console's dialog
     * can supply a registry reason from {@code CONFIRMED} onward, so a
     * refactor that kept the predicates individually correct but broke this
     * specific, customer-visible boundary still fails a test that says what
     * broke.
     */
    @Test
    void cancelIsOfferedFromEveryNonTerminalStatusNowThatTheDialogCanSupplyAReason() {
        for (OrderStatus status : OrderStatus.values()) {
            boolean offered =
                    OrderActionsPolicy.availableFor(status, FulfillmentMode.DELIVERY, ALL_ACTION_CAPS).stream()
                            .anyMatch(a -> a.code() == OrderActionCode.CANCEL);
            assertThat(offered).as("%s cancellable today", status).isEqualTo(!status.terminal());
        }
    }

    /**
     * The gate {@code CANCEL}'s emission widened past (wave P09) is untouched:
     * {@code OrderStateService.cancel}'s reasonless overload still refuses
     * exactly {@code CONFIRMED}/{@code PREPARING}/{@code READY}/{@code
     * FULFILLING}, precisely as it did before this wave — a terminal status
     * such as {@code PAYMENT_FAILED} reads as reasonless-cancellable by this
     * predicate alone (it is simply not one of the four excluded statuses)
     * and is refused only by {@link OrderStateMachine#permits}'s absent
     * {@code CANCELLED} edge, a fact {@link
     * #cancelAppearsExactlyWhereTheStateMachineHasAnEdgeToCancelledWhenCancelIsGranted}
     * covers, not this test. Read directly from the guard {@code
     * OrderStateService.cancel} itself calls, so this and the production code
     * cannot silently drift apart.
     */
    @Test
    void theReasonlessGuardStaysNarrowEvenThoughTheActionIsOfferedMoreWidely() {
        EnumSet<OrderStatus> excludedFromReasonless =
                EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.FULFILLING);

        for (OrderStatus status : OrderStatus.values()) {
            assertThat(OrderActionsPolicy.canCancelWithoutReason(status))
                    .as("%s reasonless-cancellable", status)
                    .isEqualTo(!excludedFromReasonless.contains(status));
        }
    }

    // ------------------------------------------- compensating override (ADR 0110, wave P41)

    /** {@link #ALL_ACTION_CAPS} plus {@code ORDER_STATE_OVERRIDE}, for the override-specific tests below. */
    private static final Set<Capability> ALL_ACTION_CAPS_WITH_OVERRIDE;

    static {
        EnumSet<Capability> caps = EnumSet.copyOf(ALL_ACTION_CAPS);
        caps.add(Capability.ORDER_STATE_OVERRIDE);
        ALL_ACTION_CAPS_WITH_OVERRIDE = caps;
    }

    /**
     * Drift-proofed exactly like {@link #everyAdvanceTargetIsExactlyWhatTheStateMachinePermitsWhenAdvanceIsGranted}:
     * the offered {@code OVERRIDE} targets are read back against {@link
     * OrderStateMachine#compensatingTransitionsFrom}, not a hand-written table,
     * so a third compensating edge added to the machine without a matching
     * branch here would fail this test rather than silently ship unoffered.
     */
    @Test
    void everyOverrideTargetIsExactlyWhatTheMachineDeclaresCompensatingWhenOverrideIsGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderStatus> offered =
                        OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS_WITH_OVERRIDE).stream()
                                .filter(a -> a.code() == OrderActionCode.OVERRIDE)
                                .map(OrderAction::targetStatus)
                                .toList();

                assertThat(offered)
                        .as("override targets for %s/%s", status, mode)
                        .containsExactlyInAnyOrderElementsOf(OrderStateMachine.compensatingTransitionsFrom(status));
            }
        }
    }

    /** Names the two edges directly, so a change to the machine's compensating table cannot pass unnoticed. */
    @Test
    void overrideOffersExactlyTheTwoNamedCompensatingEdges() {
        assertThat(OrderActionsPolicy.availableFor(
                        OrderStatus.READY, FulfillmentMode.DELIVERY, ALL_ACTION_CAPS_WITH_OVERRIDE))
                .filteredOn(a -> a.code() == OrderActionCode.OVERRIDE)
                .extracting(OrderAction::targetStatus)
                .containsExactly(OrderStatus.PREPARING);
        assertThat(OrderActionsPolicy.availableFor(
                        OrderStatus.FULFILLING, FulfillmentMode.DELIVERY, ALL_ACTION_CAPS_WITH_OVERRIDE))
                .filteredOn(a -> a.code() == OrderActionCode.OVERRIDE)
                .extracting(OrderAction::targetStatus)
                .containsExactly(OrderStatus.READY);
    }

    /**
     * The brief's own trap, stated as a test: an {@code ORDER_ADVANCE} holder —
     * every line cook — never sees {@code OVERRIDE} on any status or mode,
     * however far its grant otherwise reaches, because {@code
     * ORDER_STATE_OVERRIDE} is absent from {@link #ALL_ACTION_CAPS}.
     */
    @Test
    void overrideNeverAppearsForAPrincipalWithoutTheOverrideCapability() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                assertThat(OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS))
                        .as("%s/%s without ORDER_STATE_OVERRIDE", status, mode)
                        .noneMatch(a -> a.code() == OrderActionCode.OVERRIDE);
            }
        }
    }

    /** Terminal orders stay terminal (the brief's other named trap): no override, even fully granted. */
    @Test
    void terminalStatusesOfferNoOverrideEvenWhenGranted() {
        for (OrderStatus status : OrderStatus.values()) {
            if (!status.terminal()) {
                continue;
            }
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                assertThat(OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS_WITH_OVERRIDE))
                        .as("%s/%s is terminal", status, mode)
                        .isEmpty();
            }
        }
    }

    /**
     * {@code TENANT_ADMIN} and {@code TENANT_OWNER} are the only two {@link
     * PlatformRole} bundles holding {@code ORDER_STATE_OVERRIDE} today — "almost
     * nobody" as {@link Capability#ORDER_ADVANCE}'s own doc puts it.
     * {@code LOCATION_MANAGER}, which otherwise holds every other action
     * capability, must still never see {@code OVERRIDE}.
     */
    @Test
    void onlyTenantAdminAndTenantOwnerHoldTheOverrideCapabilityAmongInspectedRoles() {
        assertThat(PlatformRole.TENANT_ADMIN.capabilities()).contains(Capability.ORDER_STATE_OVERRIDE);
        assertThat(PlatformRole.TENANT_OWNER.capabilities()).contains(Capability.ORDER_STATE_OVERRIDE);
        assertThat(PlatformRole.LOCATION_MANAGER.capabilities()).doesNotContain(Capability.ORDER_STATE_OVERRIDE);
        assertThat(PlatformRole.LOCATION_STAFF.capabilities()).doesNotContain(Capability.ORDER_STATE_OVERRIDE);

        assertThat(OrderActionsPolicy.availableFor(
                        OrderStatus.READY, FulfillmentMode.DELIVERY, PlatformRole.TENANT_ADMIN.capabilities()))
                .anyMatch(a -> a.code() == OrderActionCode.OVERRIDE);
        assertThat(OrderActionsPolicy.availableFor(
                        OrderStatus.READY, FulfillmentMode.DELIVERY, PlatformRole.LOCATION_MANAGER.capabilities()))
                .noneMatch(a -> a.code() == OrderActionCode.OVERRIDE);
    }

    /** A terminal order offers nothing at all — not even a read-only advance. */
    @Test
    void terminalStatusesOfferNoActions() {
        for (OrderStatus status : OrderStatus.values()) {
            if (!status.terminal()) {
                continue;
            }
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                assertThat(OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS))
                        .as("%s/%s is terminal", status, mode)
                        .isEmpty();
            }
        }
    }

    /**
     * {@code AMEND}'s gate ({@code canAmend}: legal on any order that has not
     * ended, regardless of fulfilment mode or advance/cancel state, mirroring
     * {@code OrderAmendmentService.propose}'s own status guard) was built and
     * correct from wave P05, but held behind {@code
     * OrderActionsPolicy.AMEND_EMISSION_ENABLED} because that wave's own
     * adversarial review found the console had no translated label or click
     * handler for a code {@code ORDER_AMEND} already reached five real roles
     * with (ADR 0105). Wave P10 gave it both — {@code order-actions.ts}'s
     * real case, {@code order-detail-pane.ts}'s {@code q-order-amend-menu} —
     * and flipped the constant to {@code true} (ADR 0113). This test proves
     * the flip did exactly what it was designed to: {@code AMEND} appears in
     * {@code actions[]} wherever {@code canAmend} and {@code ORDER_AMEND}
     * both hold, on every status and mode, and never for a principal missing
     * the capability. {@link #amendsGateMatchesCanAmend} is the companion
     * assertion that the gate itself computes what this test also reads
     * directly, so the two cannot drift apart unnoticed.
     */
    @Test
    void amendIsEmittedWhereverTheGateAndGrantBothHold() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                boolean withFullGrant = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS).stream()
                        .anyMatch(a -> a.code() == OrderActionCode.AMEND);
                assertThat(withFullGrant)
                        .as("%s/%s AMEND, granted", status, mode)
                        .isEqualTo(OrderActionsPolicy.canAmend(status));

                boolean withoutGrant =
                        OrderActionsPolicy.availableFor(status, mode, EnumSet.noneOf(Capability.class)).stream()
                                .anyMatch(a -> a.code() == OrderActionCode.AMEND);
                assertThat(withoutGrant)
                        .as("%s/%s amend, ungranted", status, mode)
                        .isFalse();
            }
        }
    }

    /**
     * The predicate {@code availableFor}'s {@code AMEND} branch reads,
     * asserted directly — the same one {@link #amendIsEmittedWhereverTheGateAndGrantBothHold}
     * exercises indirectly through {@code availableFor} itself. If the two
     * ever disagreed, the branch would be emitting something other than what
     * this test says {@code canAmend} computes.
     */
    @Test
    void amendsGateMatchesCanAmend() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(OrderActionsPolicy.canAmend(status))
                    .as("%s canAmend", status)
                    .isEqualTo(!status.terminal());
        }
    }

    // -------------------------------------------------- principal-aware (P05)

    /**
     * The exact bug 1.2a named: {@code LOCATION_STAFF} holds {@code
     * ORDER_APPROVE}/{@code ORDER_ADVANCE} and neither {@code ORDER_CANCEL} nor
     * {@code ORDER_AMEND}, so it must never see «Отменить» or the amendment
     * action, on any order, at any status where a fuller grant would have
     * offered one.
     */
    @Test
    void locationStaffNeverOffersCancelOrAmend() {
        Set<Capability> granted = PlatformRole.LOCATION_STAFF.capabilities();
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderActionCode> codes = codesOf(status, mode, granted);
                assertThat(codes)
                        .as("location-staff at %s/%s", status, mode)
                        .doesNotContain(OrderActionCode.CANCEL, OrderActionCode.AMEND);
            }
        }
        // And on a status where CANCEL and AMEND would otherwise be legal (RECEIVED
        // is cancellable and amendable), staff still gets only what its own grant
        // justifies: three ADVANCE targets (docs/domains/state-machines.md), never
        // a CANCEL or AMEND alongside them.
        List<OrderActionCode> onReceived = codesOf(OrderStatus.RECEIVED, FulfillmentMode.DELIVERY, granted);
        assertThat(onReceived).isNotEmpty().containsOnly(OrderActionCode.ADVANCE);
    }

    /**
     * {@code SUPPORT_AGENT} holds exactly the inverse of {@code
     * LOCATION_STAFF} among these four: {@code ORDER_CANCEL} and {@code
     * ORDER_AMEND}, never {@code ORDER_APPROVE} or {@code ORDER_ADVANCE}.
     * Wave P10 flipped {@code AMEND_EMISSION_ENABLED}, so both of this role's
     * two capabilities are now visible: {@code CANCEL} wherever {@code
     * canCancel} holds, {@code AMEND} wherever {@code canAmend} holds, and
     * never {@code APPROVE}/{@code REJECT}/{@code ADVANCE}, which it holds
     * neither capability for.
     */
    @Test
    void supportAgentOffersExactlyCancelAndAmend() {
        Set<Capability> granted = PlatformRole.SUPPORT_AGENT.capabilities();
        assertThat(granted).contains(Capability.ORDER_CANCEL, Capability.ORDER_AMEND);
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                List<OrderActionCode> codes = codesOf(status, mode, granted);
                assertThat(codes)
                        .as("support-agent at %s/%s", status, mode)
                        .doesNotContain(OrderActionCode.APPROVE, OrderActionCode.REJECT, OrderActionCode.ADVANCE);
                assertThat(codes.contains(OrderActionCode.AMEND))
                        .as("%s/%s AMEND", status, mode)
                        .isEqualTo(OrderActionsPolicy.canAmend(status));
                assertThat(codes.contains(OrderActionCode.CANCEL))
                        .as("%s/%s CANCEL", status, mode)
                        .isEqualTo(OrderActionsPolicy.canCancel(status));
            }
        }
        // RECEIVED is cancellable and amendable; support-agent's grant covers
        // both, and both are now visible together — CANCEL added before AMEND
        // in availableFor's own order.
        assertThat(codesOf(OrderStatus.RECEIVED, FulfillmentMode.DELIVERY, granted))
                .containsExactly(OrderActionCode.CANCEL, OrderActionCode.AMEND);
    }

    /**
     * {@code TENANT_FINANCE}, {@code BRAND_MANAGER} and {@code
     * COURIER_DISPATCHER} each hold {@code ORDER_READ} and none of {@code
     * ORDER_APPROVE}/{@code ORDER_ADVANCE}/{@code ORDER_CANCEL}/{@code
     * ORDER_AMEND} — the gap map named all three as getting the false
     * «Отменить» offer alongside {@code LOCATION_STAFF} and {@code
     * SUPPORT_AGENT}. Each must see an empty {@code actions[]} everywhere.
     */
    @Test
    void readOnlyRolesNeverOfferAnyAction() {
        for (PlatformRole role :
                List.of(PlatformRole.TENANT_FINANCE, PlatformRole.BRAND_MANAGER, PlatformRole.COURIER_DISPATCHER)) {
            Set<Capability> granted = role.capabilities();
            assertThat(granted)
                    .as("%s must hold none of the four gating capabilities for this test to prove anything", role)
                    .doesNotContain(
                            Capability.ORDER_APPROVE,
                            Capability.ORDER_ADVANCE,
                            Capability.ORDER_CANCEL,
                            Capability.ORDER_AMEND);

            for (OrderStatus status : OrderStatus.values()) {
                for (FulfillmentMode mode : FulfillmentMode.values()) {
                    assertThat(OrderActionsPolicy.availableFor(status, mode, granted))
                            .as("%s at %s/%s", role, status, mode)
                            .isEmpty();
                }
            }
        }
    }

    /** {@code LOCATION_MANAGER} holds all four and gets everything its status permits. */
    @Test
    void locationManagerGetsExactlyWhatTheFullGrantJustifies() {
        Set<Capability> granted = PlatformRole.LOCATION_MANAGER.capabilities();
        assertThat(granted).containsAll(ALL_ACTION_CAPS);

        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                assertThat(OrderActionsPolicy.availableFor(status, mode, granted))
                        .as("%s/%s", status, mode)
                        .isEqualTo(OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS));
            }
        }
    }

    // ---------------------------------------------- every code has a route (P05)

    /**
     * {@link OrderActionCode}'s widened set named four routes that did not
     * exist from an order when wave P05 declared them. {@code COMPLETE} is
     * wired (wave P09, gap map {@code 1.2j}); {@code ASSIGN_COURIER} is wired
     * too as of this wave (gap map {@code 1.1e}) — the four-argument {@code
     * availableFor} overload emits it, and the console opens the order to
     * reach the existing assign control there. {@code RESOLVE} still needs
     * per-amendment state {@code availableFor} does not carry, and {@code
     * ISSUE_INVOICE} still has no endpoint at all (gap map {@code P12}). This
     * switch is exhaustive on purpose: adding a ninth {@link OrderActionCode}
     * constant without adding a branch here fails to <em>compile</em>, so a
     * future change cannot silently start emitting a code from {@link
     * OrderActionsPolicy#availableFor} without this test being forced to take
     * a position on whether that code has a real route.
     *
     * <p>{@code AMEND} answers {@code true} here — its route ({@code POST
     * .../amendments}) genuinely exists and its gate is built, and as of wave
     * P10 it is emitted too (see {@link #amendIsEmittedWhereverTheGateAndGrantBothHold}).
     * "Has a route" and "is emitted" stayed different questions for this code
     * through wave P05's hold — a frontend-readiness decision (ADR 0105), not
     * a missing endpoint — and ADR 0113 is the record of the hold being
     * lifted.
     */
    private static boolean hasRealRouteToday(OrderActionCode code) {
        return switch (code) {
            case APPROVE, REJECT -> true; // POST .../approval-decisions
            case ADVANCE -> true; // POST .../state-actions
            case CANCEL -> true; // POST .../cancellations
            case AMEND -> true; // POST .../amendments
            case OVERRIDE -> true; // POST .../state-overrides (ADR 0110, wave P41)
            case COMPLETE -> true; // POST .../completion (wave P09)
            case ASSIGN_COURIER -> true; // DispatchController .../dispatch/plans/{id}/assign (gap map 1.1e)
            case RESOLVE, ISSUE_INVOICE -> false;
        };
    }

    @Test
    void everyCodeWithARealRouteIsAccountedForByName() {
        assertThat(EnumSet.allOf(OrderActionCode.class).stream().filter(OrderActionsPolicyTests::hasRealRouteToday))
                .containsExactlyInAnyOrder(
                        OrderActionCode.APPROVE,
                        OrderActionCode.REJECT,
                        OrderActionCode.ADVANCE,
                        OrderActionCode.CANCEL,
                        OrderActionCode.AMEND,
                        OrderActionCode.OVERRIDE,
                        OrderActionCode.COMPLETE,
                        OrderActionCode.ASSIGN_COURIER);
    }

    /**
     * The property orders.md §4.2 exists for: {@code availableFor} never
     * offers a code with no endpoint behind it, at any status, mode or grant —
     * the array cannot lead an operator to a dead end. Swept over the
     * four-argument overload (the maximal case: it only ever adds to the
     * three-argument form's own result — see {@link
     * #theFourArgumentOverloadAddsOnlyAssignCourierOnTopOfTheThreeArgumentForm})
     * so {@code ASSIGN_COURIER} is covered by the same sweep as everything
     * else.
     */
    @Test
    void theArrayNeverOffersAnActionWithNoRoute() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                // The full grant is the maximal case: availableFor only ever adds
                // actions for a capability present, so anything offered with less
                // than the full grant is offered with the full grant too. Sweeping
                // the maximum is exhaustive for "is this code ever emitted at all".
                for (OrderAction action :
                        OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS_WITH_COURIER_ASSIGN, true)) {
                    assertThat(hasRealRouteToday(action.code()))
                            .as("%s offered at %s/%s must have a real route", action.code(), status, mode)
                            .isTrue();
                }
            }
        }
    }

    // -------------------------------------------------- assign courier (gap map 1.1e)

    private static final Set<Capability> ALL_ACTION_CAPS_WITH_COURIER_ASSIGN;

    static {
        EnumSet<Capability> caps = EnumSet.copyOf(ALL_ACTION_CAPS);
        caps.add(Capability.DELIVERY_MANUAL_ASSIGN);
        ALL_ACTION_CAPS_WITH_COURIER_ASSIGN = caps;
    }

    /**
     * The four-argument overload never widens what the three-argument form
     * already offers — it only ever adds {@code ASSIGN_COURIER} on top.
     */
    @Test
    void theFourArgumentOverloadAddsOnlyAssignCourierOnTopOfTheThreeArgumentForm() {
        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                for (boolean courierUnassigned : new boolean[] {true, false}) {
                    List<OrderAction> base = OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS);
                    List<OrderAction> withCourier =
                            OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS, courierUnassigned);

                    List<OrderAction> withoutAssignCourier = withCourier.stream()
                            .filter(a -> a.code() != OrderActionCode.ASSIGN_COURIER)
                            .toList();
                    assertThat(withoutAssignCourier)
                            .as("%s/%s courierUnassigned=%s, minus ASSIGN_COURIER", status, mode, courierUnassigned)
                            .isEqualTo(base);
                }
            }
        }
    }

    /**
     * {@code ASSIGN_COURIER} is offered exactly for a {@code DELIVERY} order
     * from {@code CONFIRMED} through {@code FULFILLING} (the window {@code
     * DeliveryPlanTrigger} keeps a plan open in) when nobody is carrying it
     * yet and the caller holds {@code DELIVERY_MANUAL_ASSIGN} — never for a
     * pickup or dine-in order, never once a courier is already assigned, and
     * never without the capability.
     */
    @Test
    void assignCourierAppearsExactlyForAnUnassignedOpenDeliveryPlanWhenGranted() {
        EnumSet<OrderStatus> planOpenWindow =
                EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.FULFILLING);

        for (OrderStatus status : OrderStatus.values()) {
            for (FulfillmentMode mode : FulfillmentMode.values()) {
                boolean offered =
                        OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS_WITH_COURIER_ASSIGN, true)
                                .stream()
                                .anyMatch(a -> a.code() == OrderActionCode.ASSIGN_COURIER);
                boolean expected = mode == FulfillmentMode.DELIVERY && planOpenWindow.contains(status);

                assertThat(offered).as("ASSIGN_COURIER for %s/%s", status, mode).isEqualTo(expected);
            }
        }
    }

    @Test
    void assignCourierNeverAppearsOnceACourierIsAlreadyAssigned() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(OrderActionsPolicy.availableFor(
                            status, FulfillmentMode.DELIVERY, ALL_ACTION_CAPS_WITH_COURIER_ASSIGN, false))
                    .as("%s, courier already assigned", status)
                    .noneMatch(a -> a.code() == OrderActionCode.ASSIGN_COURIER);
        }
    }

    @Test
    void assignCourierNeverAppearsWithoutTheDeliveryManualAssignCapability() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(OrderActionsPolicy.availableFor(status, FulfillmentMode.DELIVERY, ALL_ACTION_CAPS, true))
                    .as("%s, ungranted", status)
                    .noneMatch(a -> a.code() == OrderActionCode.ASSIGN_COURIER);
        }
    }

    private static List<OrderStatus> targetsOf(OrderStatus status, FulfillmentMode mode) {
        return OrderActionsPolicy.availableFor(status, mode, ALL_ACTION_CAPS).stream()
                .filter(a -> a.code() == OrderActionCode.ADVANCE)
                .map(OrderAction::targetStatus)
                .toList();
    }

    private static List<OrderActionCode> codesOf(OrderStatus status, FulfillmentMode mode, Set<Capability> granted) {
        return OrderActionsPolicy.availableFor(status, mode, granted).stream()
                .map(OrderAction::code)
                .toList();
    }
}
