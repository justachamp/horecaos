import 'package:flutter/material.dart';

import '../../../design/horecaos_theme.dart';
import '../../../design/horecaos_tokens.dart';
import '../../../format/horecaos_formats.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../../cart/widgets/problem_panel.dart';
import '../checkout_controller.dart';
import '../checkout_models.dart';

/// The order exists. What is left is the promise, and paying for it.
class OrderPlacedView extends StatelessWidget {
  const OrderPlacedView({
    required this.order,
    required this.paymentStage,
    required this.paymentMethod,
    required this.onPay,
    required this.onDone,
    super.key,
  });

  final PlacedOrder order;
  final PaymentStage paymentStage;
  final PaymentMethodChoice paymentMethod;
  final VoidCallback onPay;
  final VoidCallback onDone;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);
    final String locale = Localizations.localeOf(context).toLanguageTag();

    return ListView(
      padding: const EdgeInsets.all(HorecaOSGeometry.spaceMd),
      children: <Widget>[
        Text(l10n.orderPlacedTitle, style: text.titleLarge),
        const SizedBox(height: HorecaOSGeometry.spaceXs),
        Text(l10n.ordersNumber(order.publicOrderNumber), style: text.bodyLarge),
        const SizedBox(height: HorecaOSGeometry.spaceSm),
        Text(
          _statusLabel(l10n, order.status),
          style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
        ),

        const SizedBox(height: HorecaOSGeometry.spaceMd),
        // The promise, exactly as the platform decided it at checkout. Absent
        // rather than estimated: V0023 stores a promised time that is never
        // recomputed, and a client-side guess printed beside it would be a
        // second number contradicting the one the branch is measured against.
        Text(
          order.promisedAt == null
              ? l10n.orderPromisedNone
              : l10n.orderPromisedBy(
                  HorecaOSFormats.dayMonthTime(
                    HorecaOSFormats.toLocal(order.promisedAt!),
                    locale: locale,
                  ),
                ),
          style: text.bodyMedium,
        ),

        if (order.replayed) ...<Widget>[
          const SizedBox(height: HorecaOSGeometry.spaceMd),
          // A replay is a success. The platform returned the order it had
          // already created for this idempotency key rather than creating a
          // second one, and saying so stops a customer pressing the button
          // again to "make sure".
          QProblemPanel(
            title: l10n.orderPlacedTitle,
            body: l10n.orderAlreadyPlaced,
          ),
        ],

        const SizedBox(height: HorecaOSGeometry.spaceMd),
        _Payment(
          stage: paymentStage,
          method: paymentMethod,
          awaitingPayment: order.status.isAwaitingPayment,
          onPay: onPay,
        ),

        const SizedBox(height: HorecaOSGeometry.spaceLg),
        FilledButton(onPressed: onDone, child: Text(l10n.checkoutDone)),
      ],
    );
  }

  static String _statusLabel(AppLocalizations l10n, OrderStatusCode status) =>
      switch (status.value) {
        'RECEIVED' => l10n.orderStatusReceived,
        'PAYMENT_AUTHORIZING' => l10n.orderStatusPaymentAuthorizing,
        'AWAITING_APPROVAL' => l10n.orderStatusAwaitingApproval,
        'CONFIRMED' => l10n.orderStatusConfirmed,
        // ADR 0031 evolves enums additively and clients are documented to
        // tolerate unknown values. Saying "unavailable" is the tolerance.
        _ => l10n.orderStatusUnknown,
      };
}

/// Paying, or not having to.
class _Payment extends StatelessWidget {
  const _Payment({
    required this.stage,
    required this.method,
    required this.awaitingPayment,
    required this.onPay,
  });

  final PaymentStage stage;
  final PaymentMethodChoice method;
  final bool awaitingPayment;
  final VoidCallback onPay;

  @override
  Widget build(BuildContext context) {
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);

    if (!method.needsProvider) {
      // Cash is a tender, not the absence of one. It has no surface, no
      // redirect, and nothing to wait for.
      return Text(l10n.paymentCashNote, style: text.bodyMedium);
    }

    return switch (stage) {
      PaymentStage.opening => Text(l10n.paymentOpening, style: text.bodyMedium),
      PaymentStage.handedOff => Text(
        l10n.paymentHandedOff,
        style: text.bodyMedium,
      ),
      PaymentStage.alreadyPaid => Text(
        l10n.paymentAlreadyPaid,
        style: text.bodyMedium,
      ),
      PaymentStage.couldNotOpen => QProblemPanel(
        title: l10n.paymentCouldNotOpen,
        body: l10n.checkoutFailedBody,
        tone: ProblemTone.warning,
        actionLabel: l10n.retry,
        onAction: onPay,
      ),
      PaymentStage.unavailable => QProblemPanel(
        title: l10n.paymentSurfaceUnavailable,
        body: l10n.checkoutPaymentUnavailableBody,
        tone: ProblemTone.warning,
      ),
      // No retry offered, and that is the whole point. A surface presented now
      // would be a second charge with no idempotency key anywhere in Click's
      // MERCHANT API to recover from it.
      PaymentStage.outcomeUncertain => QProblemPanel(
        title: l10n.paymentUncertainTitle,
        body: l10n.paymentUncertainBody,
        tone: ProblemTone.failure,
      ),
      PaymentStage.notNeeded when awaitingPayment => FilledButton(
        onPressed: onPay,
        child: Text(l10n.payNow),
      ),
      PaymentStage.notNeeded => Text(
        l10n.paymentCashNote,
        style: text.bodyMedium,
      ),
    };
  }
}
