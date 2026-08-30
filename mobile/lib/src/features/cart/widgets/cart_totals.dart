import 'package:flutter/material.dart';

import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../cart_models.dart';
import '../money_label.dart';

/// The server's figures, and only the server's figures.
///
/// Three rows, because `PricedCartResponse` publishes three amounts: the goods
/// subtotal, the tax, and the total. Nothing is added, nothing is derived, and
/// in particular no fourth row is inferred from the difference between them —
/// `QuoteSnapshot` carries a fee and a discount that this endpoint does not
/// send, so a "delivery" row computed as `total - subtotal - tax` would be a
/// number this application made up and attributed to the platform.
class CartTotals extends StatelessWidget {
  const CartTotals({required this.quote, super.key});

  /// Null when the basket has been edited since it was priced. Every edit
  /// clears the quote on the server, so there is genuinely no total to show and
  /// the customer is told that rather than shown the previous one.
  final PricedCart? quote;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final PricedCart? priced = quote;

    if (priced == null) {
      return Padding(
        padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
        child: Text(
          l10n.cartPriceNotConfirmed,
          style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
        ),
      );
    }

    return Padding(
      padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
      child: Column(
        children: <Widget>[
          _Row(label: l10n.cartSubtotal, value: moneyLabel(context, priced.subtotal)),
          const SizedBox(height: QoidaGeometry.spaceXs),
          _Row(label: l10n.cartTax, value: moneyLabel(context, priced.tax)),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: QoidaGeometry.spaceSm),
            child: Divider(),
          ),
          _Row(
            label: l10n.cartTotal,
            value: moneyLabel(context, priced.total),
            emphasised: true,
          ),
        ],
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
    required this.label,
    required this.value,
    this.emphasised = false,
  });

  final String label;
  final String value;
  final bool emphasised;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;
    final TextStyle? style = emphasised ? text.titleSmall : text.bodyMedium;

    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: <Widget>[
        Text(
          label,
          style: emphasised
              ? style
              : style?.copyWith(color: tokens.inkMuted),
        ),
        Text(value, style: style),
      ],
    );
  }
}
