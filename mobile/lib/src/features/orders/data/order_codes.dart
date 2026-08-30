/// The platform's closed enumerations, decoded so an unknown value cannot
/// crash a screen.
///
/// ADR 0031 evolves a major version additively and permits new enum values
/// "where the client is documented to tolerate unknown values". A Dart `enum`
/// would throw on decoding one, which turns an additive server change into a
/// customer looking at a crash. Each of these is a wrapper over the wire string
/// with named constants for the values the platform has today, in the same
/// shape `ApiErrorCode` already uses in `lib/src/api/problem_details.dart`.
library;

/// The shared identity of a wire code: equal when the type and the string are.
abstract base class WireCode {
  const WireCode(this.value);

  /// The `SCREAMING_SNAKE_CASE` value exactly as the platform sent it.
  ///
  /// Never rendered to a customer. It is a database value, and a screen that
  /// falls back to printing it has shown someone `APPROVAL_DEADLINE_LAPSED`.
  final String value;

  @override
  bool operator ==(Object other) =>
      other is WireCode &&
      other.runtimeType == runtimeType &&
      other.value == value;

  @override
  int get hashCode => Object.hash(runtimeType, value);

  @override
  String toString() => '$runtimeType($value)';
}

/// `uz.qoida.platform.ordering.domain.OrderStatus`, ADR 0019.
final class OrderStatus extends WireCode {
  const OrderStatus(super.value);

  static const OrderStatus received = OrderStatus('RECEIVED');
  static const OrderStatus paymentAuthorizing = OrderStatus(
    'PAYMENT_AUTHORIZING',
  );
  static const OrderStatus awaitingApproval = OrderStatus('AWAITING_APPROVAL');
  static const OrderStatus paymentFailed = OrderStatus('PAYMENT_FAILED');
  static const OrderStatus confirmed = OrderStatus('CONFIRMED');
  static const OrderStatus rejected = OrderStatus('REJECTED');
  static const OrderStatus expired = OrderStatus('EXPIRED');
  static const OrderStatus preparing = OrderStatus('PREPARING');
  static const OrderStatus ready = OrderStatus('READY');
  static const OrderStatus fulfilling = OrderStatus('FULFILLING');
  static const OrderStatus completed = OrderStatus('COMPLETED');
  static const OrderStatus cancelled = OrderStatus('CANCELLED');

  /// Assigned locally when the platform sent a status this build does not know.
  static const OrderStatus unrecognised = OrderStatus('CLIENT_UNRECOGNISED');

  /// The values this build knows, as strings.
  ///
  /// A `Set<OrderStatus>` cannot be `const`: these types override `==` so that
  /// an unknown value decodes instead of crashing, and Dart refuses a constant
  /// set whose elements do not have primitive equality. Holding the wire
  /// strings keeps the table `const` and compares exactly the thing that came
  /// off the wire.
  static const Set<String> knownValues = <String>{
    'RECEIVED',
    'PAYMENT_AUTHORIZING',
    'AWAITING_APPROVAL',
    'PAYMENT_FAILED',
    'CONFIRMED',
    'REJECTED',
    'EXPIRED',
    'PREPARING',
    'READY',
    'FULFILLING',
    'COMPLETED',
    'CANCELLED',
  };

  static OrderStatus parse(String? raw) =>
      raw != null && knownValues.contains(raw)
      ? OrderStatus(raw)
      : unrecognised;

  bool get isKnown => knownValues.contains(value);

  /// The same predicate `OrderStatus.terminal()` carries on the platform.
  ///
  /// An unrecognised status is deliberately **not** terminal: treating an
  /// unknown value as final would stop the live view polling an order that is
  /// still moving, and the customer would watch a frozen screen.
  bool get isTerminal =>
      this == paymentFailed ||
      this == rejected ||
      this == expired ||
      this == completed ||
      this == cancelled;

  /// Whether the restaurant has committed to this order yet.
  bool get isBeforeAcceptance =>
      this == received ||
      this == paymentAuthorizing ||
      this == awaitingApproval;
}

