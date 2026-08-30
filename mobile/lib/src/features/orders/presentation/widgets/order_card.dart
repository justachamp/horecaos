import 'package:flutter/material.dart';

import '../../../../design/horecaos_theme.dart';
import '../../../../design/horecaos_tokens.dart';
import '../../../../design/horecaos_typography.dart';
import '../../../../format/horecaos_formats.dart';
import '../../../../l10n/generated/app_localizations.dart';
import '../../data/order_models.dart';
import '../order_strings.dart';
import 'order_status_pill.dart';

/// One order in the history.
///
/// A card and not a table row. ADR 0035's component table is explicit that
/// `DataTable` is "not ported" to MOBILE — a table is not a phone pattern, and
/// the equivalent is a lazily-built list of cards.
class OrderCard extends StatefulWidget {
  const OrderCard({
    required this.order,
    required this.onOpen,
    super.key,
    this.now,
  });

  final OrderSummary order;
  final VoidCallback onOpen;

  /// Injectable clock, because lateness is derived against it and a test that
  /// cannot fix "now" cannot assert the derivation.
  final DateTime? now;

  @override
  State<OrderCard> createState() => _OrderCardState();
}

class _OrderCardState extends State<OrderCard> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final String locale = Localizations.localeOf(context).toLanguageTag();
    final OrderSummary order = widget.order;

    final DateTime placedAt = HorecaOSFormats.toLocal(order.placedAt);

    return Semantics(
      button: true,
      label: l10n.ordersNumber(order.publicOrderNumber),
      child: GestureDetector(
        onTapDown: (_) => setState(() => _pressed = true),
        onTapUp: (_) => setState(() => _pressed = false),
        onTapCancel: () => setState(() => _pressed = false),
        onTap: widget.onOpen,
        child: Container(
          constraints: BoxConstraints(minHeight: tokens.minTarget),
          padding: const EdgeInsets.all(HorecaOSGeometry.spaceMd),
          decoration: BoxDecoration(
            // The press state is a token colour change, because the ink ripple
            // is switched off system-wide: a ripple is decoration and this
            // system has none.
            color: _pressed ? tokens.surface1 : tokens.canvas,
            borderRadius: BorderRadius.circular(tokens.radius),
            border: Border.all(
              color: tokens.hairline,
              width: HorecaOSGeometry.hairline,
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Expanded(
                    child: Text(
                      l10n.ordersNumber(order.publicOrderNumber),
                      style: text.titleMedium,
                    ),
                  ),
                  const SizedBox(width: HorecaOSGeometry.spaceSm),
                  Text(
                    OrderStrings.money(context, order.total),
                    // Tabular figures so a column of totals does not ripple as
                    // the list scrolls.
                    style: text.titleMedium?.copyWith(
                      fontFeatures: HorecaOSTypography.tabular,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: HorecaOSGeometry.spaceSm),
              Row(
                children: <Widget>[
                  Expanded(
                    child: Text(
                      l10n.ordersPlacedAt(
                        HorecaOSFormats.dayMonthTime(placedAt, locale: locale),
                      ),
                      style: text.bodySmall,
                    ),
                  ),
                  const SizedBox(width: HorecaOSGeometry.spaceSm),
                  Flexible(
                    child: OrderStatusPill(
                      status: order.status,
                      isLate: order.isLate(widget.now ?? DateTime.now()),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
