import 'package:flutter/material.dart';

import '../../../design/horecaos_theme.dart';
import '../../../design/horecaos_tokens.dart';
import '../../../format/money.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../../cart/money_label.dart';
import '../checkout_controller.dart';
import '../checkout_models.dart';

/// Where a delivery order goes, and what the branch charges to take it there.
///
/// **Every figure here comes from ADR 0037's resolver.** The stepped tariff,
/// the peak-hour table, the zone ranking, the minimum basket and the free-
/// delivery threshold are all its answers; this widget renders them and
/// computes none of them. The one arithmetic operation is the shortfall — the
/// difference between two amounts the resolver itself published — which ADR 0037
/// explicitly returns "so the storefront can say how much more is needed".
///
/// **The fee is shown beside the total and never added to it.** The quote is
/// what the platform will charge, and today a storefront quote carries no
/// delivery line at all: `CartPricingPort.PricingCommand` has no destination, so
/// `QuoteService` never reaches the resolver for a customer basket. Adding the
/// resolver's figure to the server's total here would produce a number no
/// invoice will ever match.
class DeliverySection extends StatelessWidget {
  const DeliverySection({
    required this.controller,
    super.key,
    this.onChooseDestination,
  });

  final CheckoutController controller;

  /// Opens the map picker. Absent until one exists — ADR 0035 puts the address
  /// pin behind Yandex MapKit, which this application does not bind yet.
  final VoidCallback? onChooseDestination;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final DeliveryFeeQuote? fee = controller.deliveryFee;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        Text(l10n.checkoutDeliveryTitle, style: text.titleSmall),
        const SizedBox(height: HorecaOSGeometry.spaceXs),
        Text(
          l10n.checkoutDeliveryBody,
          style: text.bodySmall?.copyWith(color: tokens.inkMuted),
        ),
        const SizedBox(height: HorecaOSGeometry.spaceSm),
        if (controller.destination == null)
          _Row(
            label: l10n.checkoutDeliveryNotSet,
            action: onChooseDestination,
            actionLabel: l10n.checkoutDeliveryTitle,
          )
        else if (controller.isResolvingDeliveryFee)
          Text(
            l10n.checkoutRepricing,
            style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
          )
        else if (fee == null)
          Text(l10n.checkoutDeliveryFeeUnknown, style: text.bodyMedium)
        else if (!fee.available)
          Text(l10n.checkoutDeliveryNotServed, style: text.bodyMedium)
        else ...<Widget>[
          if (fee.fee != null)
            _Row(
              label: l10n.checkoutDeliveryFeeLabel,
              value: moneyLabel(context, fee.fee!),
            ),
          if (controller.deliveryShortfall case final Money shortfall)
            Padding(
              padding: const EdgeInsets.only(top: HorecaOSGeometry.spaceXs),
              child: Text(
                l10n.checkoutDeliveryShortfall(moneyLabel(context, shortfall)),
                style: text.bodySmall?.copyWith(color: tokens.warningInk),
              ),
            )
          else if (fee.minimumBasket case final Money minimum)
            Padding(
              padding: const EdgeInsets.only(top: HorecaOSGeometry.spaceXs),
              child: Text(
                l10n.checkoutDeliveryMinimumBasket(
                  moneyLabel(context, minimum),
                ),
                style: text.bodySmall?.copyWith(color: tokens.inkMuted),
              ),
            ),
          if (fee.freeDeliveryFrom case final Money threshold)
            Padding(
              padding: const EdgeInsets.only(top: HorecaOSGeometry.spaceXs),
              child: Text(
                l10n.checkoutFreeDeliveryFrom(moneyLabel(context, threshold)),
                style: text.bodySmall?.copyWith(color: tokens.inkMuted),
              ),
            ),
        ],
      ],
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.label, this.value, this.action, this.actionLabel});

  final String label;
  final String? value;
  final VoidCallback? action;
  final String? actionLabel;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;

    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: <Widget>[
        Expanded(
          child: Text(
            label,
            style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
          ),
        ),
        if (value != null) Text(value!, style: text.bodyMedium),
        if (action != null && actionLabel != null)
          TextButton(onPressed: action, child: Text(actionLabel!)),
      ],
    );
  }
}
