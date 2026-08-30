import 'package:flutter/widgets.dart';

import '../../format/money.dart';
import '../../l10n/generated/app_localizations.dart';

/// Renders an amount, and is the only thing in this feature that does.
///
/// Every price on a cart or a checkout screen goes through here. Not because a
/// helper is tidy, but because the alternative is what the archived SwiftUI
/// application did: format per screen, and let the screens disagree.
///
/// The scale is [MoneyFormat]'s, which is the platform's, which for UZS is a
/// whole som. Nothing here asks ICU how many decimal places a currency has —
/// ICU says two for UZS and would divide every price by a hundred.
String moneyLabel(BuildContext context, Money money) {
  final String locale = Localizations.localeOf(context).toLanguageTag();
  if (money.currency == 'UZS') {
    return MoneyFormat.withSymbol(
      money,
      AppLocalizations.of(context).currencySymbolUzs,
      locale: locale,
    );
  }
  // No other currency is configured for a HorecaOS brand today, and the ARB has no
  // marker for one. The ISO code is used rather than a symbol invented here:
  // wrong-looking is recoverable, wrong-valued is not.
  return '${MoneyFormat.amount(money, locale: locale)}'
      '${MoneyFormat.groupSeparator}${money.currency}';
}
