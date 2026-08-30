/// The milestones a customer is shown, and nothing else (ADR 0045).
///
/// ADR 0045 settled on 2026-08-23 that customers see **status milestones only**.
/// A courier's position is ingested for dispatch and never published: no map,
/// no pin, no vehicle, no plate, no phone number, and no continuously
/// recomputed ETA — because an ETA that ticks down every minute is a coarse
/// position feed wearing a clock face.
///
/// This file is the whole of the derivation, kept as a pure function over
/// facts the order already carries. Nothing here is computed from a coordinate,
/// because there is no coordinate in the model to compute from.
///
/// | Milestone | Source, per ADR 0045 |
/// |---|---|
/// | Accepted | `OrderConfirmed` / ADR 0019 `CONFIRMED` |
/// | Preparing | ADR 0019 `PREPARING`, proposed by ADR 0041's ticket reaching `IN_PRODUCTION` |
/// | Ready | ADR 0019 `READY` — "ready to collect" for pickup, "awaiting courier" for delivery |
/// | On the way | ADR 0019 `FULFILLING`, entered at handover to the courier |
/// | Handed over | ADR 0019 `COMPLETED` |
///
/// `FULFILLING` is the milestone that replaces the map, and it is the right
/// one for a reason worth keeping in the code: it is entered at handover, which
/// is a recorded business transition, not an inference about where a phone is.
library;

import '../data/order_codes.dart';
import '../data/order_models.dart';


/// The five steps, in the order they happen.
enum OrderMilestone {
  accepted,
  preparing,

  /// Cooked and waiting: for a courier on a delivery, for the customer on a
  /// pickup. One step, because it is one fact about the food.
  ready,

  /// Delivery only. `OrderStatus.fulfilling` — a pickup order never enters it.
  onTheWay,

  /// Delivered, collected, or served, according to the fulfilment mode.
  handedOver,
}

enum MilestoneState { done, current, pending }

final class MilestoneStep {
  const MilestoneStep({required this.milestone, required this.state, this.at});

  final OrderMilestone milestone;
  final MilestoneState state;

  /// When this step happened, where the order carries the fact.
  ///
  /// Only two of the five have a timestamp today: acceptance has `confirmedAt`
  /// and the handover has `closedAt`. The platform records every transition in
  /// `ordering.order_transitions` and `OperationsOrderController` exposes it at
  /// `/timeline`, but no storefront endpoint does — so the intermediate steps
  /// are shown without a time rather than with a plausible one. A guessed
  /// timestamp beside a real one is indistinguishable from a real one.
  final DateTime? at;

  bool get isDone => state == MilestoneState.done;
  bool get isCurrent => state == MilestoneState.current;
}

/// The rail for one order.
///
/// Empty means "do not draw a rail":
///
/// * A terminal order that did not complete — cancelled, rejected, expired,
///   payment failed — because how far it got before it ended is not knowable
///   from a status alone, and the outcome panel is what the customer is owed
///   instead (ADR 0039).
/// * A status this build does not recognise, because a rail is a claim about
///   where in the sequence an order is and an unknown status supports no such
///   claim.
///
/// Before the restaurant accepts, every step is [MilestoneState.pending]: the
/// road ahead is shown and nothing is claimed to have happened.
List<MilestoneStep> milestonesFor(OrderDetail order) => milestonesForStatus(
  status: order.status,
  mode: order.fulfillmentMode,
  confirmedAt: order.confirmedAt,
  closedAt: order.closedAt,
);

/// The same derivation over loose facts, for a caller that does not have a
/// whole [OrderDetail] — a list row, or a test.
List<MilestoneStep> milestonesForStatus({
  required OrderStatus status,
  FulfillmentMode? mode,
  DateTime? confirmedAt,
  DateTime? closedAt,
}) {
  if (!status.isKnown) return const <MilestoneStep>[];
  if (status.isTerminal && status != OrderStatus.completed) {
    return const <MilestoneStep>[];
  }

  final List<OrderMilestone> steps = <OrderMilestone>[
    OrderMilestone.accepted,
    OrderMilestone.preparing,
    OrderMilestone.ready,
    // A pickup order never enters `FULFILLING`, so the step is not part of its
    // sequence at all. Where the mode is absent — today's storefront response
    // does not send it — the status itself settles it: an order that reached
    // `FULFILLING` is being delivered by something.
    if (mode == FulfillmentMode.delivery ||
        (mode == null && status == OrderStatus.fulfilling))
      OrderMilestone.onTheWay,
    OrderMilestone.handedOver,
  ];

  final OrderMilestone? reachedStep = _reachedBy(status);
  // -1 leaves every step pending, which is the correct picture for an order the
  // restaurant has not accepted yet.
  final int reached = reachedStep == null ? -1 : steps.indexOf(reachedStep);

  return <MilestoneStep>[
    for (int i = 0; i < steps.length; i++)
      MilestoneStep(
        milestone: steps[i],
        state: switch (i) {
          _ when i < reached => MilestoneState.done,
          _ when i > reached => MilestoneState.pending,
          // The last reached step is "current" while the order is still moving
          // and "done" once it has finished.
          _ =>
            status.isTerminal ? MilestoneState.done : MilestoneState.current,
        },
        at: switch (steps[i]) {
          OrderMilestone.accepted => i <= reached ? confirmedAt : null,
          OrderMilestone.handedOver => i <= reached ? closedAt : null,
          _ => null,
        },
      ),
  ];
}

/// The step a status has reached, or null before the restaurant accepts.
///
/// Switched on the wire string rather than on the [OrderStatus] constants:
/// [OrderStatus] overrides `==` so that an unknown value can be decoded rather
/// than crash, and a type with a custom `==` is not a safe subject for constant
/// patterns.
OrderMilestone? _reachedBy(OrderStatus status) => switch (status.value) {
  'CONFIRMED' => OrderMilestone.accepted,
  'PREPARING' => OrderMilestone.preparing,
  'READY' => OrderMilestone.ready,
  'FULFILLING' => OrderMilestone.onTheWay,
  'COMPLETED' => OrderMilestone.handedOver,
  // RECEIVED, PAYMENT_AUTHORIZING, AWAITING_APPROVAL: the restaurant has not
  // committed, so the first milestone has not happened.
  _ => null,
};