/// `uz.qoida.platform.tenancy.api.FulfillmentMode`.
final class FulfillmentMode extends WireCode {
  const FulfillmentMode(super.value);

  static const FulfillmentMode delivery = FulfillmentMode('DELIVERY');
  static const FulfillmentMode pickup = FulfillmentMode('PICKUP');
  static const FulfillmentMode dineIn = FulfillmentMode('DINE_IN');

  static const Set<String> knownValues = <String>{
    'DELIVERY',
    'PICKUP',
    'DINE_IN',
  };

  /// Null for an absent or unknown mode, which is a real state today: the
  /// storefront order response does not carry the mode at all (see
  /// `orders_repository.dart`). Callers must render something sensible without
  /// it rather than assuming delivery.
  static FulfillmentMode? parse(String? raw) =>
      raw != null && knownValues.contains(raw) ? FulfillmentMode(raw) : null;
}

/// `ordering.orders.payment_status_projection`, ADR 0013 (V0022).
final class PaymentStatus extends WireCode {
  const PaymentStatus(super.value);

  /// No online payment is expected: the order is paid at handover.
  static const PaymentStatus notRequired = PaymentStatus('NOT_REQUIRED');
  static const PaymentStatus pending = PaymentStatus('PENDING');
  static const PaymentStatus authorized = PaymentStatus('AUTHORIZED');
  static const PaymentStatus captured = PaymentStatus('CAPTURED');
  static const PaymentStatus failed = PaymentStatus('FAILED');
  static const PaymentStatus voided = PaymentStatus('VOIDED');
  static const PaymentStatus refunded = PaymentStatus('REFUNDED');

  static const PaymentStatus unrecognised = PaymentStatus('CLIENT_UNRECOGNISED');

  static const Set<String> knownValues = <String>{
    'NOT_REQUIRED',
    'PENDING',
    'AUTHORIZED',
    'CAPTURED',
    'FAILED',
    'VOIDED',
    'REFUNDED',
  };

  static PaymentStatus parse(String? raw) =>
      raw != null && knownValues.contains(raw)
      ? PaymentStatus(raw)
      : unrecognised;
}

/// `uz.qoida.platform.ordering.domain.TerminalOutcomeKind`, ADR 0039.
///
/// The whole point of the enum on the platform is that a rejection, an expiry
/// and a cancellation are three different commercial facts rather than one
/// status with a free-text reason. This surface keeps them apart for the same
/// reason: a customer is owed the difference between "the restaurant declined"
/// and "nobody looked at it in time".
final class TerminalOutcomeKind extends WireCode {
  const TerminalOutcomeKind(super.value);

  static const TerminalOutcomeKind completed = TerminalOutcomeKind('COMPLETED');
  static const TerminalOutcomeKind cancelled = TerminalOutcomeKind('CANCELLED');
  static const TerminalOutcomeKind rejected = TerminalOutcomeKind('REJECTED');
  static const TerminalOutcomeKind expired = TerminalOutcomeKind('EXPIRED');
  static const TerminalOutcomeKind paymentFailed = TerminalOutcomeKind(
    'PAYMENT_FAILED',
  );

  static const TerminalOutcomeKind unrecognised = TerminalOutcomeKind(
    'CLIENT_UNRECOGNISED',
  );

  static const Set<String> knownValues = <String>{
    'COMPLETED',
    'CANCELLED',
    'REJECTED',
    'EXPIRED',
    'PAYMENT_FAILED',
  };

  static TerminalOutcomeKind parse(String? raw) =>
      raw != null && knownValues.contains(raw)
      ? TerminalOutcomeKind(raw)
      : unrecognised;

  /// The kind implied by a terminal status, for an order whose outcome row the
  /// server did not send.
  ///
  /// The status and the outcome cannot disagree — ADR 0039 writes the outcome
  /// in the same transaction as the transition — so deriving the kind from the
  /// status is reading the same fact, not guessing. The *reason* is not
  /// derivable and stays absent.
  static TerminalOutcomeKind? forStatus(OrderStatus status) {
    if (!status.isTerminal) return null;
    return parse(status.value);
  }
}

