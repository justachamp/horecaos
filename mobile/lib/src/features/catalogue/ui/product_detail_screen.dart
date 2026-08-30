import 'package:flutter/material.dart';

import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../data/menu.dart';
import '../data/menu_index.dart';
import '../domain/modifier_selection.dart';
import 'availability_label.dart';
import 'catalogue_pressable.dart';

/// One product: its variants, its modifier groups, and no price.
///
/// The absent price is the point rather than a gap in this screen. ADR 0018
/// makes the quote authoritative and its context hash provable, and the
/// published menu carries no amount for a variant or a modifier option at all —
/// so there is nothing here to add up, and adding up parts would produce a
/// number the server never quoted. The customer sees the price where the server
/// states it, which is the priced cart.
class ProductDetailScreen extends StatefulWidget {
  const ProductDetailScreen({
    required this.index,
    required this.product,
    super.key,
    this.onAddToBasket,
  });

  final MenuIndex index;
  final MenuProduct product;

  /// Supplied by the cart, which is a different feature. Null hides the action
  /// rather than showing a button that does nothing.
  final void Function(ProductConfiguration configuration)? onAddToBasket;

  @override
  State<ProductDetailScreen> createState() => _ProductDetailScreenState();
}

class _ProductDetailScreenState extends State<ProductDetailScreen> {
  late ProductConfiguration _configuration = ProductConfiguration(
    product: widget.product,
    variant: widget.product.preferredVariant,
    groups: widget.index.modifierGroupsFor(widget.product.productId),
    selection: const ModifierSelection.empty(),
  );

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;
    final MenuProduct product = widget.product;

    return Scaffold(
      appBar: AppBar(title: Text(product.name)),
      body: ListView(
        padding: const EdgeInsets.only(bottom: QoidaGeometry.spaceXl),
        children: <Widget>[
          if (product.description != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(
                QoidaGeometry.spaceMd,
                QoidaGeometry.spaceMd,
                QoidaGeometry.spaceMd,
                0,
              ),
              child: Text(
                product.description!,
                style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
              ),
            ),

          Padding(
            padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
            child: product.isOrderable
                ? const PriceInBasketCaption()
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      const SoldOutLabel(),
                      const SizedBox(height: QoidaGeometry.spaceXs),
                      Text(
                        l10n.catalogueProductSoldOutBody,
                        style: text.bodyMedium?.copyWith(
                          color: tokens.inkMuted,
                        ),
                      ),
                    ],
                  ),
          ),

          // One variant needs no picker: there is nothing to choose between,
          // and a single-row selector is a control that asks a question with
          // one answer.
          if (product.variants.length > 1) ...<Widget>[
            _Heading(l10n.catalogueVariantsTitle),
            for (final MenuVariant variant in product.variants)
              _VariantRow(
                variant: variant,
                label: _variantLabel(variant, l10n),
                selected:
                    _configuration.variant?.variantId == variant.variantId,
                onSelected: variant.orderable
                    ? () => setState(() {
                        _configuration = _configuration.withVariant(variant);
                      })
                    : null,
              ),
          ],

          for (final MenuModifierGroup group in _configuration.groups)
            _ModifierGroupSection(
              state: _configuration.selection.stateFor(group),
              onToggle: (String optionId) => setState(() {
                _configuration = _configuration.withToggled(group, optionId);
              }),
            ),

          if (widget.onAddToBasket != null)
            Padding(
              padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
              child: FilledButton(
                // Disabled, not hidden. A greyed action that stays where the
                // customer expects it explains itself; an action that vanishes
                // reads as a broken screen.
                onPressed: _configuration.isOrderable
                    ? () => widget.onAddToBasket!(_configuration)
                    : null,
                child: Text(l10n.catalogueAddToBasket),
              ),
            ),
        ],
      ),
    );
  }

  /// What to call a variant.
  ///
  /// The published menu carries no display name for one — translations are
  /// published for categories, products and modifier groups only — so this
  /// falls back through the two identifying strings the wire does carry, and
  /// then to a neutral word. A SKU in front of a customer is poor, and it is
  /// better than an unlabelled radio button; both are reported upward rather
  /// than accepted.
  String _variantLabel(MenuVariant variant, AppLocalizations l10n) =>
      variant.sku ?? variant.unitCode ?? l10n.catalogueVariantUnnamed;
}

class _Heading extends StatelessWidget {
  const _Heading(this.text, {this.trailing});

  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        QoidaGeometry.spaceMd,
        QoidaGeometry.spaceLg,
        QoidaGeometry.spaceMd,
        QoidaGeometry.spaceSm,
      ),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Text(text, style: Theme.of(context).textTheme.titleMedium),
          ),
          ?trailing,
        ],
      ),
    );
  }
}

class _VariantRow extends StatelessWidget {
  const _VariantRow({
    required this.variant,
    required this.label,
    required this.selected,
    required this.onSelected,
  });

  final MenuVariant variant;
  final String label;
  final bool selected;

