import '../../../format/money.dart';
import 'order_codes.dart';
import 'order_json.dart';

/// One row of the customer's order history.
///
/// The member names mirror `OperationsOrderController.OrderSummaryResponse`,
/// which is the only order summary the platform actually renders today. A
/// storefront list has no reason to invent different names for the same facts.
final class OrderSummary {
  const OrderSummary({
    required this.orderId,
    required this.publicOrderNumber,
    required this.status,
    required this.total,
    required this.placedAt,
    this.fulfillmentMode,
    this.promisedAt,
  });

  final String orderId;

  /// What the customer and the branch both call this order out loud.
  ///
  /// Rendered everywhere the order is named. The UUID is never shown: it is the
  /// identifier the API uses and it means nothing to anyone holding a receipt.
  final String publicOrderNumber;

  final OrderStatus status;
  final Money total;

  /// `createdAt` — when the order was placed.
  final DateTime placedAt;

  final FulfillmentMode? fulfillmentMode;

  /// The stored promise (V0023), or null when the order carries none.
  ///
  /// Null is a real state and not an error: `promise_basis = 'NOT_PROMISED'`
  /// pairs with a null `promised_at` by database constraint, and orders that
  /// predate the column were deliberately not backfilled. "We never promised"
  /// and "on time" must not render the same way.
  final DateTime? promisedAt;

  /// Lateness, derived rather than read.
  ///
  /// V0023 is explicit that there is no `late` column and never will be: it is
  /// `promised_at` against the clock on an order that has not finished. A
  /// terminal order is never late — it is done, and the promise stopped being a
  /// commitment when it ended.
  bool isLate(DateTime now) =>
      promisedAt != null && !status.isTerminal && now.isAfter(promisedAt!);

  factory OrderSummary.fromJson(Map<String, Object?> json) {
    final String currency = OrderJson.requireString(json, 'currency');
    return OrderSummary(
      orderId: OrderJson.requireString(json, 'orderId'),
      publicOrderNumber: OrderJson.requireString(json, 'publicOrderNumber'),
      status: OrderStatus.parse(OrderJson.optionalString(json, 'status')),
      total: OrderJson.requireMoney(json, 'total', currency),
      placedAt: OrderJson.requireInstant(json, 'createdAt'),
      fulfillmentMode: FulfillmentMode.parse(
        OrderJson.optionalString(json, 'fulfillmentMode'),
      ),
      promisedAt: OrderJson.optionalInstant(json, 'promisedAt'),
    );
  }
}

/// One snapshotted line of an order.
///
/// The names are the ones the order snapshot stored at checkout, not the
/// catalogue's current ones. That is the property the platform's schema
/// guarantees — "the order says what it said" — and it is why a receipt does
/// not change when a product is renamed.
final class OrderLine {
  const OrderLine({
    required this.lineNumber,
    required this.productName,
    required this.quantity,
    required this.total,
    this.variantName,
    this.unitAmount,
    this.modifiers = const <String>[],
  });

  final int lineNumber;
  final String productName;
  final int quantity;

  /// `finalAmountMinor` — what this line cost after its modifiers.
  final Money total;

  final String? variantName;
  final Money? unitAmount;
  final List<String> modifiers;

  factory OrderLine.fromJson(Map<String, Object?> json, String currency) =>
      OrderLine(
        lineNumber: OrderJson.requireInt(json, 'lineNumber'),
        productName: OrderJson.requireString(json, 'productName'),
        quantity: OrderJson.requireInt(json, 'quantity'),
        // `finalAmountMinor` and `unitAmountMinor`, spelled the way both order
        // controllers already spell them.
        total: OrderJson.requireMoney(json, 'finalAmount', currency),
        variantName: OrderJson.optionalString(json, 'variantName'),
        unitAmount: OrderJson.optionalMoney(json, 'unitAmount', currency),
        modifiers: OrderJson.stringList(json, 'modifiers'),
      );
}

/// The one terminal fact an order ended in (ADR 0039).
///
/// Carries the platform-owned category and the tenant's *customer* wording, and
/// deliberately not the members `OperationsOrderController.OutcomeResponse`
/// also returns: the stock disposition, the liability party, the reason
/// identifier and whether the reservation was committed are internal
/// accounting. Neither does it carry `internal_name` — «Не дозвонились» is what
/// the operator picked from, and the softened text the tenant wrote for the
/// customer is a different string in a different table. Publishing the internal
/// one to a customer is the exact mistake ADR 0039's split exists to prevent,
/// and the way to not make it is to have nowhere to put it.
final class OrderOutcome {
  const OrderOutcome({
    required this.kind,
    this.category,
    this.refund,
    this.occurredAt,
    this.customerText,
  });

  final TerminalOutcomeKind kind;
  final OutcomeCategory? category;

  /// The refund *posture* the reason carries, never a statement that money has
  /// moved. ADR 0013 owns the refund itself.
  final RefundPosture? refund;

  final DateTime? occurredAt;

  /// The tenant's own customer-facing wording, already localised server-side.
  ///
  /// Shown in place of the platform sentence when it is present, because a
  /// tenant that wrote its own explanation wrote a better one than a closed
  /// category can express.
  final String? customerText;

  factory OrderOutcome.fromJson(Map<String, Object?> json) => OrderOutcome(
    kind: TerminalOutcomeKind.parse(OrderJson.optionalString(json, 'kind')),
    category: OutcomeCategory.parse(
      OrderJson.optionalString(json, 'systemCategory'),
    ),
    refund: RefundPosture.parse(
      OrderJson.optionalString(json, 'customerRefund'),
    ),
    occurredAt: OrderJson.optionalInstant(json, 'occurredAt'),
    customerText: OrderJson.optionalString(json, 'reasonCustomerText'),
  );
}

