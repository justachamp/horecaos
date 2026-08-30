import 'package:flutter/widgets.dart';

import '../../../api/api_exception.dart';
import '../../../format/money.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../application/order_milestones.dart';
import '../data/order_codes.dart';
import '../data/order_models.dart';

/// Every wire value these screens render, turned into the customer's language.
///
/// One file, because the mapping is the part that must not drift: a status
/// spelled one way on the list and another on the detail is how the archived
/// application ended up with three names for `FULFILLING`.
///
/// The rules it enforces:
///
/// * A value this build does not recognise renders as "unavailable", never as
///   the wire string. `APPROVAL_DEADLINE_LAPSED` is a database value, and a
///   screen that prints it has shown a customer the schema.
/// * A tenant's internal reason wording never appears. ADR 0039 splits
///   `internal_name` from `customer_text` precisely so that «Не дозвонились»
///   stays in the operator's list, and this surface has no field to put it in.
abstract final class OrderStrings {
  /// The status pill's text.
  static String status(AppLocalizations l10n, OrderStatus status) =>
      switch (status.value) {
        'RECEIVED' => l10n.orderStatusReceived,
        'PAYMENT_AUTHORIZING' => l10n.orderStatusPaymentAuthorizing,
        'AWAITING_APPROVAL' => l10n.orderStatusAwaitingApproval,
        'PAYMENT_FAILED' => l10n.orderStatusPaymentFailed,
        'CONFIRMED' => l10n.orderStatusConfirmed,
        'REJECTED' => l10n.orderStatusRejected,
        'EXPIRED' => l10n.orderStatusExpired,
        'PREPARING' => l10n.orderStatusPreparing,
        'READY' => l10n.orderStatusReady,
        'FULFILLING' => l10n.orderStatusFulfilling,
        'COMPLETED' => l10n.orderStatusCompleted,
        'CANCELLED' => l10n.orderStatusCancelled,
        _ => l10n.orderStatusUnknown,
      };

  /// A milestone's label, which depends on the fulfilment mode for two of the
  /// five steps: "ready to collect" and "waiting for a courier" are the same
  /// `READY`, and a customer standing in the branch needs the first one.
  ///
  /// [courierFirstName] is used only on the handover step, and only if the
  /// platform published one. Nothing else about a courier exists in this model
  /// to render (ADR 0045).
  static String milestone(
    AppLocalizations l10n,
    OrderMilestone milestone,
    FulfillmentMode? mode, {
    String? courierFirstName,
  }) => switch (milestone) {
    OrderMilestone.accepted => l10n.orderMilestoneAccepted,
    OrderMilestone.preparing => l10n.orderMilestonePreparing,
    OrderMilestone.ready => switch (mode?.value) {
      'DELIVERY' => l10n.orderMilestoneReadyDelivery,
      'PICKUP' || 'DINE_IN' => l10n.orderMilestoneReadyPickup,
      // Mode unknown: say the neutral thing rather than guess which branch of
      // the sentence applies.
      _ => l10n.orderStatusReady,
    },
    OrderMilestone.onTheWay =>
      courierFirstName == null || courierFirstName.isEmpty
          ? l10n.orderMilestoneOnTheWay
          : l10n.orderMilestoneOnTheWayWith(courierFirstName),
    OrderMilestone.handedOver => switch (mode?.value) {
      'DELIVERY' => l10n.orderMilestoneDelivered,
      'PICKUP' => l10n.orderMilestoneCollected,
      'DINE_IN' => l10n.orderMilestoneServed,
      _ => l10n.orderStatusCompleted,
    },
  };

  /// The payment sentence, from the closed `payment_status_projection` set.
  static String payment(AppLocalizations l10n, PaymentStatus status) =>
      switch (status.value) {
        'NOT_REQUIRED' => l10n.orderPaymentNotRequired,
        'PENDING' => l10n.orderPaymentPending,
        'AUTHORIZED' => l10n.orderPaymentAuthorized,
        'CAPTURED' => l10n.orderPaymentCaptured,
        'FAILED' => l10n.orderStatusPaymentFailed,
        'VOIDED' => l10n.orderPaymentVoided,
        'REFUNDED' => l10n.orderPaymentRefunded,
        _ => l10n.orderPaymentUnknown,
      };

