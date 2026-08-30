import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/format/money.dart';

void main() {
  group('UZS has no minor units', () {
    // This group is the regression test for a bug that shipped in this codebase
    // in August 2026: a formatter asked ICU for the currency's decimal places,
    // ICU answered 2 for UZS as ISO 4217 says, and a customer was shown a price
    // one hundredth of the real one.
    test('84 000 som is rendered as 84 000, not 840.00', () {
      expect(MoneyFormat.amount(const Money(84000, 'UZS')), '84\u00A0000');
    });

    test('the exponent for UZS is zero', () {
      expect(MinorUnits.exponentFor('UZS'), 0);
    });

    test('an unrecorded currency throws rather than assuming two', () {
      // The dangerous failure is a silent default of 2. An exception at the
      // seam is recoverable; a wrong number on a receipt is not.
      expect(() => MinorUnits.exponentFor('XYZ'), throwsArgumentError);
    });
  });

  group('grouping', () {
    test('groups with a no-break space so a price never wraps mid-number', () {
      expect(MoneyFormat.groupSeparator, '\u00A0');
      expect(MoneyFormat.amount(const Money(1234567, 'UZS')), '1\u00A0234\u00A0567');
    });

    test('leaves amounts under a thousand ungrouped', () {
      expect(MoneyFormat.amount(const Money(999, 'UZS')), '999');
      expect(MoneyFormat.amount(const Money(0, 'UZS')), '0');
    });

    test('groups exactly at the thousand boundary', () {
      expect(MoneyFormat.amount(const Money(1000, 'UZS')), '1\u00A0000');
    });

    test('keeps the sign outside the grouping', () {
      expect(MoneyFormat.amount(const Money(-84000, 'UZS')), '-84\u00A0000');
    });
  });

  group('currencies that do have minor units', () {
    test('renders two decimals for USD', () {
      expect(
        MoneyFormat.amount(const Money(123456, 'USD'), locale: 'en'),
        '1\u00A0234.56',
      );
    });

    test('pads a fraction with a leading zero', () {
      expect(MoneyFormat.amount(const Money(105, 'USD'), locale: 'en'), '1.05');
    });

    test('takes the decimal separator from the locale', () {
      expect(MoneyFormat.amount(const Money(105, 'USD'), locale: 'ru'), '1,05');
    });
  });

  group('symbol', () {
    test('appends the localised marker rather than an ICU symbol', () {
      expect(
        MoneyFormat.withSymbol(const Money(84000, 'UZS'), "so'm"),
        "84\u00A0000\u00A0so'm",
      );
    });
  });

  group('arithmetic', () {
    test('adds within one currency', () {
      expect(
        const Money(1000, 'UZS') + const Money(500, 'UZS'),
        const Money(1500, 'UZS'),
      );
    });

    test('refuses to combine currencies', () {
      expect(
        () => const Money(1000, 'UZS') + const Money(500, 'USD'),
        throwsArgumentError,
      );
    });
  });

  group('wire format', () {
    test('round-trips the ADR 0031 money object', () {
      const Map<String, Object?> json = <String, Object?>{
        'amountMinor': 125000,
        'currency': 'UZS',
      };
      expect(Money.fromJson(json), const Money(125000, 'UZS'));
      expect(const Money(125000, 'UZS').toJson(), json);
    });

    test('rejects a bare number', () {
      expect(
        () => Money.fromJson(<String, Object?>{'amountMinor': 125000.0}),
        throwsFormatException,
      );
    });
  });
}
