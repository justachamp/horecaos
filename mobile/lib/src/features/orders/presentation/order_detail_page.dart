import 'package:flutter/material.dart';

import '../../../api/api_exception.dart';
import '../../../design/q_empty_state.dart';
import '../../../design/horecaos_theme.dart';
import '../../../design/horecaos_tokens.dart';
import '../../../format/horecaos_formats.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../application/order_milestones.dart';
import '../application/order_tracking_controller.dart';
import '../data/order_codes.dart';
import '../data/order_models.dart';
import '../data/orders_repository.dart';
import 'order_strings.dart';
import 'widgets/milestone_rail.dart';
import 'widgets/order_receipt.dart';
import 'widgets/order_status_pill.dart';
import 'widgets/outcome_panel.dart';

/// One order: where it is, what was in it, what it cost, and how it is paid.
///
/// The live view and the receipt are one screen rather than two. They are the
/// same order, they come from the same read, and a separate tracking screen
/// would duplicate the header, the money and the polling for no gain.
///
/// The screen polls while the order is live and stops the moment it is not,
/// at ADR 0045's twenty seconds. It holds no socket, draws no map, and shows
/// no distance or countdown.
class OrderDetailPage extends StatefulWidget {
  const OrderDetailPage({
    required this.repository,
    required this.orderId,
    super.key,
    this.publicOrderNumber,
    this.onUpdated,
    this.now,
    this.pollInterval = OrderTrackingController.defaultPollInterval,
  });

  final OrdersRepository repository;
  final String orderId;

  /// The number the list already knows, so the title is right before the first
  /// read lands rather than empty for a moment.
  final String? publicOrderNumber;

  /// Called with each fresh read, so the list this was opened from can update
  /// the row without re-fetching a page it already has.
  final void Function(OrderSummary order)? onUpdated;

  final DateTime? now;
  final Duration pollInterval;

  @override
  State<OrderDetailPage> createState() => _OrderDetailPageState();
}

class _OrderDetailPageState extends State<OrderDetailPage>
    with WidgetsBindingObserver {
  late final OrderTrackingController _controller = OrderTrackingController(
    repository: widget.repository,
    orderId: widget.orderId,
    pollInterval: widget.pollInterval,
  );

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _controller.addListener(_publish);
    _controller.start();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller
      ..removeListener(_publish)
      ..dispose();
    super.dispose();
  }

  /// A polling loop that survives the application being backgrounded is a
  /// battery bill with nobody reading the answer.
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _controller.resume();
    } else {
      _controller.pause();
    }
  }

  void _publish() {
    final OrderDetail? order = _controller.order;
    if (order != null) {
      widget.onUpdated?.call(order.toSummary());
    }
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(
        title: ListenableBuilder(
          listenable: _controller,
          builder: (BuildContext context, Widget? _) {
            final String? number =
                _controller.order?.publicOrderNumber ?? widget.publicOrderNumber;
            return Text(number == null ? '' : l10n.ordersNumber(number));
          },
        ),
      ),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (BuildContext context, Widget? _) => _body(context, l10n),
      ),
    );
  }

  Widget _body(BuildContext context, AppLocalizations l10n) {
    final OrderDetail? order = _controller.order;
    final ApiFailure? failure = _controller.failure;

    if (order == null) {
      if (failure != null) {
        final ({String title, String body}) copy = OrderStrings.failure(
          l10n,
          failure,
        );
        return QEmptyState(
          title: copy.title,
          body: copy.body,
          actionLabel: l10n.retry,
          onAction: _controller.retry,
        );
      }
      return const Center(child: CircularProgressIndicator());
    }

    return _OrderBody(
      order: order,
      updatedAt: _controller.updatedAt,
      now: widget.now ?? DateTime.now(),
    );
  }
}

class _OrderBody extends StatelessWidget {
  const _OrderBody({
    required this.order,
    required this.now,
    this.updatedAt,
  });

  final OrderDetail order;
  final DateTime? updatedAt;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final String locale = Localizations.localeOf(context).toLanguageTag();

    final bool isLate = order.isLate(now);
    final List<MilestoneStep> steps = milestonesFor(order);
    final OrderOutcome? outcome = order.effectiveOutcome;
    final bool ended = order.status.isTerminal;

