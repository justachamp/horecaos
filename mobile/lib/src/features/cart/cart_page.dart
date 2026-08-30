import 'package:flutter/material.dart';

import '../../design/q_empty_state.dart';
import '../../design/horecaos_theme.dart';
import '../../design/horecaos_tokens.dart';
import '../../l10n/generated/app_localizations.dart';
import 'cart_controller.dart';
import 'cart_item_naming.dart';
import 'cart_models.dart';
import 'widgets/cart_line_tile.dart';
import 'widgets/cart_totals.dart';
import 'widgets/problem_panel.dart';

/// The basket.
///
/// Lines with their modifiers and quantities, the server's totals, and one
/// primary action whose label depends on whether the basket has a price yet.
/// Two actions — "confirm the price" and "checkout" — because a basket without a
/// bound quote has nothing to check out with, and offering the second while
/// meaning the first would make the price appear after the decision.
class CartPage extends StatelessWidget {
  const CartPage({
    required this.controller,
    super.key,
    this.naming = const EmptyCartItemNaming(),
    this.onCheckout,
    this.onBrowseMenu,
  });

  final CartController controller;

  /// Where line names come from. Supplied by the composition root from the
  /// catalogue's own menu index; the default names nothing and the screen falls
  /// back to a neutral label rather than to a guess.
  final CartItemNaming naming;

  final VoidCallback? onCheckout;
  final VoidCallback? onBrowseMenu;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.cartTitle)),
      body: AnimatedBuilder(
        animation: controller,
        builder: (BuildContext context, _) => _body(context),
      ),
    );
  }

  Widget _body(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final HorecaOSTokens tokens = context.horecaos;
    final Cart? cart = controller.cart;

    if (cart == null) {
      final CartProblem? problem = controller.problem;
      if (problem != null) {
        return Padding(
          padding: const EdgeInsets.all(HorecaOSGeometry.spaceMd),
          child: cartProblemPanel(context, problem),
        );
      }
      return QEmptyState(
        title: l10n.cartEmptyTitle,
        body: l10n.cartEmptyBody,
        actionLabel: onBrowseMenu == null ? null : l10n.catalogueMenuTitle,
        onAction: onBrowseMenu,
      );
    }

    if (cart.isEmpty) {
      return QEmptyState(
        title: l10n.cartEmptyTitle,
        body: l10n.cartEmptyBody,
        actionLabel: onBrowseMenu == null ? null : l10n.catalogueMenuTitle,
        onAction: onBrowseMenu,
      );
    }

    final CartProblem? problem = controller.problem;

    return Column(
      children: <Widget>[
        Expanded(
          child: ListView(
            padding: const EdgeInsets.only(bottom: HorecaOSGeometry.spaceMd),
            children: <Widget>[
              if (problem != null)
                Padding(
                  padding: const EdgeInsets.all(HorecaOSGeometry.spaceMd),
                  child: cartProblemPanel(context, problem),
                ),
              for (final CartLine line in cart.lines) ...<Widget>[
                CartLineTile(
                  line: line,
                  naming: naming,
                  enabled: !controller.isBusy,
                  onQuantityChanged: (int quantity) =>
                      controller.setQuantity(line.lineKey, quantity),
                  onRemove: () => controller.removeLine(line.lineKey),
                ),
                const Divider(),
              ],
              CartTotals(quote: controller.quote),
            ],
          ),
        ),
        _ActionBar(
          controller: controller,
          onCheckout: onCheckout,
          hairline: tokens.hairline,
        ),
      ],
    );
  }
}

/// The one place a [CartProblem] becomes words.
///
/// A function rather than six call sites choosing their own wording, because
/// the same refusal reaching two screens with two different explanations is how
/// a customer concludes the application is guessing.
QProblemPanel cartProblemPanel(
  BuildContext context,
  CartProblem problem, {
  VoidCallback? onRetry,
}) {
  final AppLocalizations l10n = AppLocalizations.of(context);
  final (String title, String body, ProblemTone tone) = switch (problem.kind) {
    CartProblemKind.cartGone => (
      l10n.cartGoneTitle,
      l10n.cartGoneBody,
      ProblemTone.neutral,
    ),
    CartProblemKind.notServiceable => (
      l10n.cartBranchClosedTitle,
      l10n.cartBranchClosedBody,
      ProblemTone.neutral,
    ),
    CartProblemKind.pricingRefused => (
      l10n.cartUnpriceableTitle,
      l10n.cartUnpriceableBody,
      ProblemTone.warning,
    ),
    CartProblemKind.notPermitted => (
      l10n.cartNotPermittedTitle,
      l10n.cartNotPermittedBody,
      ProblemTone.failure,
    ),
    CartProblemKind.offline => (
      l10n.cartOfflineTitle,
      l10n.cartOfflineBody,
      ProblemTone.warning,
    ),
    CartProblemKind.unexpected => (
      l10n.cartFailedTitle,
      l10n.cartFailedBody,
      ProblemTone.failure,
    ),
  };

  return QProblemPanel(
    title: title,
    body: body,
    tone: tone,
    correlationId: problem.correlationId,
    actionLabel: onRetry == null ? null : l10n.retry,
    onAction: onRetry,
  );
}

/// The primary action, over the design system's hairline.
///
/// One button, and which one it is depends on a single question: does the
/// basket hold a quote the platform would still accept. If it does not, pricing
/// it is the next step and checkout is not offered — a checkout screen with no
/// price on it would ask the customer to decide before telling them what they
/// are deciding.
class _ActionBar extends StatelessWidget {
  const _ActionBar({
    required this.controller,
    required this.onCheckout,
    required this.hairline,
  });

  final CartController controller;
  final VoidCallback? onCheckout;
  final Color hairline;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final bool priced = controller.hasUsableQuote;
    final bool enabled = !controller.isBusy && !controller.isEmpty;

    return DecoratedBox(
      decoration: BoxDecoration(
        border: Border(
          top: BorderSide(color: hairline, width: HorecaOSGeometry.hairline),
        ),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.all(HorecaOSGeometry.spaceMd),
          child: SizedBox(
            width: double.infinity,
            child: FilledButton(
              onPressed: !enabled
                  ? null
                  : priced
                  ? onCheckout
                  : controller.refreshPrice,
              child: Text(
                priced ? l10n.cartGoToCheckout : l10n.cartConfirmPrice,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
