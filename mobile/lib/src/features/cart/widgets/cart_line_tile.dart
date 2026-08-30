import 'package:flutter/material.dart';

import '../../../design/q_icon.dart';
import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../cart_item_naming.dart';
import '../cart_models.dart';

/// One line of the basket: what it is, how many, and the two controls that
/// change that.
///
/// **No price.** The storefront cart endpoint publishes no per-line amount and
/// the published menu publishes none either — the only money in this flow is the
/// quote's three figures. A per-line price shown here would have to be invented,
/// and an invented price beside a real total is how a customer learns to
/// distrust both.
class CartLineTile extends StatelessWidget {
  const CartLineTile({
    required this.line,
    required this.naming,
    required this.onQuantityChanged,
    required this.onRemove,
    super.key,
    this.enabled = true,
  });

  final CartLine line;
  final CartItemNaming naming;
  final ValueChanged<int> onQuantityChanged;
  final VoidCallback onRemove;

  /// False while a mutation is in flight. The controls stay visible and stop
  /// responding, rather than disappearing and moving everything under the
  /// customer's thumb.
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final CartItemName? name = naming.nameFor(line.variantId);

    final List<String> modifiers = <String>[
      for (final String optionId in line.modifierOptionIds)
        naming.modifierOptionLabel(optionId) ?? optionId,
    ];

    return Padding(
      padding: const EdgeInsets.symmetric(
        vertical: QoidaGeometry.spaceMd,
        horizontal: QoidaGeometry.spaceMd,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  name?.productName ?? l10n.cartLineUnnamed,
                  style: text.bodyLarge,
                ),
                if (name?.variantLabel != null)
                  Text(
                    name!.variantLabel!,
                    style: text.bodySmall?.copyWith(color: tokens.inkMuted),
                  ),
                if (modifiers.isNotEmpty)
                  Text(
                    modifiers.join(', '),
                    style: text.bodySmall?.copyWith(color: tokens.inkMuted),
                  ),
                if (line.hasCustomerNote)
                  Text(l10n.cartLineNote, style: text.bodySmall),
                if (name != null && !name.orderable)
                  Padding(
                    padding: const EdgeInsets.only(top: QoidaGeometry.spaceXs),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: <Widget>[
                        SizedBox(
                          width: QoidaGeometry.spaceSm,
                          height: QoidaGeometry.spaceSm,
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              shape: BoxShape.circle,
                              color: tokens.warningDot,
                            ),
                          ),
                        ),
                        const SizedBox(width: QoidaGeometry.spaceXs),
                        Text(
                          l10n.cartLineSoldOut,
                          style: text.bodySmall?.copyWith(
                            color: tokens.warningInk,
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(width: QoidaGeometry.spaceSm),
          _QuantityStepper(
            quantity: line.quantity,
            enabled: enabled,
            onChanged: onQuantityChanged,
          ),
          IconButton(
            onPressed: enabled ? onRemove : null,
            tooltip: l10n.cartRemoveLine,
            icon: const QIcon(QIconName.close),
          ),
        ],
      ),
    );
  }
}

/// Minus, the count, plus.
///
/// The two controls are text rather than icons on purpose: the icon set is
/// closed and holds no plus and no minus, and adding entries to it is a design
/// review rather than something a screen does for itself. `+` and `−` are
/// glyphs from the type scale, which the design system already governs.
class _QuantityStepper extends StatelessWidget {
  const _QuantityStepper({
    required this.quantity,
    required this.enabled,
    required this.onChanged,
  });

  final int quantity;
  final bool enabled;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        _StepButton(
          // A true minus sign, not a hyphen. At a stepper's size the hyphen
          // reads as a stray mark.
          glyph: '−',
          semanticLabel: l10n.cartQuantityDecrease,
          onPressed: enabled ? () => onChanged(quantity - 1) : null,
        ),
        SizedBox(
          // Fixed, so the row does not jump one pixel sideways when the count
          // crosses from nine to ten while a thumb is resting on the button.
          width: QoidaGeometry.spaceXl,
          child: Text(
            '$quantity',
            textAlign: TextAlign.center,
            style: text.titleSmall,
          ),
        ),
        _StepButton(
          glyph: '+',
          semanticLabel: l10n.cartQuantityIncrease,
          onPressed: enabled ? () => onChanged(quantity + 1) : null,
        ),
      ],
    );
  }
}

class _StepButton extends StatelessWidget {
  const _StepButton({
    required this.glyph,
    required this.semanticLabel,
    required this.onPressed,
  });

  final String glyph;
  final String semanticLabel;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    return Semantics(
      button: true,
      label: semanticLabel,
      child: SizedBox(
        // MOBILE takes the larger of Material's 48 and iOS's 44 on both
        // platforms, so one codebase does not render as two products.
        width: tokens.minTarget,
        height: tokens.minTarget,
        child: OutlinedButton(
          onPressed: onPressed,
          child: ExcludeSemantics(child: Text(glyph)),
        ),
      ),
    );
  }
}
