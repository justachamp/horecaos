import 'package:intl/intl.dart';

/// Money on the wire and in the application (ADR 0031).
///
/// Always an object, never a bare number. A bare number loses the currency, and
/// a client that guesses the scale eventually charges someone a hundred times
/// too much.
final class Money implements Comparable<Money> {
  const Money(this.amountMinor, this.currency);

  /// The amount in the currency's minor unit, as an integer.
  ///
  /// Integer, not double: a double cannot represent every minor unit exactly,
  /// and a total that cannot be reproduced is a total nobody can audit.
  final int amountMinor;

  /// ISO 4217 alphabetic code.
  final String currency;

  static const Money zeroUzs = Money(0, 'UZS');

  factory Money.fromJson(Map<String, Object?> json) {
    final Object? amount = json['amountMinor'];
    final Object? currency = json['currency'];
    if (amount is! int || currency is! String) {
      throw FormatException('Not a money object: $json');
    }
    return Money(amount, currency);
  }

  Map<String, Object?> toJson() => <String, Object?>{
    'amountMinor': amountMinor,
    'currency': currency,
  };

  Money operator +(Money other) {
    _requireSameCurrency(other);
    return Money(amountMinor + other.amountMinor, currency);
  }

  Money operator -(Money other) {
    _requireSameCurrency(other);
    return Money(amountMinor - other.amountMinor, currency);
  }

  void _requireSameCurrency(Money other) {
    if (other.currency != currency) {
      throw ArgumentError('Cannot combine $currency with ${other.currency}');
    }
  }

  @override
  int compareTo(Money other) {
    _requireSameCurrency(other);
    return amountMinor.compareTo(other.amountMinor);
  }

  @override
  bool operator ==(Object other) =>
      other is Money &&
      other.amountMinor == amountMinor &&
      other.currency == currency;

  @override
  int get hashCode => Object.hash(amountMinor, currency);

  @override
  String toString() => 'Money($amountMinor $currency)';
}

/// How many minor units make one major unit, per currency.
///
/// **This table is why this file exists.** ISO 4217 gives UZS an exponent of 2,
/// because a som is nominally a hundred tiyin. The Qoida platform stores whole
/// som as its minor unit and ADR 0018 says so: there is nothing to divide.
///
/// Any formatter that asks ICU — `NumberFormat.currency` without an explicit
/// `decimalDigits`, `NumberFormat.simpleCurrency`, `Intl.NumberFormat` on the
/// web — gets the ISO answer, divides by a hundred, and shows a customer a
/// price a hundredth of the real one. That bug shipped in this codebase in
/// August 2026. The table below is the platform's answer, and nothing in this
/// application may ask ICU for it.
abstract final class MinorUnits {
  static const Map<String, int> _exponents = <String, int>{
    // The platform's minor unit for som is the som. Not a rounding decision:
    // no price, tax, fee or total in this system is ever expressed in tiyin.
    'UZS': 0,
    'USD': 2,
    'EUR': 2,
    'RUB': 2,
  };

  /// Throws rather than assuming 2.
  ///
  /// Defaulting to 2 is what produces a silently wrong price for a currency
  /// nobody thought about. An exception at the seam is recoverable; a wrong
  /// number on a receipt is not.
  static int exponentFor(String currency) {
    final int? exponent = _exponents[currency];
    if (exponent == null) {
      throw ArgumentError(
        'No minor-unit exponent recorded for $currency. Add it to '
        'MinorUnits._exponents; do not fall back to ICU, which reports 2 for '
        'UZS and would divide a som price by a hundred.',
      );
    }
    return exponent;
  }

  static bool isKnown(String currency) => _exponents.containsKey(currency);
}

/// The one money formatter in the application.
///
/// One, because the archived SwiftUI application reimplemented formatting per
/// screen and the screens disagreed with each other.
abstract final class MoneyFormat {
  /// The group separator.
  ///
  /// A no-break space rather than an ordinary one: `84 000` must never wrap
  /// between the `84` and the `000`, which on a narrow phone row it otherwise
  /// will. The design system asks for a space; this is the space that behaves.
  static const String groupSeparator = '\u00A0';

  /// `84 000` — the amount alone, with no currency marker.
  ///
  /// The marker is localised (`so'm`, `сум`) and comes from the ARB files, so
  /// it is applied by [withSymbol] rather than baked in here.
  static String amount(Money money, {String? locale}) {
    final int exponent = MinorUnits.exponentFor(money.currency);
    final bool negative = money.amountMinor < 0;
    final int magnitude = money.amountMinor.abs();

    final String sign = negative ? '-' : '';
    if (exponent == 0) {
      return '$sign${_group(magnitude.toString())}';
    }

    final int divisor = _pow10(exponent);
    final String major = _group((magnitude ~/ divisor).toString());
    final String minor = (magnitude % divisor).toString().padLeft(exponent, '0');
    return '$sign$major${_decimalSeparator(locale)}$minor';
  }

  /// `84 000 so'm`.
  ///
  /// [symbol] comes from the localisations, never from ICU: ICU's UZS symbol
  /// arrives with ICU's UZS scale, and taking one without the other is how the
  /// two drift apart.
  /// The space before the marker is a no-break space too. `84 000` and
  /// `so'm` are one price, and a line break between them reads as two.
  static String withSymbol(Money money, String symbol, {String? locale}) =>
      '${amount(money, locale: locale)}$groupSeparator$symbol';

  /// intl is asked for the decimal separator and nothing else.
  ///
  /// Separator conventions are genuinely locale data and intl has them right.
  /// Currency scale is platform data and intl has it wrong for us.
  static String _decimalSeparator(String? locale) =>
      NumberFormat.decimalPattern(locale).symbols.DECIMAL_SEP;

  static String _group(String digits) {
    final StringBuffer out = StringBuffer();
    for (int i = 0; i < digits.length; i++) {
      final int fromEnd = digits.length - i;
      if (i > 0 && fromEnd % 3 == 0) {
        out.write(groupSeparator);
      }
      out.write(digits[i]);
    }
    return out.toString();
  }

  static int _pow10(int exponent) {
    int value = 1;
    for (int i = 0; i < exponent; i++) {
      value *= 10;
    }
    return value;
  }
}