/// `uz.qoida.platform.ordering.domain.OutcomeSystemCategory`, ADR 0039.
///
/// Platform-owned and closed, which is exactly why this screen renders from it
/// rather than from the tenant's own reason registry: the registry is fifty
/// near-duplicate rows per tenant and nothing in a client can localise it.
final class OutcomeCategory extends WireCode {
  const OutcomeCategory(super.value);

  static const OutcomeCategory customerCancelled = OutcomeCategory(
    'CUSTOMER_CANCELLED',
  );
  static const OutcomeCategory customerUnreachable = OutcomeCategory(
    'CUSTOMER_UNREACHABLE',
  );
  static const OutcomeCategory customerNoShow = OutcomeCategory(
    'CUSTOMER_NO_SHOW',
  );
  static const OutcomeCategory restaurantRefused = OutcomeCategory(
    'RESTAURANT_REFUSED',
  );
  static const OutcomeCategory itemUnavailable = OutcomeCategory(
    'ITEM_UNAVAILABLE',
  );
  static const OutcomeCategory kitchenCapacity = OutcomeCategory(
    'KITCHEN_CAPACITY',
  );
  static const OutcomeCategory deliveryFailed = OutcomeCategory(
    'DELIVERY_FAILED',
  );
  static const OutcomeCategory courierUnavailable = OutcomeCategory(
    'COURIER_UNAVAILABLE',
  );
  static const OutcomeCategory addressUnserviceable = OutcomeCategory(
    'ADDRESS_UNSERVICEABLE',
  );
  static const OutcomeCategory paymentNotReceived = OutcomeCategory(
    'PAYMENT_NOT_RECEIVED',
  );
  static const OutcomeCategory duplicateOrder = OutcomeCategory(
    'DUPLICATE_ORDER',
  );
  static const OutcomeCategory testOrder = OutcomeCategory('TEST_ORDER');
  static const OutcomeCategory suspectedFraud = OutcomeCategory(
    'SUSPECTED_FRAUD',
  );
  static const OutcomeCategory pricingError = OutcomeCategory('PRICING_ERROR');
  static const OutcomeCategory deliveredOwnCourier = OutcomeCategory(
    'DELIVERED_OWN_COURIER',
  );
  static const OutcomeCategory deliveredPartnerCourier = OutcomeCategory(
    'DELIVERED_PARTNER_COURIER',
  );
  static const OutcomeCategory collectedByCustomer = OutcomeCategory(
    'COLLECTED_BY_CUSTOMER',
  );
  static const OutcomeCategory servedInHouse = OutcomeCategory(
    'SERVED_IN_HOUSE',
  );
  static const OutcomeCategory approvalDeadlineLapsed = OutcomeCategory(
    'APPROVAL_DEADLINE_LAPSED',
  );
  static const OutcomeCategory other = OutcomeCategory('OTHER');

  static OutcomeCategory? parse(String? raw) =>
      raw == null ? null : OutcomeCategory(raw);
}

/// `uz.qoida.platform.ordering.domain.CustomerRefund`, ADR 0039.
///
/// A **posture**, not a refund. ADR 0013 owns the money; this is what the
/// reason says should happen, and the wording on the screen has to keep that
/// difference visible rather than telling a customer they have been paid.
final class RefundPosture extends WireCode {
  const RefundPosture(super.value);

  static const RefundPosture full = RefundPosture('FULL');
  static const RefundPosture none = RefundPosture('NONE');
  static const RefundPosture discretionary = RefundPosture('DISCRETIONARY');

  static const Set<String> knownValues = <String>{
    'FULL',
    'NONE',
    'DISCRETIONARY',
  };

  static RefundPosture? parse(String? raw) =>
      raw != null && knownValues.contains(raw) ? RefundPosture(raw) : null;
}
