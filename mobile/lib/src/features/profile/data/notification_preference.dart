/// Why a message is being sent, which decides what has to be true before it may
/// be (ADR 0020).
///
/// The two flags below are the whole reason this enum is mirrored in the client
/// rather than treated as an opaque string. A preference screen has to know
/// which messages a customer may switch off *before* it draws a switch, because
/// drawing one for a required message and then letting the server refuse the
/// write is an interface that lied and then argued.
enum NotificationClass {
  /// A confirmation, a refusal, a payment failure. Part of the transaction
  /// rather than an extra the customer opted into, and not switchable.
  transactionalRequired(
    'TRANSACTIONAL_REQUIRED',
    respectsPreference: false,
    addressedToCustomer: true,
  ),

  /// Useful but not owed — a reminder that an order is waiting.
  transactionalOptional(
    'TRANSACTIONAL_OPTIONAL',
    respectsPreference: true,
    addressedToCustomer: true,
  ),

  /// Offers and news. Requires an ADR 0015 consent decision as well as this
  /// preference; the two are not the same thing and this screen never conflates
  /// them.
  marketing('MARKETING', respectsPreference: true, addressedToCustomer: true),

  /// Account and credential events. Suppressing one is itself a security risk,
  /// so it is not switchable.
  security('SECURITY', respectsPreference: false, addressedToCustomer: true),

  /// Aimed at an on-call route or a shared operations channel. There is no data
  /// subject in the ADR 0015 sense, and it never appears on a customer's
  /// screen.
  operationsAlert(
    'OPERATIONS_ALERT',
    respectsPreference: false,
    addressedToCustomer: false,
  ),

  /// A class this build does not know.
  ///
  /// Never rendered, and in particular never rendered as a switch: this client
  /// cannot tell whether an unknown class is one a customer may refuse, and
  /// guessing "yes" is how a required message becomes suppressible in an
  /// interface. ADR 0031 permits additive enum values, so this is the
  /// documented tolerance rather than a defect.
  unknown('', respectsPreference: false, addressedToCustomer: false);

  const NotificationClass(
    this.wire, {
    required this.respectsPreference,
    required this.addressedToCustomer,
  });

  final String wire;

  /// Whether a customer's own preference can stop this.
  final bool respectsPreference;

  /// Whether a customer is ever the recipient.
  final bool addressedToCustomer;

  /// Shown on the preference screen as something the customer controls.
  bool get isSwitchable => respectsPreference && addressedToCustomer;

  /// Shown on the preference screen as something they will always receive.
  ///
  /// Listed rather than hidden. A customer who cannot find out that order
  /// confirmations exist has not been told what the application will send them.
  bool get isAlwaysSent =>
      !respectsPreference && addressedToCustomer && this != unknown;

  static NotificationClass fromWire(String? value) {
    for (final NotificationClass candidate in NotificationClass.values) {
      if (candidate != NotificationClass.unknown && candidate.wire == value) {
        return candidate;
      }
    }
    return NotificationClass.unknown;
  }
}

/// How a message physically reaches someone (ADR 0020).
enum NotificationChannel {
  /// The one channel with an adapter behind it in this release.
  sms('SMS', isWired: true),

  email('EMAIL', isWired: false),
  push('PUSH', isWired: false),
  messagingApp('MESSAGING_APP', isWired: false),

  /// A channel this build does not know. Never offered.
  unknown('', isWired: false);

  const NotificationChannel(this.wire, {required this.isWired});

  final String wire;

  /// Whether the platform can actually send on this channel today.
  ///
  /// An unwired channel is not offered. A switch for email, when nothing sends
  /// email, is a control whose position means nothing — and the customer who
  /// turns it on and receives no email concludes the application is broken
  /// rather than that the channel does not exist.
  final bool isWired;

  static NotificationChannel fromWire(String? value) {
    for (final NotificationChannel candidate in NotificationChannel.values) {
      if (candidate != NotificationChannel.unknown && candidate.wire == value) {
        return candidate;
      }
    }
    return NotificationChannel.unknown;
  }
}

/// One stored preference row.
///
/// **Absence is not an opt-out.** ADR 0020's default is on: a customer who never
/// expressed a preference has not refused anything. So the screen renders a
/// missing row as enabled, and [NotificationPreferences.isEnabled] is where that
/// rule lives rather than in a widget.
final class NotificationPreference {
  const NotificationPreference({
    required this.notificationClass,
    required this.channel,
    required this.enabled,
    this.brandId,
    this.version = 0,
  });

  /// Null for the customer's tenant-wide answer, set when they overrode it for
  /// one brand.
  final String? brandId;

  final NotificationClass notificationClass;
  final NotificationChannel channel;
  final bool enabled;

  /// The row's version. Carried because the platform sends it; nothing uses it,
  /// because the write endpoint takes no `If-Match` and there is no version to
  /// send back.
  final int version;

  static NotificationPreference fromJson(Map<String, Object?> json) =>
      NotificationPreference(
        brandId: json['brandId'] as String?,
        notificationClass: NotificationClass.fromWire(
          json['notificationClass'] as String?,
        ),
        channel: NotificationChannel.fromWire(json['channel'] as String?),
        enabled: json['enabled'] as bool? ?? true,
        version: switch (json['version']) {
          final int value => value,
          final num value => value.toInt(),
          _ => 0,
        },
      );

  @override
  String toString() =>
      'NotificationPreference(${notificationClass.name}/${channel.name}: '
      '${enabled ? 'on' : 'off'})';
}

/// The set of rows the platform holds, and the defaulting rule around them.
final class NotificationPreferences {
  const NotificationPreferences(this._rows);

  const NotificationPreferences.empty() : _rows = const <NotificationPreference>[];

  final List<NotificationPreference> _rows;

  List<NotificationPreference> get rows => List<NotificationPreference>.unmodifiable(_rows);

  /// Whether a class is enabled on a channel.
  ///
  /// A brand-specific row wins over the tenant-wide one, matching the platform's
  /// own resolution order. No row at all means enabled, per ADR 0020.
  bool isEnabled(
    NotificationClass notificationClass,
    NotificationChannel channel, {
    String? brandId,
  }) {
    NotificationPreference? tenantWide;
    for (final NotificationPreference row in _rows) {
      if (row.notificationClass != notificationClass || row.channel != channel) {
        continue;
      }
      if (brandId != null && row.brandId == brandId) {
        return row.enabled;
      }
      if (row.brandId == null) {
        tenantWide = row;
      }
    }
    return tenantWide?.enabled ?? true;
  }

  /// The same set with one row replaced, for an optimistic update.
  NotificationPreferences withSetting({
    required NotificationClass notificationClass,
    required NotificationChannel channel,
    required bool enabled,
    String? brandId,
  }) {
    final List<NotificationPreference> next = <NotificationPreference>[
      for (final NotificationPreference row in _rows)
        if (!(row.notificationClass == notificationClass &&
            row.channel == channel &&
            row.brandId == brandId))
          row,
      NotificationPreference(
        brandId: brandId,
        notificationClass: notificationClass,
        channel: channel,
        enabled: enabled,
      ),
    ];
    return NotificationPreferences(next);
  }
}
