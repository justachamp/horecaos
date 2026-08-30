import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../api/idempotency_key.dart';
import '../../../design/q_empty_state.dart';
import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../data/saved_address.dart';
import '../data/saved_address_repository.dart';
import '../profile_routes.dart';
import 'profile_failure_view.dart';
import 'profile_scope.dart';
import 'profile_widgets.dart';

/// The customer's saved addresses.
///
/// Nothing on this screen is cached. The list is held in this widget's state
/// for as long as the screen is on top and is re-read when it comes back, which
/// is slower than a cache and is the point: an address must not be written to
/// the device (ADR 0029), and the simplest way to guarantee that is to have no
/// code that could.
class AddressesPage extends StatefulWidget {
  const AddressesPage({super.key});

  @override
  State<AddressesPage> createState() => _AddressesPageState();
}

class _AddressesPageState extends State<AddressesPage> {
  Future<_AddressList>? _addresses;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _addresses ??= _load();
  }

  Future<_AddressList> _load() async {
    final SavedAddressRepository repository = await ProfileScope.of(
      context,
    ).addresses();
    return _AddressList(
      repository: repository,
      addresses: await repository.list(),
    );
  }

  void _reload() {
    // A block body, not an arrow: an arrow closure returns the assignment's
    // value, and `setState` refuses a callback that returns a Future.
    setState(() {
      _addresses = _load();
    });
  }

  Future<void> _add() async {
    final Object? saved = await context.push<Object?>(
      ProfileRoutes.newAddress,
    );
    if (saved == true && mounted) {
      _reload();
    }
  }

  Future<void> _edit(SavedAddress address) async {
    final Object? saved = await context.push<Object?>(
      ProfileRoutes.editAddressPath(address.id),
      extra: address,
    );
    if (saved == true && mounted) {
      _reload();
    }
  }

  Future<void> _remove(_AddressList list, SavedAddress address) async {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);

    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: Text(l10n.profileAddressRemoveTitle),
        content: Text(l10n.profileAddressRemoveBody),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(l10n.profileCancel),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(l10n.profileAddressRemoveAction),
          ),
        ],
      ),
    );
    if (confirmed != true) {
      return;
    }

    try {
      await list.repository.remove(
        address.id,
        // One key for one intent: this customer removing this address. A
        // retry over a dropped connection removes it once.
        idempotencyKey: IdempotencyKey.generate(),
      );
      messenger.showSnackBar(
        SnackBar(content: Text(l10n.profileAddressRemoved)),
      );
    } on Object {
      messenger.showSnackBar(SnackBar(content: Text(l10n.profileErrorTitle)));
    }
    if (mounted) {
      _reload();
    }
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.profileAddresses)),
      body: SafeArea(
        child: FutureBuilder<_AddressList>(
          future: _addresses,
          builder:
              (BuildContext context, AsyncSnapshot<_AddressList> snapshot) {
                if (snapshot.connectionState == ConnectionState.waiting) {
                  return const Center(child: CircularProgressIndicator());
                }
                final Object? failure = snapshot.error;
                if (failure != null) {
                  return ProfileFailureView(
                    failure: failure,
                    onRetry: _reload,
                  );
                }
                final _AddressList? list = snapshot.data;
                if (list == null) {
                  return const SizedBox.shrink();
                }
                if (list.addresses.isEmpty) {
                  return QEmptyState(
                    title: l10n.profileAddressesEmptyTitle,
                    body: l10n.profileAddressesEmptyBody,
                    actionLabel: l10n.profileAddressAdd,
                    onAction: _add,
                  );
                }
                return _AddressListView(
                  list: list,
                  onAdd: _add,
                  onEdit: _edit,
                  onRemove: (SavedAddress address) => _remove(list, address),
                );
              },
        ),
      ),
    );
  }
}

/// The addresses and the repository they came from.
///
/// Carried together because whether editing and removing are offered is a
/// property of the repository, not of the address, and the list screen must not
/// assume one answer.
final class _AddressList {
  const _AddressList({required this.repository, required this.addresses});

  final SavedAddressRepository repository;
  final List<SavedAddress> addresses;
}

class _AddressListView extends StatelessWidget {
  const _AddressListView({
    required this.list,
    required this.onAdd,
    required this.onEdit,
    required this.onRemove,
  });

