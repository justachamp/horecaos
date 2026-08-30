import 'package:flutter/material.dart';

import '../../design/horecaos_theme.dart';
import '../../design/horecaos_tokens.dart';
import '../../l10n/generated/app_localizations.dart';
import '../cart/cart_controller.dart';
import '../cart/cart_models.dart';
import '../cart/money_label.dart';
import '../cart/widgets/cart_totals.dart';
import '../cart/widgets/problem_panel.dart';
import 'checkout_controller.dart';
import 'checkout_models.dart';
import 'widgets/delivery_section.dart';
import 'widgets/order_placed_view.dart';
import 'widgets/price_moved_panel.dart';

/// Fulfilment mode, where it goes, how it is paid for, and the button that
/// creates an order.
class CheckoutPage extends StatelessWidget {
  const CheckoutPage({
    required this.controller,
    required this.cart,
    super.key,
    this.onChooseDestination,
    this.onDone,
  });

  final CheckoutController controller;
  final CartController cart;

  /// Opens the address pin. Null until a map binding exists.
  final VoidCallback? onChooseDestination;

  /// Leaves the flow once there is an order.
  final VoidCallback? onDone;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.checkoutTitle)),
      body: AnimatedBuilder(
        animation: Listenable.merge(<Listenable>[controller, cart]),
        builder: (BuildContext context, _) => switch (controller.stage) {
          final CheckoutPlaced placed => OrderPlacedView(
            order: placed.order,
            paymentStage: controller.paymentStage,
            paymentMethod: controller.paymentMethod,
            onPay: controller.openPayment,
            onDone: onDone ?? () {},
          ),
          _ => _Form(
            controller: controller,
            cart: cart,
            onChooseDestination: onChooseDestination,
          ),
        },
      ),
    );
  }
}

class _Form extends StatelessWidget {
  const _Form({
    required this.controller,
    required this.cart,
    required this.onChooseDestination,
  });

  final CheckoutController controller;
  final CartController cart;
  final VoidCallback? onChooseDestination;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final CheckoutStage stage = controller.stage;

    return Column(
      children: <Widget>[
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(HorecaOSGeometry.spaceMd),
            children: <Widget>[
              if (stage case final CheckoutPriceMoved moved) ...<Widget>[
                PriceMovedPanel(stage: moved, onAccept: controller.place),
                const SizedBox(height: HorecaOSGeometry.spaceMd),
              ],
              if (stage case final CheckoutRefused refused) ...<Widget>[
                _refusalPanel(context, refused, onRetry: controller.place),
                const SizedBox(height: HorecaOSGeometry.spaceMd),
              ],

              Text(l10n.checkoutFulfilmentTitle, style: text.titleSmall),
              const SizedBox(height: HorecaOSGeometry.spaceXs),
              Text(_modeLabel(l10n, controller.fulfilmentMode), style: text.bodyLarge),
              Text(
                // The mode is fixed for the life of a cart: the platform takes
                // it at creation and nothing moves a cart between modes. Saying
                // so beats a control that silently discards the basket.
                l10n.checkoutModeFixedNote,
                style: text.bodySmall?.copyWith(color: tokens.inkMuted),
              ),

              if (controller.isDelivery) ...<Widget>[
                const SizedBox(height: HorecaOSGeometry.spaceLg),
                DeliverySection(
                  controller: controller,
                  onChooseDestination: onChooseDestination,
                ),
              ],

              const SizedBox(height: HorecaOSGeometry.spaceLg),
              Text(l10n.checkoutPaymentTitle, style: text.titleSmall),
              for (final PaymentMethodChoice method
                  in PaymentMethodChoice.values)
                _PaymentOption(
                  method: method,
                  selected: controller.paymentMethod == method,
                  onSelected: () => controller.choosePaymentMethod(method),
                ),

              const SizedBox(height: HorecaOSGeometry.spaceLg),
              CartTotals(quote: _quoteOnScreen),
            ],
          ),
        ),
        _ActionBar(controller: controller, hairline: tokens.hairline),
      ],
    );
  }

  /// The quote the button would send: the accepted replacement after a refusal,
  /// otherwise the basket's own.
  PricedCart? get _quoteOnScreen =>
      switch (controller.stage) {
        CheckoutPriceMoved(newQuote: final PricedCart? quote)
            when quote != null =>
          quote,
        _ => cart.quote,
      };
}

String _modeLabel(AppLocalizations l10n, FulfilmentMode mode) => switch (mode) {
  FulfilmentMode.delivery => l10n.checkoutModeDelivery,
  FulfilmentMode.pickup => l10n.checkoutModePickup,
  FulfilmentMode.dineIn => l10n.checkoutModeDineIn,
};

