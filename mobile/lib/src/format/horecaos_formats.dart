import 'package:intl/intl.dart';

/// Dates and times, in the one form the design system specifies.
///
/// ADR 0035 fixes a 24h clock and `DD.MM` for ru and uz. This applies the same
/// pattern to en as well, which the ADR does not state either way: the
/// application is used in Uzbekistan by customers who switch locale for the
/// language and not for the date convention, and an order list that renders
/// `22.08` in two locales and `08/22` in the third is a list that looks broken
/// in the third. Recorded here as a decision rather than an inference.
abstract final class HorecaOSFormats {
  /// `22.08`.
  static String dayMonth(DateTime local, {String? locale}) =>
      DateFormat('dd.MM', locale).format(local);

  /// `22.08.2026`. For anything older than the current year.
  static String date(DateTime local, {String? locale}) =>
      DateFormat('dd.MM.yyyy', locale).format(local);

  /// `19:05`. Never a 12h clock, in any locale.
  static String time(DateTime local, {String? locale}) =>
      DateFormat('HH:mm', locale).format(local);

  /// `22.08, 19:05`.
  static String dayMonthTime(DateTime local, {String? locale}) =>
      '${dayMonth(local, locale: locale)}, ${time(local, locale: locale)}';

  /// Converts an instant from the API into the device's local time.
  ///
  /// ADR 0031 sends instants as RFC 3339 UTC with `Z`. Rendering one without
  /// converting shows a customer in Tashkent a delivery promise five hours in
  /// the past, so the conversion is here and not left to a call site.
  static DateTime toLocal(DateTime instant) => instant.toLocal();

  /// Parses an ADR 0031 instant.
  ///
  /// Strict: a value that does not parse throws rather than silently becoming
  /// "now", which is what a null-coalescing parse would do to an order's
  /// placed-at time.
  static DateTime parseInstant(String value) => DateTime.parse(value).toUtc();
}
