import 'dart:async';

import 'package:flutter/material.dart';

import '../../api/api_client.dart';
import 'catalogue_home.dart';
import 'data/pickup_location.dart';
import 'data/pickup_location_repository.dart';
import 'pickup_location_controller.dart';
import 'ui/pickup_location_picker.dart';

/// Mounts the public storefront journey inside the menu tab.
///
/// Selecting a location pushes the existing catalogue above the app shell.
/// Popping returns to this already-loaded picker, so changing branches is a
/// normal back gesture rather than a restart of the application.
class StorefrontHome extends StatefulWidget {
  const StorefrontHome({
    required this.api,
    required this.initialPickupPoint,
    super.key,
  });

  final QoidaApiClient api;
  final PickupSearchPoint initialPickupPoint;

  @override
  State<StorefrontHome> createState() => _StorefrontHomeState();
}

class _StorefrontHomeState extends State<StorefrontHome> {
  late final PickupLocationsController _controller = PickupLocationsController(
    repository: PickupLocationRepository(api: widget.api),
    point: widget.initialPickupPoint,
  );

  @override
  void initState() {
    super.initState();
    unawaited(_controller.load());
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _openCatalogue(PickupLocation location) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (BuildContext context) =>
            CatalogueHome(scope: location.catalogueScope, api: widget.api),
      ),
    );
  }

  @override
  Widget build(BuildContext context) =>
      PickupLocationPicker(controller: _controller, onSelect: _openCatalogue);
}
