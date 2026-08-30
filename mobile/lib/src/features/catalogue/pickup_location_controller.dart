import 'package:flutter/foundation.dart';

import '../../api/api_exception.dart';
import 'data/pickup_location.dart';
import 'data/pickup_location_repository.dart';

/// The two recoverable ways public branch discovery can fail.
enum PickupLocationsFailureKind { offline, unavailable }

sealed class PickupLocationsState {
  const PickupLocationsState();
}

final class PickupLocationsLoading extends PickupLocationsState {
  const PickupLocationsLoading();
}

final class PickupLocationsReady extends PickupLocationsState {
  const PickupLocationsReady(this.locations);

  final List<PickupLocation> locations;
}

final class PickupLocationsFailed extends PickupLocationsState {
  const PickupLocationsFailed(this.kind, {this.correlationId});

  final PickupLocationsFailureKind kind;

  /// Kept for telemetry and support, never rendered to the customer.
  final String? correlationId;
}

/// Loads the configured pickup search point and protects the widget lifecycle.
final class PickupLocationsController extends ChangeNotifier {
  PickupLocationsController({
    required PickupLocationRepository repository,
    required PickupSearchPoint point,
  }) : // Named parameters cannot initialise private fields directly.
       // ignore: prefer_initializing_formals
       _repository = repository,
       // ignore: prefer_initializing_formals
       _point = point;

  final PickupLocationRepository _repository;
  final PickupSearchPoint _point;

  PickupLocationsState _state = const PickupLocationsLoading();
  PickupLocationsState get state => _state;

  bool _disposed = false;
  int _generation = 0;

  Future<void> load() async {
    final int generation = ++_generation;
    _publish(const PickupLocationsLoading(), generation);

    try {
      _publish(
        PickupLocationsReady(await _repository.nearby(point: _point)),
        generation,
      );
    } on ApiTransportException catch (failure) {
      _publish(
        PickupLocationsFailed(
          PickupLocationsFailureKind.offline,
          correlationId: failure.correlationId,
        ),
        generation,
      );
    } on ApiException catch (failure) {
      _publish(
        PickupLocationsFailed(
          PickupLocationsFailureKind.unavailable,
          correlationId: failure.problem.correlationId,
        ),
        generation,
      );
    } on FormatException {
      _publish(
        const PickupLocationsFailed(PickupLocationsFailureKind.unavailable),
        generation,
      );
    }
  }

  void _publish(PickupLocationsState next, int generation) {
    if (_disposed || generation != _generation) return;
    _state = next;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
