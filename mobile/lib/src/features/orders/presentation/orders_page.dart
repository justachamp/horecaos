import 'package:flutter/material.dart';

import '../../../api/api_exception.dart';
import '../../../design/q_empty_state.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../application/order_list_controller.dart';
import '../data/order_models.dart';
import '../data/orders_repository.dart';
import 'order_detail_page.dart';
import 'order_strings.dart';
import 'widgets/order_card.dart';

/// The customer's order history.
///
/// One chronological list, newest first, and no tabs. The archived iOS
/// application split orders into active, completed and cancelled, and that
/// split cannot be honestly rebuilt on a cursor: a cursor encodes the sort key
/// and the filter set it was issued for, so grouping rows client-side across
/// pages produces headings that are wrong as soon as a page boundary falls
/// inside a group. Tabs become correct the day the platform offers a status
/// filter on the list endpoint, and inventing that parameter here would be
/// inventing an API.
class OrdersPage extends StatefulWidget {
  const OrdersPage({
    required this.repository,
    super.key,
    this.onOpenOrder,
    this.now,
  });

  final OrdersRepository repository;

  /// How a row opens. Defaults to pushing the detail screen.
  ///
  /// A callback rather than a `go_router` call, because the router does not own
  /// an orders route yet and this feature should not be the thing that decides
  /// it does. Wiring it into the shell is a two-line change at the call site.
  final void Function(BuildContext context, OrderSummary order)? onOpenOrder;

  /// Injectable clock. Lateness is derived against it.
  final DateTime? now;

  @override
  State<OrdersPage> createState() => _OrdersPageState();
}

class _OrdersPageState extends State<OrdersPage> {
  late final OrderListController _controller = OrderListController(
    repository: widget.repository,
  );
  final ScrollController _scroll = ScrollController();

  /// How close to the end of the list a continuation starts, in pixels.
  ///
  /// Far enough that the next page is usually there before the customer
  /// arrives, near enough that a list nobody scrolls costs one request.
  static const double _continuationThreshold = 400;

  @override
  void initState() {
    super.initState();
    _scroll.addListener(_maybeContinue);
    _controller.load();
  }

  @override
  void dispose() {
    _scroll
      ..removeListener(_maybeContinue)
      ..dispose();
    _controller.dispose();
    super.dispose();
  }

  void _maybeContinue() {
    if (!_scroll.hasClients) return;
    final double remaining =
        _scroll.position.maxScrollExtent - _scroll.position.pixels;
    if (remaining < _continuationThreshold) {
      // The controller ignores this while a request is in flight, at the end of
      // the collection, or after a failure the customer has not retried.
      _controller.loadMore();
    }
  }

  void _open(OrderSummary order) {
    final void Function(BuildContext, OrderSummary)? handler =
        widget.onOpenOrder;
    if (handler != null) {
      handler(context, order);
      return;
    }
    Navigator.of(context).push<void>(
      MaterialPageRoute<void>(
        builder: (BuildContext context) => OrderDetailPage(
          repository: widget.repository,
          orderId: order.orderId,
          publicOrderNumber: order.publicOrderNumber,
          now: widget.now,
          // A status seen on the detail screen is the one the list shows on the
          // way back, without a second request for a page it already holds.
          onUpdated: _controller.replace,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.navOrders)),
      body: ListenableBuilder(
        listenable: _controller,
        builder: (BuildContext context, Widget? _) => _body(context, l10n),
      ),
    );
  }

  Widget _body(BuildContext context, AppLocalizations l10n) {
    if (_controller.isLoadingFirstPage) {
      return const Center(child: CircularProgressIndicator());
    }

    final ApiFailure? failure = _controller.failure;
    if (failure != null && _controller.orders.isEmpty) {
      final ({String title, String body}) copy = OrderStrings.failure(
        l10n,
        failure,
      );
      return QEmptyState(
        title: copy.title,
        body: copy.body,
        actionLabel: l10n.retry,
        onAction: _controller.load,
      );
    }

    if (_controller.isEmpty) {
      return QEmptyState(
        title: l10n.ordersEmptyTitle,
        body: l10n.ordersEmptyBody,
      );
    }

    final List<OrderSummary> orders = _controller.orders;

    return RefreshIndicator(
      onRefresh: _controller.refresh,
      child: ListView.separated(
        controller: _scroll,
        padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
        // One extra row for the footer: a continuation in flight, a retry after
        // one failed, or nothing at the end of the collection.
        itemCount: orders.length + 1,
        separatorBuilder: (BuildContext context, int index) =>
            const SizedBox(height: QoidaGeometry.spaceSm),
        itemBuilder: (BuildContext context, int index) {
          if (index == orders.length) return _footer(l10n);
          final OrderSummary order = orders[index];
          return OrderCard(
            order: order,
            now: widget.now,
            onOpen: () => _open(order),
          );
        },
      ),
    );
  }

  Widget _footer(AppLocalizations l10n) {
    if (_controller.isLoadingMore) {
      return const Padding(
        padding: EdgeInsets.all(QoidaGeometry.spaceMd),
        child: Center(child: CircularProgressIndicator()),
      );
    }
    final ApiFailure? failure = _controller.failure;
    if (failure != null) {
      // The rows already on screen stay: throwing away what the customer is
      // reading is a worse answer to a dropped connection than leaving it and
      // offering the continuation again.
      return Padding(
        padding: const EdgeInsets.all(QoidaGeometry.spaceSm),
        child: Center(
          child: TextButton(
            onPressed: _controller.loadMore,
            child: Text(l10n.ordersLoadMore),
          ),
        ),
      );
    }
    return const SizedBox(height: QoidaGeometry.spaceMd);
  }
}
