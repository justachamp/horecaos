import 'package:flutter/material.dart';

import '../../../../design/horecaos_theme.dart';
import '../../../../design/horecaos_tokens.dart';
import '../../../../l10n/generated/app_localizations.dart';
import '../../data/order_codes.dart';
import '../order_strings.dart';
import 'status_dot.dart';

/// The status pill, in its dual state.
///
/// ADR 0035's component table asks MOBILE's `StatusPill` for "order status and
/// courier progress", and CONSOLE's for "lateness as an overlay on a status".
/// The overlay is what the second dot is: the order is *being prepared* and it
/// is *later than promised*, which are two facts, and collapsing them into one
/// red pill would lose the first.
class OrderStatusPill extends StatelessWidget {
  const OrderStatusPill({
    required this.status,
    super.key,
    this.isLate = false,
  });

  final OrderStatus status;

  /// Derived by the caller from `promised_at` against the clock. There is no
  /// lateness field on the platform and there is deliberately never going to
  /// be one (V0023).
  final bool isLate;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: HorecaOSGeometry.spaceSm,
        vertical: HorecaOSGeometry.spaceXs,
      ),
      decoration: BoxDecoration(
        color: tokens.surface1,
        borderRadius: BorderRadius.circular(tokens.radius),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          StatusDot(tone: toneForStatus(status)),
          const SizedBox(width: HorecaOSGeometry.spaceXs),
          Flexible(
            child: Text(
              OrderStrings.status(l10n, status),
              style: text.labelLarge,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          if (isLate) ...<Widget>[
            const SizedBox(width: HorecaOSGeometry.spaceSm),
            const StatusDot(tone: StatusTone.warning),
            const SizedBox(width: HorecaOSGeometry.spaceXs),
            Flexible(
              child: Text(
                l10n.orderLate,
                // Yellow is a dot only; the words beside it take the darker
                // warning ink, which is the pairing the token sheet specifies.
                style: text.labelLarge?.copyWith(color: tokens.warningInk),
                overflow: TextOverflow.ellipsis,
              ),
            ),
          ],
        ],
      ),
    );
  }
}
