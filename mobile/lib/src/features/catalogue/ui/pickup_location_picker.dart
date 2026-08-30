import 'package:flutter/material.dart';

import '../../../design/q_empty_state.dart';
import '../../../design/q_icon.dart';
import '../../../design/horecaos_theme.dart';
import '../../../design/horecaos_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../data/pickup_location.dart';
import '../pickup_location_controller.dart';
import 'catalogue_pressable.dart';

/// The public first screen of the storefront: choose a branch, then browse.
class PickupLocationPicker extends StatefulWidget {
  const PickupLocationPicker({
    required this.controller,
    required this.onSelect,
    super.key,
  });

  final PickupLocationsController controller;
  final ValueChanged<PickupLocation> onSelect;

  @override
  State<PickupLocationPicker> createState() => _PickupLocationPickerState();
}

class _PickupLocationPickerState extends State<PickupLocationPicker> {
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onControllerChanged);
  }

  @override
  void didUpdateWidget(PickupLocationPicker oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.controller, widget.controller)) {
      oldWidget.controller.removeListener(_onControllerChanged);
      widget.controller.addListener(_onControllerChanged);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    super.dispose();
  }

  void _onControllerChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.pickupLocationsTitle)),
      body: switch (widget.controller.state) {
        PickupLocationsLoading() => const Center(
          child: CircularProgressIndicator(),
        ),
        PickupLocationsFailed(:final PickupLocationsFailureKind kind) =>
          _Failure(kind: kind, onRetry: widget.controller.load),
        PickupLocationsReady(:final List<PickupLocation> locations) =>
          _Locations(locations: locations, onSelect: widget.onSelect),
      },
    );
  }
}

class _Locations extends StatelessWidget {
  const _Locations({required this.locations, required this.onSelect});

  final List<PickupLocation> locations;
  final ValueChanged<PickupLocation> onSelect;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final HorecaOSTokens tokens = context.horecaos;

    if (locations.isEmpty) {
      return QEmptyState(
        title: l10n.pickupLocationsEmptyTitle,
        body: l10n.pickupLocationsEmptyBody,
      );
    }

    return ListView.separated(
      itemCount: locations.length + 1,
      separatorBuilder: (BuildContext context, int _) =>
          Divider(height: HorecaOSGeometry.hairline, color: tokens.hairline),
      itemBuilder: (BuildContext context, int index) {
        if (index == 0) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(
              HorecaOSGeometry.spaceMd,
              HorecaOSGeometry.spaceLg,
              HorecaOSGeometry.spaceMd,
              HorecaOSGeometry.spaceSm,
            ),
            child: Text(
              l10n.pickupLocationsNearby,
              style: Theme.of(context).textTheme.titleMedium,
            ),
          );
        }
        return _LocationRow(location: locations[index - 1], onSelect: onSelect);
      },
    );
  }
}

class _LocationRow extends StatelessWidget {
  const _LocationRow({required this.location, required this.onSelect});

  final PickupLocation location;
  final ValueChanged<PickupLocation> onSelect;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final String name = location.brandName == location.locationName
        ? location.brandName
        : '${location.brandName} — ${location.locationName}';
    final List<String> address = <String>[
      ?location.addressLine,
      ?location.district,
      ?location.city,
    ];

    return CataloguePressable(
      semanticsLabel: name,
      onTap: () => onSelect(location),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(name, style: text.bodyLarge),
                if (address.isNotEmpty) ...<Widget>[
                  const SizedBox(height: HorecaOSGeometry.spaceXs),
                  Text(
                    address.join(', '),
                    style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
                  ),
                ],
                const SizedBox(height: HorecaOSGeometry.spaceXs),
                Text(
                  l10n.pickupLocationsDistance(location.distanceMeters),
                  style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
                ),
                const SizedBox(height: HorecaOSGeometry.spaceXs),
                Text(
                  location.available
                      ? l10n.pickupLocationAvailable
                      : l10n.pickupLocationUnavailable,
                  style: text.labelMedium?.copyWith(
                    color: location.available
                        ? tokens.success
                        : tokens.inkMuted,
                  ),
                ),
              ],
            ),
          ),
          QIcon(QIconName.chevronRight, color: tokens.inkSubtle),
        ],
      ),
    );
  }
}

class _Failure extends StatelessWidget {
  const _Failure({required this.kind, required this.onRetry});

  final PickupLocationsFailureKind kind;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final (String title, String body) = switch (kind) {
      PickupLocationsFailureKind.offline => (
        l10n.pickupLocationsOfflineTitle,
        l10n.pickupLocationsOfflineBody,
      ),
      PickupLocationsFailureKind.unavailable => (
        l10n.pickupLocationsUnavailableTitle,
        l10n.pickupLocationsUnavailableBody,
      ),
    };

    return QEmptyState(
      title: title,
      body: body,
      actionLabel: l10n.retry,
      onAction: onRetry,
    );
  }
}