/// Turns a refusal into the sentence a customer needs.
///
/// Every branch says what happened to the order, because that is the question
/// being asked. A transport failure in particular says the order **may** have
/// been placed — which is true, and which is why the retry is safe.
QProblemPanel _refusalPanel(
  BuildContext context,
  CheckoutRefused refused, {
  required VoidCallback onRetry,
}) {
  final AppLocalizations l10n = AppLocalizations.of(context);

  final (String title, String body, ProblemTone tone, bool retryable) =
      switch (refused.kind) {
        CheckoutRefusalKind.atCapacity => (
          l10n.checkoutCapacityTitle,
          l10n.checkoutCapacityBody,
          ProblemTone.neutral,
          true,
        ),
        CheckoutRefusalKind.itemsUnavailable => (
          l10n.checkoutSoldOutTitle,
          l10n.checkoutSoldOutBody,
          ProblemTone.warning,
          false,
        ),
        CheckoutRefusalKind.paymentMethodUnavailable => (
          l10n.checkoutPaymentUnavailableTitle,
          l10n.checkoutPaymentUnavailableBody,
          ProblemTone.warning,
          false,
        ),
        CheckoutRefusalKind.notServiceable => (
          l10n.cartBranchClosedTitle,
          l10n.cartBranchClosedBody,
          ProblemTone.neutral,
          false,
        ),
        CheckoutRefusalKind.cartGone => (
          l10n.cartGoneTitle,
          l10n.cartGoneBody,
          ProblemTone.neutral,
          false,
        ),
        CheckoutRefusalKind.notPermitted => (
          l10n.checkoutNotPermittedTitle,
          l10n.checkoutNotPermittedBody,
          ProblemTone.failure,
          false,
        ),
        // The one refusal whose recovery is to send the identical request
        // again. The idempotency key has not been thrown away, so the platform
        // either replays the order it created or creates the one it did not.
        CheckoutRefusalKind.offline => (
          l10n.checkoutOfflineTitle,
          l10n.checkoutOfflineBody,
          ProblemTone.warning,
          true,
        ),
        CheckoutRefusalKind.unexpected => (
          l10n.checkoutFailedTitle,
          l10n.checkoutFailedBody,
          ProblemTone.failure,
          true,
        ),
      };

  return QProblemPanel(
    title: title,
    body: body,
    tone: tone,
    correlationId: refused.correlationId,
    actionLabel: retryable ? l10n.retry : null,
    onAction: retryable ? onRetry : null,
  );
}

class _PaymentOption extends StatelessWidget {
  const _PaymentOption({
    required this.method,
    required this.selected,
    required this.onSelected,
  });

  final PaymentMethodChoice method;
  final bool selected;
  final VoidCallback onSelected;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;

    return Semantics(
      inMutuallyExclusiveGroup: true,
      selected: selected,
      button: true,
      child: InkWell(
        onTap: onSelected,
        child: ConstrainedBox(
          // The MOBILE minimum target, on both platforms.
          constraints: BoxConstraints(minHeight: tokens.minTarget),
          child: Row(
            children: <Widget>[
              _SelectionMark(selected: selected),
              const SizedBox(width: HorecaOSGeometry.spaceMd),
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      // The provider's own name is a brand name, in the same
                      // class as the application's title, and is not an ARB
                      // message: "Click" is spelled Click in all three locales.
                      method.providerName ?? l10n.checkoutPaymentCash,
                      style: text.bodyLarge,
                    ),
                    if (method.needsProvider)
                      Text(
                        l10n.checkoutPaymentCard,
                        style: text.bodySmall?.copyWith(color: tokens.inkMuted),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The selected state, as a ring that fills.
///
/// Hand-drawn rather than a `Radio`, whose Material default brings an ink
/// splash and a tinted overlay this design system does not use — and whose
/// current API routes selection through a `RadioGroup` ancestor that would put
/// state above the widget that owns it.
class _SelectionMark extends StatelessWidget {
  const _SelectionMark({required this.selected});

  final bool selected;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    return SizedBox(
      width: HorecaOSGeometry.spaceLg,
      height: HorecaOSGeometry.spaceLg,
      child: DecoratedBox(
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: selected ? tokens.accent : tokens.canvas,
          border: Border.all(
            color: selected ? tokens.accent : tokens.surface2,
            width: HorecaOSGeometry.hairline,
          ),
        ),
      ),
    );
  }
}

/// The total, and the button that agrees to it.
class _ActionBar extends StatelessWidget {
  const _ActionBar({required this.controller, required this.hairline});

  final CheckoutController controller;
  final Color hairline;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final bool placing = controller.stage is CheckoutPlacing;

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
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              if (controller.total case final total?)
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: <Widget>[
                    Text(l10n.checkoutToPay, style: text.bodyMedium),
                    Text(moneyLabel(context, total), style: text.titleSmall),
                  ],
                ),
              const SizedBox(height: HorecaOSGeometry.spaceSm),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  // Disabled while a request is in flight, so a second tap
                  // cannot start a second checkout. The idempotency key would
                  // make that harmless; not sending it at all is better.
                  onPressed: controller.canPlace ? controller.place : null,
                  child: Text(
                    placing ? l10n.checkoutPlacingOrder : l10n.checkoutPlaceOrder,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
