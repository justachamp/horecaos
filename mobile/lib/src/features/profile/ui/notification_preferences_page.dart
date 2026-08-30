import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show HapticFeedback;

import '../../../api/idempotency_key.dart';
import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../data/notification_preference.dart';
import '../data/notification_preference_repository.dart';
import 'profile_failure_view.dart';
import 'profile_scope.dart';
import 'profile_widgets.dart';

/// Which messages the customer receives (ADR 0020).
///
/// **What this screen offers.** One switch per class the customer may actually
/// refuse — `TRANSACTIONAL_OPTIONAL` and `MARKETING` — on each channel that has
/// an adapter behind it, which today is SMS alone.
///
/// **What it does not offer, and why each is deliberate.**
///
/// - *No switch for a required transactional message.* Order confirmations,
///   refusals and payment failures are part of the transaction, and `SECURITY`
///   messages are account events whose suppression is itself a risk. The
///   platform refuses to write a preference for them. An interface that let
///   somebody switch off their order confirmations and then sent them anyway
///   would be worse than one that says no, so these are **listed** — the
///   customer can see what will be sent — and listed without a control.
/// - *No switch for a channel nothing sends on.* Email, push and messaging apps
///   are declared in the platform's enum with `isWired` false. A switch whose
///   position changes nothing is a control that lies, and the customer who turns
///   email on and receives none concludes the application is broken.
/// - *No `OPERATIONS_ALERT` row.* It is addressed to an on-call route, and there
///   is no data subject in the ADR 0015 sense. It is not the customer's message
///   and does not appear on the customer's screen.
/// - *No consent toggle.* Consent is the customer's and it is an ADR 0015
///   decision recorded append-only against a policy version, with its date and
///   its evidence. A preference is not one and must never stand in for one: a
///   legal basis created by a checkbox nobody can date is not a legal basis.
///   Writing one would also require this client to supply the policy version,
///   which is a legal artefact that legal has not issued. The marketing row
///   therefore says plainly that switching it on is a preference, and that
///   offers are only sent where consent also exists.
class NotificationPreferencesPage extends StatefulWidget {
  const NotificationPreferencesPage({super.key});

  @override
  State<NotificationPreferencesPage> createState() =>
      _NotificationPreferencesPageState();
}

class _NotificationPreferencesPageState
    extends State<NotificationPreferencesPage> {
  Future<_PreferenceState>? _loaded;

  /// The rows the customer may switch, in the order they are shown.
  static const List<NotificationClass> _switchable = <NotificationClass>[
    NotificationClass.transactionalOptional,
    NotificationClass.marketing,
  ];

  /// The rows that are always sent, listed so the customer knows they exist.
  static const List<NotificationClass> _alwaysSent = <NotificationClass>[
    NotificationClass.transactionalRequired,
    NotificationClass.security,
  ];

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _loaded ??= _load();
  }

  Future<_PreferenceState> _load() async {
    final NotificationPreferenceRepository repository = await ProfileScope.of(
      context,
    ).notifications();
    return _PreferenceState(
      repository: repository,
      preferences: await repository.list(),
    );
  }

  void _reload() {
    setState(() {
      _loaded = _load();
    });
  }

  Future<void> _toggle(
    _PreferenceState state,
    NotificationClass notificationClass,
    NotificationChannel channel,
    bool enabled,
  ) async {
    // A switch that does not move until the network answers reads as a broken
    // switch. It moves now and is put back if the write fails, which is the
    // only honest form of an optimistic update.
    final NotificationPreferences before = state.preferences;
    setState(() {
      state.preferences = before.withSetting(
        notificationClass: notificationClass,
        channel: channel,
        enabled: enabled,
      );
    });
    unawaited(HapticFeedback.selectionClick());

    final ScaffoldMessengerState messenger = ScaffoldMessenger.of(context);
    final AppLocalizations l10n = AppLocalizations.of(context);
    try {
      await state.repository.set(
        notificationClass: notificationClass,
        channel: channel,
        enabled: enabled,
        // One key for one intent: this flip of this switch.
        idempotencyKey: IdempotencyKey.generate(),
      );
    } on Object {
      if (!mounted) {
        return;
      }
      setState(() => state.preferences = before);
      messenger.showSnackBar(
        SnackBar(content: Text(l10n.profileNotificationsSaveFailed)),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.profileNotifications)),
      body: SafeArea(
        child: FutureBuilder<_PreferenceState>(
          future: _loaded,
          builder:
              (BuildContext context, AsyncSnapshot<_PreferenceState> snapshot) {
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
                final _PreferenceState? state = snapshot.data;
                if (state == null) {
                  return const SizedBox.shrink();
                }
                return _PreferenceList(
                  state: state,
                  switchable: _switchable,
                  alwaysSent: _alwaysSent,
                  onToggle: _toggle,
                );
              },
        ),
      ),
    );
  }
}

