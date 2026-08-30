import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../../api/api_exception.dart';
import '../data/order_models.dart';
import '../data/orders_repository.dart';

/// One order, kept current while it is still moving.
///
/// **It polls, and it is not a stream.** ADR 0045 settled the cadence for the
/// customer's view of an order at twenty seconds and said why: the content
/// changes perhaps five times across forty minutes, which does not need a
/// socket, and an unbounded number of held-open customer connections is
/// precisely what this platform's one machine should not be carrying. The same
/// reasoning applies here, so the same number is used.
///
/// Polling stops the moment the order reaches a terminal status. Nothing about
/// a finished order changes again, and a phone that keeps asking is a battery
/// bill for an answer that is already known.
final class OrderTrackingController extends ChangeNotifier {
  OrderTrackingController({
    required this.repository,
    required this.orderId,
    this.pollInterval = defaultPollInterval,
    DateTime Function()? clock,
  }) : _clock = clock ?? DateTime.now;

  /// ADR 0045: the customer's tracking view polls at twenty seconds.
  static const Duration defaultPollInterval = Duration(seconds: 20);

  /// How many ticks are skipped after consecutive failures, capped.
  ///
  /// A screen that keeps hammering a platform that is already failing is the
  /// client half of an outage. The cap keeps the worst case at a little over
  /// two and a half minutes, so an order that recovers is picked up again
  /// without the customer having to do anything.
  static const int maxBackoffTicks = 8;

  final OrdersRepository repository;
  final String orderId;
  final Duration pollInterval;
  final DateTime Function() _clock;

  Timer? _timer;
  bool _inFlight = false;
  int _consecutiveFailures = 0;
  int _ticksToSkip = 0;
  bool _disposed = false;

  OrderDetail? _order;
  ApiFailure? _failure;
  DateTime? _updatedAt;

  OrderDetail? get order => _order;

  /// The last failure. Kept beside [order] rather than replacing it: a poll
  /// that fails does not make what the customer is already reading untrue.
  ApiFailure? get failure => _failure;

  /// When the last successful read landed, for the "updated at" caption.
  ///
  /// A live screen that silently shows stale content is worse than one that
  /// says how old it is.
  DateTime? get updatedAt => _updatedAt;

  bool get isLoadingFirstRead => _order == null && _failure == null;

  /// Whether this order is still moving, and therefore still worth polling.
  bool get isLive {
    final OrderDetail? current = _order;
    return current == null || !current.status.isTerminal;
  }

  /// Reads once, then keeps reading while the order is live.
  Future<void> start() async {
    await _read();
    _schedule();
  }

  /// Stops the timer without discarding what has been read.
  ///
  /// Called when the screen leaves the foreground: a polling loop that survives
  /// the application being backgrounded is a battery drain with no reader.
  void pause() {
    _timer?.cancel();
    _timer = null;
  }

  /// Resumes after [pause], reading once immediately so the customer does not
  /// look at a screen that is up to twenty seconds stale.
  Future<void> resume() async {
    if (!isLive) return;
    await _read();
    _schedule();
  }

  /// An explicit retry, from the error state's own action.
  Future<void> retry() async {
    _ticksToSkip = 0;
    _consecutiveFailures = 0;
    await _read();
    _schedule();
  }

  void _schedule() {
    _timer?.cancel();
    _timer = null;
    if (_disposed || !isLive) return;
    _timer = Timer.periodic(pollInterval, (Timer _) => _tick());
  }

  Future<void> _tick() async {
    if (_ticksToSkip > 0) {
      _ticksToSkip--;
      return;
    }
    await _read();
    if (!isLive) {
      pause();
    }
  }

  Future<void> _read() async {
    if (_inFlight || _disposed) return;
    _inFlight = true;
    try {
      final OrderDetail detail = await repository.read(orderId);
      if (_disposed) return;
      _order = detail;
      _failure = null;
      _updatedAt = _clock();
      _consecutiveFailures = 0;
      _ticksToSkip = 0;
      notifyListeners();
    } on ApiFailure catch (failure) {
      if (_disposed) return;
      _failure = failure;
      _consecutiveFailures++;
      // 1, 2, 4, 8 ticks, then held at 8.
      _ticksToSkip = _consecutiveFailures >= 4
          ? maxBackoffTicks
          : 1 << (_consecutiveFailures - 1);
      notifyListeners();
    } finally {
      _inFlight = false;
    }
  }

  @override
  void dispose() {
    _disposed = true;
    pause();
    super.dispose();
  }
}