  /// The outcome panel's title — three different facts, three different
  /// sentences (ADR 0039).
  static String outcomeTitle(AppLocalizations l10n, TerminalOutcomeKind kind) =>
      switch (kind.value) {
        'CANCELLED' => l10n.orderOutcomeCancelledTitle,
        'REJECTED' => l10n.orderOutcomeRejectedTitle,
        'EXPIRED' => l10n.orderOutcomeExpiredTitle,
        'PAYMENT_FAILED' => l10n.orderOutcomePaymentFailedTitle,
        // COMPLETED never reaches the outcome panel, and an unrecognised kind
        // is described in the mildest true terms rather than guessed at.
        _ => l10n.orderStatusCompleted,
      };

  /// Why it ended, in the customer's language.
  ///
  /// The tenant's own customer wording wins when it is present: a tenant that
  /// wrote an explanation wrote a better one than a closed category can. When
  /// it is absent, the platform category maps onto one of eight sentences.
  ///
  /// Four categories deliberately land on "no further detail":
  /// `DUPLICATE_ORDER`, `TEST_ORDER`, `SUSPECTED_FRAUD` and `PRICING_ERROR`.
  /// Telling a customer their order was cancelled because fraud was suspected
  /// is a legal and commercial decision that belongs to a person, not to a
  /// switch statement in a phone application.
  static String? outcomeReason(AppLocalizations l10n, OrderOutcome outcome) {
    final String? tenantText = outcome.customerText;
    if (tenantText != null && tenantText.trim().isNotEmpty) {
      return tenantText;
    }
    return switch (outcome.category?.value) {
      'CUSTOMER_CANCELLED' => l10n.orderReasonCustomerCancelled,
      'CUSTOMER_UNREACHABLE' || 'CUSTOMER_NO_SHOW' => l10n.orderReasonUnreachable,
      'RESTAURANT_REFUSED' || 'KITCHEN_CAPACITY' => l10n.orderReasonRestaurant,
      'ITEM_UNAVAILABLE' => l10n.orderReasonItemUnavailable,
      'DELIVERY_FAILED' ||
      'COURIER_UNAVAILABLE' ||
      'ADDRESS_UNSERVICEABLE' => l10n.orderReasonDelivery,
      'PAYMENT_NOT_RECEIVED' => l10n.orderReasonPaymentNotReceived,
      'APPROVAL_DEADLINE_LAPSED' => l10n.orderReasonDeadline,
      'DUPLICATE_ORDER' ||
      'TEST_ORDER' ||
      'SUSPECTED_FRAUD' ||
      'PRICING_ERROR' ||
      'OTHER' => l10n.orderReasonUnspecified,
      // No category at all — an outcome derived from the status alone. Nothing
      // is known about why, so nothing is said about why.
      _ => null,
    };
  }

  /// The refund posture, worded as an expectation rather than a receipt.
  static String? refund(AppLocalizations l10n, RefundPosture? posture) =>
      switch (posture?.value) {
        'FULL' => l10n.orderRefundFull,
        'NONE' => l10n.orderRefundNone,
        'DISCRETIONARY' => l10n.orderRefundDiscretionary,
        _ => null,
      };

  /// What to put on an empty state for a failure.
  ///
  /// A transport failure is the only one the customer can do anything about, so
  /// it is the only one told apart. Everything else — a 403 from the ADR 0025
  /// gap, a 500, a gateway page — is one honest sentence. In particular the
  /// missing capability is never named: capabilities are a platform concept,
  /// and "you lack order.read at BRAND scope" is not a thing to say to someone
  /// looking for their dinner.
  static ({String title, String body}) failure(
    AppLocalizations l10n,
    ApiFailure failure,
  ) {
    if (failure is ApiTransportException) {
      return (title: l10n.ordersOfflineTitle, body: l10n.ordersOfflineBody);
    }
    if (failure is ApiException && failure.isNotFound) {
      return (title: l10n.orderNotFoundTitle, body: l10n.orderNotFoundBody);
    }
    return (
      title: l10n.ordersUnavailableTitle,
      body: l10n.ordersUnavailableBody,
    );
  }

  /// An amount, through the one formatter this application has.
  ///
  /// Never `NumberFormat.currency`, which asks ICU for the number of decimal
  /// places and is told two for UZS — the platform's minor unit is the whole
  /// som, so that answer divides every price by a hundred. That bug shipped
  /// here once already.
  static String money(BuildContext context, Money amount) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final String locale = Localizations.localeOf(context).toLanguageTag();
    final String symbol = amount.currency == 'UZS'
        ? l10n.currencySymbolUzs
        // Any other currency renders with its ISO code. There is no localised
        // marker for one, and inventing a symbol is worse than showing the code.
        : amount.currency;
    return MoneyFormat.withSymbol(amount, symbol, locale: locale);
  }
}