  final _AddressList list;
  final VoidCallback onAdd;
  final void Function(SavedAddress address) onEdit;
  final void Function(SavedAddress address) onRemove;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return ListView(
      padding: const EdgeInsets.only(bottom: QoidaGeometry.spaceXl),
      children: <Widget>[
        const ProfileDivider(),
        for (final SavedAddress address in list.addresses) ...<Widget>[
          _AddressTile(
            address: address,
            onEdit: list.repository.supportsReplace
                ? () => onEdit(address)
                : null,
            onRemove: list.repository.supportsRemove
                ? () => onRemove(address)
                : null,
          ),
          const ProfileDivider(),
        ],
        Padding(
          padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
          child: SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: onAdd,
              child: Text(l10n.profileAddressAdd),
            ),
          ),
        ),
      ],
    );
  }
}

class _AddressTile extends StatelessWidget {
  const _AddressTile({required this.address, this.onEdit, this.onRemove});

  final SavedAddress address;

  /// Null when the platform has no endpoint for it. The action is then absent
  /// rather than present and failing.
  final VoidCallback? onEdit;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;

    final String? street = _street(address.fields);
    final String? detail = _insideTheBuilding(address.fields, l10n);
    final String? landmark = address.fields.landmark?.trim();

    return Padding(
      padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            address.label?.trim().isNotEmpty ?? false
                ? address.label!.trim()
                : l10n.profileAddressUnlabelled,
            style: text.titleSmall,
          ),
          if (street != null) ...<Widget>[
            const SizedBox(height: QoidaGeometry.spaceXs),
            Text(street, style: text.bodyMedium),
          ],
          if (detail != null) ...<Widget>[
            const SizedBox(height: QoidaGeometry.spaceXs),
            Text(
              detail,
              style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
            ),
          ],
          if (landmark != null && landmark.isNotEmpty) ...<Widget>[
            const SizedBox(height: QoidaGeometry.spaceXs),
            Text(
              '${l10n.profileAddressFieldLandmark}: $landmark',
              style: text.bodyMedium,
            ),
          ],
          const SizedBox(height: QoidaGeometry.spaceXs),
          Text(_pinState(address, l10n), style: text.bodySmall),
          if (onEdit != null || onRemove != null) ...<Widget>[
            const SizedBox(height: QoidaGeometry.spaceSm),
            Row(
              children: <Widget>[
                if (onEdit != null)
                  TextButton(
                    onPressed: onEdit,
                    child: Text(l10n.profileAddressEdit),
                  ),
                if (onRemove != null)
                  TextButton(
                    onPressed: onRemove,
                    child: Text(l10n.profileAddressRemoveAction),
                  ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  /// The street and building, as one line.
  static String? _street(AddressFields fields) {
    final List<String> parts = <String>[
      for (final String? part in <String?>[
        fields.line1,
        fields.line2,
        fields.district,
        fields.city,
      ])
        if (part != null && part.trim().isNotEmpty) part.trim(),
    ];
    return parts.isEmpty ? null : parts.join(', ');
  }

  /// подъезд, этаж, квартира — the part that finds a door rather than a
  /// building, shown as its own line because that is how it is used.
  static String? _insideTheBuilding(
    AddressFields fields,
    AppLocalizations l10n,
  ) {
    final List<String> parts = <String>[
      if (_present(fields.entrance))
        '${l10n.profileAddressFieldEntrance} ${fields.entrance!.trim()}',
      if (_present(fields.floor))
        '${l10n.profileAddressFieldFloor} ${fields.floor!.trim()}',
      if (_present(fields.apartment))
        '${l10n.profileAddressFieldApartment} ${fields.apartment!.trim()}',
    ];
    return parts.isEmpty ? null : parts.join(' · ');
  }

  /// What the pin says about this address.
  ///
  /// Three answers, not two. "Found by its landmark" is a finished address and
  /// says so; "no pin yet" is an address somebody should come back to. Showing
  /// both as "no pin" would tell the customer their finished address is
  /// incomplete.
  static String _pinState(SavedAddress address, AppLocalizations l10n) {
    if (address.hasPoint) {
      return l10n.profileAddressPinSet;
    }
    return switch (address.coordinateSource) {
      CoordinateSource.landmarkOnly => l10n.profileAddressPinByLandmark,
      _ => l10n.profileAddressPinNone,
    };
  }

  static bool _present(String? value) =>
      value != null && value.trim().isNotEmpty;
}