    return ListView(
      padding: const EdgeInsets.all(HorecaOSGeometry.spaceMd),
      children: <Widget>[
        Row(
          children: <Widget>[
            Flexible(
              child: OrderStatusPill(status: order.status, isLate: isLate),
            ),
          ],
        ),
        const SizedBox(height: HorecaOSGeometry.spaceSm),
        Text(
          l10n.ordersPlacedAt(
            HorecaOSFormats.dayMonthTime(
              HorecaOSFormats.toLocal(order.placedAt),
              locale: locale,
            ),
          ),
          style: text.bodySmall,
        ),

        // The promise, and only for an order that has not ended. Once an order
        // is over, what was promised is history and what happened is the
        // outcome below.
        if (!ended) ...<Widget>[
          const SizedBox(height: HorecaOSGeometry.spaceMd),
          _PromiseLine(order: order, isLate: isLate),
        ],

        if (order.status.isBeforeAcceptance) ...<Widget>[
          const SizedBox(height: HorecaOSGeometry.spaceSm),
          Text(
            l10n.orderAwaitingRestaurant,
            style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
          ),
        ],

        // How it ended, when it did not end by being handed over. A completed
        // order needs no panel: the rail's last step already says it arrived.
        if (ended && order.status != OrderStatus.completed && outcome != null)
          ...<Widget>[
            const SizedBox(height: HorecaOSGeometry.spaceMd),
            OutcomePanel(outcome: outcome),
          ],

        if (steps.isNotEmpty) ...<Widget>[
          const SizedBox(height: HorecaOSGeometry.spaceLg),
          SectionHeading(l10n.orderProgressHeading),
          const SizedBox(height: HorecaOSGeometry.spaceSm),
          MilestoneRail(
            steps: steps,
            mode: order.fulfillmentMode,
            courierFirstName: order.courierFirstName,
            isLate: isLate,
          ),
        ],

        const SizedBox(height: HorecaOSGeometry.spaceLg),
        SectionHeading(l10n.orderItemsHeading),
        const SizedBox(height: HorecaOSGeometry.spaceMd),
        OrderLinesPanel(lines: order.lines),

        const SizedBox(height: HorecaOSGeometry.spaceSm),
        OrderTotalsPanel(order: order),

        if (order.payment != null) ...<Widget>[
          const SizedBox(height: HorecaOSGeometry.spaceLg),
          SectionHeading(l10n.orderPaymentHeading),
          const SizedBox(height: HorecaOSGeometry.spaceSm),
          OrderPaymentPanel(payment: order.payment!),
        ],

        if (!ended && updatedAt != null) ...<Widget>[
          const SizedBox(height: HorecaOSGeometry.spaceLg),
          Text(
            l10n.orderUpdatedAt(
              HorecaOSFormats.time(
                HorecaOSFormats.toLocal(updatedAt!),
                locale: locale,
              ),
            ),
            style: text.bodySmall,
          ),
        ],
      ],
    );
  }
}

/// The promised time, or the statement that there was none.
///
/// The platform stores one instant, decided once at checkout and never
/// recomputed (V0023), so one instant is what is shown. Not a window, which
/// would be invented; not a countdown, which ADR 0045 refuses because a
/// per-minute ETA is a coarse position feed wearing a clock face.
class _PromiseLine extends StatelessWidget {
  const _PromiseLine({required this.order, required this.isLate});

  final OrderDetail order;
  final bool isLate;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final DateTime? promisedAt = order.promisedAt;

    if (promisedAt == null) {
      // "We never promised" and "on time" must not look the same, so the
      // absence is said out loud rather than left as a gap on the screen.
      return Text(
        l10n.orderPromisedNone,
        style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
      );
    }

    return Text(
      l10n.orderPromisedBy(
        HorecaOSFormats.time(
          HorecaOSFormats.toLocal(promisedAt),
          locale: Localizations.localeOf(context).toLanguageTag(),
        ),
      ),
      style: text.headlineSmall?.copyWith(
        color: isLate ? tokens.warningInk : tokens.ink,
      ),
    );
  }
}
