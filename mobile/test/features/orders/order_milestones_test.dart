import 'package:flutter_test/flutter_test.dart';
import 'package:qoida_mobile/src/features/orders/application/order_milestones.dart';
import 'package:qoida_mobile/src/features/orders/data/order_codes.dart';

/// ADR 0045's milestone table, asserted row by row.
///
/// This is the file that fails if somebody adds a step the ADR does not have —
/// a courier position, a distance, a countdown — or removes the distinction
/// between a pickup and a delivery.
void main() {
  List<OrderMilestone> stepsFor(
    OrderStatus status, {
    FulfillmentMode? mode = FulfillmentMode.delivery,
  }) => milestonesForStatus(status: status, mode: mode)
      .map((MilestoneStep step) => step.milestone)
      .toList();

  MilestoneState stateOf(
    OrderStatus status,
    OrderMilestone milestone, {
    FulfillmentMode? mode = FulfillmentMode.delivery,
  }) => milestonesForStatus(status: status, mode: mode)
      .firstWhere((MilestoneStep step) => step.milestone == milestone)
      .state;

  group('the sequence', () {
    test('a delivery has five steps, ending in the handover', () {
      expect(stepsFor(OrderStatus.confirmed), <OrderMilestone>[
        OrderMilestone.accepted,
        OrderMilestone.preparing,
        OrderMilestone.ready,
        OrderMilestone.onTheWay,
        OrderMilestone.handedOver,
      ]);
    });

    test('a pickup has no on-the-way step at all', () {
      // ADR 0019: pickup orders never enter FULFILLING. A step that can never
      // be reached is a step that makes the list look stalled.
      expect(
        stepsFor(OrderStatus.ready, mode: FulfillmentMode.pickup),
        isNot(contains(OrderMilestone.onTheWay)),
      );
      expect(
        stepsFor(OrderStatus.ready, mode: FulfillmentMode.dineIn),
        isNot(contains(OrderMilestone.onTheWay)),
      );
    });

    test('an unknown mode takes the step only once FULFILLING proves it', () {
      // The storefront response does not carry the mode today. Reaching
      // FULFILLING is itself the proof that something is delivering this order.
      expect(
        stepsFor(OrderStatus.preparing, mode: null),
        isNot(contains(OrderMilestone.onTheWay)),
      );
      expect(
        stepsFor(OrderStatus.fulfilling, mode: null),
        contains(OrderMilestone.onTheWay),
      );
    });
  });

  group('where each status sits', () {
    test('CONFIRMED makes acceptance the current step', () {
      expect(
        stateOf(OrderStatus.confirmed, OrderMilestone.accepted),
        MilestoneState.current,
      );
      expect(
        stateOf(OrderStatus.confirmed, OrderMilestone.preparing),
        MilestoneState.pending,
      );
    });

    test('PREPARING marks acceptance done and preparation current', () {
      expect(
        stateOf(OrderStatus.preparing, OrderMilestone.accepted),
        MilestoneState.done,
      );
      expect(
        stateOf(OrderStatus.preparing, OrderMilestone.preparing),
        MilestoneState.current,
      );
    });

    test('READY is the ready step, whatever the mode calls it', () {
      expect(
        stateOf(OrderStatus.ready, OrderMilestone.ready),
        MilestoneState.current,
      );
      expect(
        stateOf(
          OrderStatus.ready,
          OrderMilestone.ready,
          mode: FulfillmentMode.pickup,
        ),
        MilestoneState.current,
      );
    });

    test('FULFILLING is the handover-to-courier step', () {
      expect(
        stateOf(OrderStatus.fulfilling, OrderMilestone.onTheWay),
        MilestoneState.current,
      );
      expect(
        stateOf(OrderStatus.fulfilling, OrderMilestone.ready),
        MilestoneState.done,
      );
    });

    test('COMPLETED leaves nothing current: every step is done', () {
      final List<MilestoneStep> steps = milestonesForStatus(
        status: OrderStatus.completed,
        mode: FulfillmentMode.delivery,
      );
      expect(steps.every((MilestoneStep step) => step.isDone), isTrue);
      expect(steps.any((MilestoneStep step) => step.isCurrent), isFalse);
    });

    test('before acceptance every step is pending and none is claimed', () {
      for (final OrderStatus status in <OrderStatus>[
        OrderStatus.received,
        OrderStatus.paymentAuthorizing,
        OrderStatus.awaitingApproval,
      ]) {
        final List<MilestoneStep> steps = milestonesForStatus(
          status: status,
          mode: FulfillmentMode.delivery,
        );
        expect(steps, isNotEmpty, reason: '${status.value} shows the road ahead');
        expect(
          steps.every((MilestoneStep step) => step.state == MilestoneState.pending),
          isTrue,
          reason: '${status.value} claims nothing has happened yet',
        );
      }
    });
  });

  group('an order that ended without arriving', () {
    test('draws no rail: how far it got is not knowable from a status', () {
      for (final OrderStatus status in <OrderStatus>[
        OrderStatus.cancelled,
        OrderStatus.rejected,
        OrderStatus.expired,
        OrderStatus.paymentFailed,
      ]) {
        expect(
          milestonesForStatus(status: status),
          isEmpty,
          reason: '${status.value} is answered by the outcome panel',
        );
      }
    });
  });

  group('a status this build does not know', () {
    test('draws no rail rather than a wrong one', () {
      expect(
        milestonesForStatus(status: OrderStatus.parse('QUANTUM_SUPERPOSITION')),
        isEmpty,
      );
    });

    test('is not treated as terminal, so the live view keeps polling', () {
      expect(OrderStatus.parse('SOMETHING_NEW').isTerminal, isFalse);
    });
  });

  group('timestamps', () {
    test('only the two steps that have a recorded time carry one', () {
      final DateTime confirmedAt = DateTime.utc(2026, 8, 24, 9, 2);
      final DateTime closedAt = DateTime.utc(2026, 8, 24, 9, 51);
      final List<MilestoneStep> steps = milestonesForStatus(
        status: OrderStatus.completed,
        mode: FulfillmentMode.delivery,
        confirmedAt: confirmedAt,
        closedAt: closedAt,
      );

      final Map<OrderMilestone, DateTime?> times = <OrderMilestone, DateTime?>{
        for (final MilestoneStep step in steps) step.milestone: step.at,
      };

      expect(times[OrderMilestone.accepted], confirmedAt);
      expect(times[OrderMilestone.handedOver], closedAt);
      // No storefront endpoint publishes the transition log, so the middle
      // steps have no time. A plausible one would be indistinguishable from a
      // real one.
      expect(times[OrderMilestone.preparing], isNull);
      expect(times[OrderMilestone.ready], isNull);
      expect(times[OrderMilestone.onTheWay], isNull);
    });

    test('a step not yet reached never shows a time', () {
      final List<MilestoneStep> steps = milestonesForStatus(
        status: OrderStatus.preparing,
        mode: FulfillmentMode.delivery,
        closedAt: DateTime.utc(2026, 8, 24, 9, 51),
      );
      expect(
        steps
            .firstWhere(
              (MilestoneStep step) => step.milestone == OrderMilestone.handedOver,
            )
            .at,
        isNull,
      );
    });
  });
}
