import 'package:flutter/material.dart';

import '../../../design/horecaos_theme.dart';
import '../../../format/money.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../../cart/money_label.dart';
import '../../cart/widgets/problem_panel.dart';
import '../checkout_controller.dart';
import '../checkout_models.dart';

/// The stale-quote path, rendered.
///
/// ADR 0018's rule is that a quote is reproducible from a context hash and that
/// checkout accepts that hash and nothing else. When the world moves, the
/// refusal is correct — so this is not an error panel. It is the screen that
/// tells a customer their order was **not** placed, what it costs now, and asks
/// them to decide again.
///
/// Three things it does deliberately:
///
/// * names the reason, because a republished menu and an expired fifteen-minute
///   quote are different events and only one of them is about money;
/// * shows both totals side by side, rather than replacing one number with
///   another and hoping the customer noticed;
/// * says so when the total did not actually change, which happens often — a
///   republication invalidates a hash without necessarily moving a price.
class PriceMovedPanel extends StatelessWidget {
  const PriceMovedPanel({
    required this.stage,
    required this.onAccept,
    super.key,
  });

  final CheckoutPriceMoved stage;
  final VoidCallback onAccept;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    final (String title, String body) = switch (stage.reason) {
      StaleQuoteReason.priceChanged => (
        l10n.checkoutPriceChangedTitle,
        l10n.checkoutPriceChangedBody,
      ),
      StaleQuoteReason.quoteExpired => (
        l10n.checkoutQuoteExpiredTitle,
        l10n.checkoutQuoteExpiredBody,
      ),
      StaleQuoteReason.menuRepublished => (
        l10n.checkoutMenuChangedTitle,
        l10n.checkoutMenuChangedBody,
      ),
      StaleQuoteReason.basketChanged => (
        l10n.checkoutBasketChangedTitle,
        l10n.checkoutBasketChangedBody,
      ),
    };

    return QProblemPanel(
      title: title,
      body: body,
      tone: ProblemTone.warning,
      actionLabel: stage.awaitingConfirmation ? l10n.checkoutAcceptNewPrice : null,
      onAction: stage.awaitingConfirmation ? onAccept : null,
      child: _Comparison(stage: stage),
    );
  }
}

class _Comparison extends StatelessWidget {
  const _Comparison({required this.stage});

  final CheckoutPriceMoved stage;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final Money? current = stage.newQuote?.total;

    if (current == null) {
      return Text(
        l10n.checkoutRepricing,
        style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
      );
    }

    if (stage.totalUnchanged) {
      // Saying "it is the same" is a better answer than printing one figure
      // twice and leaving the customer to compare two identical numbers.
      return Text(l10n.checkoutPriceSame, style: text.bodyMedium);
    }

    return Row(
      children: <Widget>[
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(l10n.checkoutPriceBefore, style: text.bodySmall),
              Text(
                moneyLabel(context, stage.previousTotal),
                style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
              ),
            ],
          ),
        ),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(l10n.checkoutPriceNow, style: text.bodySmall),
              Text(moneyLabel(context, current), style: text.titleSmall),
            ],
          ),
        ),
      ],
    );
  }
}