/// The loaded preferences and where to write them back.
final class _PreferenceState {
  _PreferenceState({required this.repository, required this.preferences});

  final NotificationPreferenceRepository repository;
  NotificationPreferences preferences;
}

class _PreferenceList extends StatelessWidget {
  const _PreferenceList({
    required this.state,
    required this.switchable,
    required this.alwaysSent,
    required this.onToggle,
  });

  final _PreferenceState state;
  final List<NotificationClass> switchable;
  final List<NotificationClass> alwaysSent;
  final Future<void> Function(
    _PreferenceState state,
    NotificationClass notificationClass,
    NotificationChannel channel,
    bool enabled,
  )
  onToggle;

  /// The channels a preference may be expressed for.
  ///
  /// Filtered on `isWired` rather than listed by hand, so a channel that gains
  /// an adapter appears here by changing one flag and a channel that loses one
  /// disappears the same way.
  static List<NotificationChannel> get _offeredChannels =>
      NotificationChannel.values
          .where((NotificationChannel channel) => channel.isWired)
          .toList(growable: false);

  @override
  Widget build(BuildContext context) {
    // The invariant this screen exists to keep, stated where it can fail. A
    // required transactional message must never reach the switchable list, and
    // an operations alert must never reach either.
    assert(
      switchable.every((NotificationClass value) => value.isSwitchable),
      'A class a customer cannot switch off was given a switch',
    );
    assert(
      alwaysSent.every((NotificationClass value) => value.isAlwaysSent),
      'A class that is not always sent was listed as always sent',
    );

    final AppLocalizations l10n = AppLocalizations.of(context);
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;

    return ListView(
      padding: const EdgeInsets.only(bottom: QoidaGeometry.spaceXl),
      children: <Widget>[
        ProfileSectionHeader(l10n.profileNotificationsAlwaysTitle),
        const ProfileDivider(),
        for (final NotificationClass sent in alwaysSent) ...<Widget>[
          ProfileRow(
            title: _title(sent, l10n),
            detail: _body(sent, l10n),
          ),
          const ProfileDivider(),
        ],
        Padding(
          padding: const EdgeInsets.fromLTRB(
            QoidaGeometry.spaceMd,
            QoidaGeometry.spaceSm,
            QoidaGeometry.spaceMd,
            0,
          ),
          child: Text(
            l10n.profileNotificationsAlwaysBody,
            style: text.bodySmall,
          ),
        ),

        ProfileSectionHeader(l10n.profileNotificationsChoiceTitle),
        const ProfileDivider(),
        for (final NotificationClass choice in switchable)
          for (final NotificationChannel channel in _offeredChannels) ...<Widget>[
            ProfileRow(
              title: _title(choice, l10n),
              detail: _body(choice, l10n),
              trailing: Switch(
                value: state.preferences.isEnabled(choice, channel),
                onChanged: (bool enabled) {
                  unawaited(onToggle(state, choice, channel, enabled));
                },
              ),
            ),
            const ProfileDivider(),
          ],
        Padding(
          padding: const EdgeInsets.fromLTRB(
            QoidaGeometry.spaceMd,
            QoidaGeometry.spaceSm,
            QoidaGeometry.spaceMd,
            0,
          ),
          child: Text(
            l10n.profileNotificationsSmsOnly,
            style: text.bodySmall?.copyWith(color: tokens.inkMuted),
          ),
        ),
      ],
    );
  }

  static String _title(NotificationClass value, AppLocalizations l10n) =>
      switch (value) {
        NotificationClass.transactionalRequired =>
          l10n.profileNotificationsOrderTitle,
        NotificationClass.security => l10n.profileNotificationsSecurityTitle,
        NotificationClass.transactionalOptional =>
          l10n.profileNotificationsExtraTitle,
        NotificationClass.marketing => l10n.profileNotificationsOffersTitle,
        // Neither of these is ever placed in a list above, and the switch is
        // exhaustive so that adding a class to the enum is a compile error
        // here rather than an unlabelled row in production.
        NotificationClass.operationsAlert ||
        NotificationClass.unknown => '',
      };

  static String _body(NotificationClass value, AppLocalizations l10n) =>
      switch (value) {
        NotificationClass.transactionalRequired =>
          l10n.profileNotificationsOrderBody,
        NotificationClass.security => l10n.profileNotificationsSecurityBody,
        NotificationClass.transactionalOptional =>
          l10n.profileNotificationsExtraBody,
        NotificationClass.marketing => l10n.profileNotificationsOffersBody,
        NotificationClass.operationsAlert ||
        NotificationClass.unknown => '',
      };
}