/// How an order is being paid.
///
/// The projection is the platform's closed enumeration and is safe to localise.
/// The method is rendered from a server-supplied display name only: payment
/// method codes are tenant-defined (V0042) and `CLICK_UP` on a receipt is a
/// database row, not the name of anything. When no name arrives, the status
/// sentence carries the meaning on its own.
final class OrderPayment {
  const OrderPayment({required this.status, this.methodName});

  final PaymentStatus status;
  final String? methodName;
}

/// One of the customer's own orders, in full.
final class OrderDetail {
  const OrderDetail({
    required this.orderId,
    required this.publicOrderNumber,
    required this.status,
    required this.total,
    required this.subtotal,
    required this.placedAt,
    required this.version,
    required this.lines,
    this.tax,
    this.discount,
    this.fee,
    this.fulfillmentMode,
    this.promisedAt,
    this.confirmedAt,
    this.closedAt,
    this.payment,
    this.outcome,
    this.courierFirstName,
  });

  final String orderId;
  final String publicOrderNumber;
  final OrderStatus status;

  final Money total;
  final Money subtotal;
  final Money? tax;
  final Money? discount;
  final Money? fee;

  final DateTime placedAt;

  /// The aggregate version from the `ETag`, kept so a later mutation on this
  /// screen can send `If-Match` without re-reading. Nothing on this screen
  /// mutates today.
  final int version;

  final List<OrderLine> lines;

  final FulfillmentMode? fulfillmentMode;
  final DateTime? promisedAt;
  final DateTime? confirmedAt;
  final DateTime? closedAt;
  final OrderPayment? payment;

  /// Present once the order has ended, and only then.
  final OrderOutcome? outcome;

  /// The courier's first name, where the platform published one.
  ///
  /// A first name is the whole of what ADR 0045 permits about a courier: no
  /// position, no phone number, no vehicle, no plate. It is decoded here so the
  /// handover milestone can say who is bringing the food, and there is nowhere
  /// in this model to put anything more.
  final String? courierFirstName;

  bool isLate(DateTime now) =>
      promisedAt != null && !status.isTerminal && now.isAfter(promisedAt!);

  /// The outcome as sent, or the one the terminal status implies.
  ///
  /// A terminal order always has an outcome row on the platform (ADR 0039
  /// writes it in the same transaction as the transition), so the fallback is
  /// reading the same fact from the other column rather than inventing one. The
  /// reason is not derivable and stays absent, which is what makes a screen say
  /// "cancelled" without also making up why.
  OrderOutcome? get effectiveOutcome {
    if (outcome != null) return outcome;
    final TerminalOutcomeKind? implied = TerminalOutcomeKind.forStatus(status);
    return implied == null
        ? null
        : OrderOutcome(kind: implied, occurredAt: closedAt);
  }

  factory OrderDetail.fromJson(Map<String, Object?> json, {int? version}) {
    final String currency = OrderJson.requireString(json, 'currency');
    final Map<String, Object?>? outcomeJson = switch (json['outcome']) {
      final Map<String, Object?> value => value,
      _ => null,
    };
    final String? paymentStatus = OrderJson.optionalString(
      json,
      'paymentStatus',
    );
    final String? paymentMethodName = OrderJson.optionalString(
      json,
      'paymentMethodName',
    );

    return OrderDetail(
      orderId: OrderJson.requireString(json, 'orderId'),
      publicOrderNumber: OrderJson.requireString(json, 'publicOrderNumber'),
      status: OrderStatus.parse(OrderJson.optionalString(json, 'status')),
      total: OrderJson.requireMoney(json, 'total', currency),
      subtotal: OrderJson.requireMoney(json, 'subtotal', currency),
      tax: OrderJson.optionalMoney(json, 'tax', currency),
      discount: OrderJson.optionalMoney(json, 'discount', currency),
      fee: OrderJson.optionalMoney(json, 'fee', currency),
      placedAt: OrderJson.requireInstant(json, 'createdAt'),
      version: version ?? OrderJson.optionalInt(json, 'version') ?? 0,
      lines: OrderJson.objectList(json, 'lines')
          .map((Map<String, Object?> line) => OrderLine.fromJson(line, currency))
          .toList(growable: false),
      fulfillmentMode: FulfillmentMode.parse(
        OrderJson.optionalString(json, 'fulfillmentMode'),
      ),
      promisedAt: OrderJson.optionalInstant(json, 'promisedAt'),
      confirmedAt: OrderJson.optionalInstant(json, 'confirmedAt'),
      closedAt: OrderJson.optionalInstant(json, 'closedAt'),
      payment: paymentStatus == null && paymentMethodName == null
          ? null
          : OrderPayment(
              status: PaymentStatus.parse(paymentStatus),
              methodName: paymentMethodName,
            ),
      outcome: outcomeJson == null ? null : OrderOutcome.fromJson(outcomeJson),
      courierFirstName: OrderJson.optionalString(json, 'courierFirstName'),
    );
  }

  /// The list row for this order, so a detail read can update the list it came
  /// from without a second request.
  OrderSummary toSummary() => OrderSummary(
    orderId: orderId,
    publicOrderNumber: publicOrderNumber,
    status: status,
    total: total,
    placedAt: placedAt,
    fulfillmentMode: fulfillmentMode,
    promisedAt: promisedAt,
  );
}
