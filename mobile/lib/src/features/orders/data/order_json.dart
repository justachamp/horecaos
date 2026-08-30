import '../../../format/money.dart';
import '../../../format/qoida_formats.dart';

/// Decoding helpers shared by the order payloads.
///
/// **On money.** ADR 0031 says a money value is always the object
/// `{amountMinor, currency}`. `StorefrontOrderingController` does not send one:
/// it sends a single `currency` beside flat `subtotalMinor`, `taxMinor` and
/// `totalMinor` members, and `OperationsOrderController` does the same. The
/// code is what runs, so that is what is decoded here — into a [Money], at the
/// seam, so that exactly one shape of money exists above this file and
/// `MoneyFormat` is the only thing that renders it. The divergence from ADR
/// 0031 is the platform's to close; a client that guessed the object form would
/// simply decode nothing.
abstract final class OrderJson {
  static String requireString(Map<String, Object?> json, String key) {
    final Object? value = json[key];
    if (value is! String) {
      throw FormatException('$key missing or not a string in $json');
    }
    return value;
  }

  static String? optionalString(Map<String, Object?> json, String key) {
    final Object? value = json[key];
    return value is String ? value : null;
  }

  static int requireInt(Map<String, Object?> json, String key) {
    final int? value = optionalInt(json, key);
    if (value == null) {
      throw FormatException('$key missing or not a number in $json');
    }
    return value;
  }

  static int? optionalInt(Map<String, Object?> json, String key) {
    final Object? value = json[key];
    if (value is int) return value;
    if (value is num) return value.toInt();
    return null;
  }

  /// An RFC 3339 instant, returned in UTC.
  ///
  /// Strict on a malformed value rather than falling back to "now": an order
  /// whose placed-at time silently became the current instant is a receipt that
  /// lies, and `QoidaFormats.parseInstant` is deliberately strict for the same
  /// reason.
  static DateTime? optionalInstant(Map<String, Object?> json, String key) {
    final String? raw = optionalString(json, key);
    return raw == null ? null : QoidaFormats.parseInstant(raw);
  }

  static DateTime requireInstant(Map<String, Object?> json, String key) {
    final DateTime? value = optionalInstant(json, key);
    if (value == null) {
      throw FormatException('$key missing or not an instant in $json');
    }
    return value;
  }

  /// `{"<field>Minor": 84000}` plus the payload's single `currency`, as [Money].
  static Money requireMoney(
    Map<String, Object?> json,
    String field,
    String currency,
  ) => Money(requireInt(json, '${field}Minor'), currency);

  /// The same, for an amount the payload may legitimately omit.
  ///
  /// Returns null rather than zero. A discount that is absent and a discount of
  /// nothing are different facts, and rendering a `0` row for the first one
  /// invents a line the order never had.
  static Money? optionalMoney(
    Map<String, Object?> json,
    String field,
    String currency,
  ) {
    final int? minor = optionalInt(json, '${field}Minor');
    return minor == null ? null : Money(minor, currency);
  }

  static List<Map<String, Object?>> objectList(
    Map<String, Object?> json,
    String key,
  ) {
    final Object? value = json[key];
    if (value is! List) return const <Map<String, Object?>>[];
    return value.whereType<Map<String, Object?>>().toList(growable: false);
  }

  static List<String> stringList(Map<String, Object?> json, String key) {
    final Object? value = json[key];
    if (value is! List) return const <String>[];
    return value.whereType<String>().toList(growable: false);
  }
}
