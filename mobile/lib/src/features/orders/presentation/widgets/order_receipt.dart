import 'package:flutter/material.dart';

import '../../../../design/horecaos_theme.dart';
import '../../../../design/horecaos_tokens.dart';
import '../../../../design/horecaos_typography.dart';
import '../../../../format/money.dart';
import '../../../../l10n/generated/app_localizations.dart';
import '../../data/order_models.dart';
import '../order_strings.dart';

/// A section heading, in the one weight the scale has for it.
class SectionHeading extends StatelessWidget {
  const SectionHeading(this.title, {super.key});

  final String title;

  @override
  Widget build(BuildContext context) => Text(
    title,
    // Sentence case, as the design system requires. No all-caps, no letter
    // spacing tricks, no rule underneath.
    style: Theme.of(context).textTheme.titleMedium,
  );
}

/// What was ordered.
///
/// These are the snapshot rows the order stored at checkout, not a join back to
/// the catalogue. That is what makes "the order says what it said" a property
/// of the data rather than a promise, and it is why renaming a dish next month
/// does not rewrite last week's receipt.
class OrderLinesPanel extends StatelessWidget {
  const OrderLinesPanel({required this.lines, super.key});

  final List<OrderLine> lines;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        for (final OrderLine line in lines)
          Padding(
            padding: const EdgeInsets.only(bottom: HorecaOSGeometry.spaceMd),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                SizedBox(
                  width: 32,
                  child: Text(
                    // The multiplication sign, not the letter x.
                    '${line.quantity}×',
                    style: text.bodyMedium?.copyWith(
                      color: tokens.inkMuted,
                      fontFeatures: HorecaOSTypography.tabular,
                    ),
                  ),
                ),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(line.productName, style: text.bodyLarge),
                      if (line.variantName != null)
                        Text(line.variantName!, style: text.bodySmall),
                      if (line.modifiers.isNotEmpty)
                        Text(line.modifiers.join(', '), style: text.bodySmall),
                    ],
                  ),
                ),
                const SizedBox(width: HorecaOSGeometry.spaceSm),
                Text(
                  OrderStrings.money(context, line.total),
                  style: text.bodyLarge?.copyWith(
                    fontFeatures: HorecaOSTypography.tabular,
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

/// What it cost.
///
/// A row per component the order actually carries. An absent discount is not
/// rendered as a zero: "no discount was applied" and "a discount of nothing"
/// are different statements, and only one of them is true of most orders.
class OrderTotalsPanel extends StatelessWidget {
  const OrderTotalsPanel({required this.order, super.key});

  final OrderDetail order;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Column(
      children: <Widget>[
        _TotalRow(label: l10n.orderSubtotal, amount: order.subtotal),
        if (order.discount != null)
          _TotalRow(label: l10n.orderDiscount, amount: order.discount!),
        if (order.tax != null)
          _TotalRow(label: l10n.orderTax, amount: order.tax!),
        if (order.fee != null)
          _TotalRow(label: l10n.orderFee, amount: order.fee!),
        const Divider(),
        _TotalRow(
          label: l10n.orderTotal,
          amount: order.total,
          emphasised: true,
        ),
      ],
    );
  }
}

class _TotalRow extends StatelessWidget {
  const _TotalRow({
    required this.label,
    required this.amount,
    this.emphasised = false,
  });

  final String label;
  final Money amount;
  final bool emphasised;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final TextStyle? style = emphasised ? text.titleMedium : text.bodyMedium;

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: HorecaOSGeometry.spaceXs),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Text(
              label,
              style: emphasised
                  ? style
                  : style?.copyWith(color: tokens.inkMuted),
            ),
          ),
          Text(
            OrderStrings.money(context, amount),
            style: style?.copyWith(fontFeatures: HorecaOSTypography.tabular),
          ),
        ],
      ),
    );
  }
}

/// How it is being paid.
///
/// Two facts and no more: the method's display name, where the platform sent
/// one, and the closed payment projection turned into a sentence. The
/// tenant-defined method *code* is never rendered — `CLICK_UP` is a row in a
/// table, not the name of anything a customer recognises — and neither is a
/// provider, a merchant account, or an attempt: ADR 0025 keeps payment attempt
/// detail behind its own capability, and a customer's receipt is not where it
/// belongs.
class OrderPaymentPanel extends StatelessWidget {
  const OrderPaymentPanel({required this.payment, super.key});

  final OrderPayment payment;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final String? method = payment.methodName;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        if (method != null && method.isNotEmpty)
          Padding(
            padding: const EdgeInsets.only(bottom: HorecaOSGeometry.spaceXs),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: Text(
                    l10n.orderPaymentMethod,
                    style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
                  ),
                ),
                Text(method, style: text.bodyMedium),
              ],
            ),
          ),
        Text(OrderStrings.payment(l10n, payment.status), style: text.bodyLarge),
      ],
    );
  }
}