  /// Null when the branch has stopped this variant. The row stays visible and
  /// says so.
  final VoidCallback? onSelected;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;

    return CataloguePressable(
      onTap: onSelected,
      semanticsLabel: label,
      child: Row(
        children: <Widget>[
          SelectionIndicator(selected: selected, round: true),
          const SizedBox(width: QoidaGeometry.spaceMd),
          Expanded(
            child: Text(
              label,
              style: text.bodyLarge?.copyWith(
                color: onSelected == null ? tokens.inkMuted : tokens.ink,
              ),
            ),
          ),
          if (!variant.orderable) const SoldOutLabel(),
        ],
      ),
    );
  }
}

class _ModifierGroupSection extends StatelessWidget {
  const _ModifierGroupSection({required this.state, required this.onToggle});

  final ModifierGroupState state;
  final void Function(String optionId) onToggle;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;
    final MenuModifierGroup group = state.group;

    // A group whose own bounds cannot be satisfied is not rendered as a picker.
    // The numbers are the server's and repairing them here would mean guessing
    // which of the two was wrong, then letting the customer complete a choice
    // the checkout will refuse.
    if (state.problem == ModifierSelectionProblem.groupIsIncoherent) {
      return Padding(
        padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(group.name, style: text.titleSmall),
            const SizedBox(height: QoidaGeometry.spaceXs),
            Text(
              l10n.catalogueGroupUnavailable,
              style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
            ),
          ],
        ),
      );
    }

    final bool single = ModifierGroupRules.isSingleChoice(group);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        _Heading(
          group.name,
          trailing: Text(
            group.required ? l10n.catalogueRequired : l10n.catalogueOptional,
            style: text.labelMedium?.copyWith(
              color: group.required ? tokens.ink : tokens.inkMuted,
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: QoidaGeometry.spaceMd,
          ),
          child: Text(
            _instruction(group, l10n),
            style: text.bodySmall?.copyWith(color: tokens.inkMuted),
          ),
        ),
        const SizedBox(height: QoidaGeometry.spaceSm),
        for (final MenuModifierOption option in group.options)
          CataloguePressable(
            onTap: state.canToggle(option.optionId)
                ? () => onToggle(option.optionId)
                : null,
            child: Row(
              children: <Widget>[
                SelectionIndicator(
                  selected: state.isSelected(option.optionId),
                  round: single,
                ),
                const SizedBox(width: QoidaGeometry.spaceMd),
                Expanded(
                  child: Text(
                    // The wire carries only an authoring code for an option;
                    // there is no published translation for one.
                    option.code ?? l10n.catalogueOptionUnnamed,
                    style: text.bodyLarge?.copyWith(
                      color: state.canToggle(option.optionId)
                          ? tokens.ink
                          : tokens.inkMuted,
                    ),
                  ),
                ),
              ],
            ),
          ),
      ],
    );
  }

  /// The one sentence that describes this group's bounds.
  ///
  /// Both numbers are the server's. A group with a minimum equal to its maximum
  /// asks for exactly that many; one with a lower minimum asks for at least
  /// that many; one with no minimum and room for more than one says how many it
  /// will take.
  String _instruction(MenuModifierGroup group, AppLocalizations l10n) {
    if (group.minimumSelections == group.maximumSelections) {
      return l10n.catalogueChooseExactly(group.minimumSelections);
    }
    if (group.minimumSelections > 0) {
      return l10n.catalogueChooseAtLeast(group.minimumSelections);
    }
    return l10n.catalogueChooseUpTo(group.maximumSelections);
  }
}

/// The radio or the checkbox, drawn from tokens.
///
/// Material's `Radio` and `Checkbox` were the alternative. They bring their own
/// shape, their own animation and their own ripple — three things this design
/// system either overrides or forbids — and the override surface is larger than
/// the widget.
class SelectionIndicator extends StatelessWidget {
  const SelectionIndicator({
    required this.selected,
    required this.round,
    super.key,
  });

  final bool selected;

  /// Round for "choose one", square for "choose some". The shape is the only
  /// thing that tells a customer which kind of question this is before they
  /// answer it.
  final bool round;

  static const double _size = 20;
  static const double _dot = 8;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    return SizedBox(
      width: _size,
      height: _size,
      child: DecoratedBox(
        decoration: BoxDecoration(
          shape: round ? BoxShape.circle : BoxShape.rectangle,
          borderRadius: round
              ? null
              : BorderRadius.circular(QoidaGeometry.spaceXs),
          border: Border.all(
            color: selected ? tokens.accent : tokens.hairline,
            width: selected ? 2 : QoidaGeometry.hairline,
          ),
        ),
        child: selected
            ? Center(
                child: SizedBox(
                  width: _dot,
                  height: _dot,
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      color: tokens.accent,
                      shape: round ? BoxShape.circle : BoxShape.rectangle,
                    ),
                  ),
                ),
              )
            : null,
      ),
    );
  }
}
