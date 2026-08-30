import 'package:flutter/foundation.dart';

import '../../../api/api_exception.dart';
import '../../../api/page.dart';
import '../data/order_models.dart';
import '../data/orders_repository.dart';

/// The order history, one cursor page at a time (ADR 0031).
///
/// A `ChangeNotifier` because that is what this application already has:
/// nothing here depends on a state-management package, and adding one for two
/// screens would be a dependency the repository does not carry.
///
/// **There is no page number and no total, and there is not going to be one.**
/// Offset pagination silently skips and duplicates rows while a list is being
/// paged, which in an order feed means a customer whose order is simply not
/// there. The screen is built around continuation: a cursor, or the end.
final class OrderListController extends ChangeNotifier {
  OrderListController({required this.repository});

  final OrdersRepository repository;

  final List<OrderSummary> _orders = <OrderSummary>[];
  String? _cursor;
  bool _loadedFirstPage = false;
  bool _loading = false;
  bool _loadingMore = false;
  ApiFailure? _failure;

  List<OrderSummary> get orders => List<OrderSummary>.unmodifiable(_orders);

  /// The first page is on its way and there is nothing to show yet.
  bool get isLoadingFirstPage => _loading && !_loadedFirstPage;

  /// A continuation is on its way, under a list that is already on screen.
  bool get isLoadingMore => _loadingMore;

  bool get hasMore => _cursor != null;

  /// The last failure, kept rather than thrown.
  ///
  /// A failure loading the first page is a full-screen state; a failure loading
  /// a continuation is a footer with a retry, because throwing away rows the
  /// customer is already reading would be a worse answer to a dropped
  /// connection than leaving them there.
  ApiFailure? get failure => _failure;

  bool get isEmpty => _loadedFirstPage && _orders.isEmpty && _failure == null;

  /// Loads the first page, or reloads it after a failure.
  Future<void> load() async {
    if (_loading) return;
    _loading = true;
    _failure = null;
    notifyListeners();

    try {
      final Page<OrderSummary> page = await repository.list();
      _orders
        ..clear()
        ..addAll(page.items);
      _cursor = page.nextCursor;
      _loadedFirstPage = true;
    } on ApiFailure catch (failure) {
      _failure = failure;
    } finally {
      _loading = false;
      notifyListeners();
    }
  }

  /// Pull-to-refresh, and what a return from the detail screen does.
  ///
  /// Restarts the iteration from the beginning rather than merging: a cursor
  /// encodes the sort key and the filter set it was issued for, and stitching a
  /// fresh head onto an old tail produces a list that is coherent nowhere.
  Future<void> refresh() {
    _cursor = null;
    return load();
  }

  /// The next page, if there is one.
  Future<void> loadMore() async {
    final String? cursor = _cursor;
    if (cursor == null || _loading || _loadingMore) return;

    _loadingMore = true;
    _failure = null;
    notifyListeners();

    try {
      final Page<OrderSummary> page = await repository.list(cursor: cursor);
      _orders.addAll(page.items);
      _cursor = page.nextCursor;
    } on ApiFailure catch (failure) {
      // The cursor is kept, so the retry continues from where the iteration
      // stopped instead of starting the list again underneath the customer.
      _failure = failure;
    } finally {
      _loadingMore = false;
      notifyListeners();
    }
  }

  /// Replaces one row from a detail read, so a status seen on the detail screen
  /// is the one the list shows on the way back.
  void replace(OrderSummary order) {
    final int index = _orders.indexWhere(
      (OrderSummary existing) => existing.orderId == order.orderId,
    );
    if (index == -1) return;
    _orders[index] = order;
    notifyListeners();
  }
}
