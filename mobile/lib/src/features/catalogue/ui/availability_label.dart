import 'package:flutter/material.dart';

import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';

/// "Sold out", as a dot and a word.
///
/// The design system is explicit that status tone is a dot plus text and never
/// colour alone, and that yellow is a dot only — its text pair is the darker
/// warning ink. A greyed-out row would have been colour alone, and to a
/// colour-blind customer it would have been nothing at all.
///
/// Shown only when the server says the item is not orderable. Availability is
/// never inferred here: the branch's stop list is what the operations console
/// writes and what `MenuVariant.orderable` reports, and a second opinion
/// computed on the phone would eventually contradict the checkout.
class SoldOutLabel extends StatelessWidget {
  const SoldOutLabel({super.key});

  static const double _dotDiameter = 8;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final TextTheme text = Theme.of(context).textTheme;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        Container(
          width: _dotDiameter,
          height: _dotDiameter,
          decoration: BoxDecoration(
            color: tokens.warningDot,
            shape: BoxShape.circle,
          ),
        ),
        const SizedBox(width: QoidaGeometry.spaceXs),
        Text(
          l10n.catalogueSoldOut,
          style: text.bodySmall?.copyWith(color: tokens.warningInk),
        ),
      ],
    );
  }
}

/// The caption that stands where a price would be.
///
/// There is no price on this screen and there is no bug here. The published
/// menu carries no amount for a variant or for a modifier option — the catalog
/// module holds no money at all, by design — and the only endpoints that
/// produce one are the quote and the cart pricing call. ADR 0018 makes that
/// quote authoritative, so adding up parts on the phone to fill this space
/// would produce a number the server never agreed to and the customer would
/// remember it as the price.
class PriceInBasketCaption extends StatelessWidget {
  const PriceInBasketCaption({super.key});

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    return Text(
      AppLocalizations.of(context).cataloguePriceInBasket,
      style: Theme.of(context).textTheme.bodySmall
          ?.copyWith(color: tokens.inkSubtle),
    );
  }
}
