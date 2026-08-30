import 'package:flutter/foundation.dart';

import '../../api/api_exception.dart';
import '../../api/idempotency_key.dart';
import '../../api/problem_details.dart';
import '../../format/money.dart';
import '../cart/cart_controller.dart';
import '../cart/cart_models.dart';
import 'checkout_models.dart';
import 'checkout_repository.dart';
import 'payment_launcher.dart';

/// Where the checkout is.
sealed class CheckoutStage {
  const CheckoutStage();
}

/// Choosing a branch, a payment method, and where it goes.
final class CheckoutEditing extends CheckoutStage {
  const CheckoutEditing();
}

/// The request is in flight. No second one may start.
final class CheckoutPlacing extends CheckoutStage {
  const CheckoutPlacing();
}

/// The quote the customer was shown is no longer one the platform will accept.
///
/// **A first-class outcome, not an error.** ADR 0018 issues a quote against a
/// context hash and checkout accepts that hash and no other; when the menu is
/// republished or a price moves, refusing is the system working. What the
/// customer needs is the new price and a decision, so this stage carries both
/// halves: what they were told, and — once the basket has been priced again —
/// what it costs now.
final class CheckoutPriceMoved extends CheckoutStage {
  const CheckoutPriceMoved({
    required this.reason,
    required this.previousTotal,
    this.newQuote,
    this.repricingFailed,
  });

  final StaleQuoteReason reason;

  /// The total on screen when the customer pressed the button.
  final Money previousTotal;

  /// The re-priced basket, once it has arrived. Null while the new price is
  /// still being fetched — and the button that places the order does not exist
  /// until it is non-null, because there would be nothing to agree to.
  final PricedCart? newQuote;

  /// Set when the basket could not be priced again at all — every item sold
  /// out, the branch closed. The customer is not left staring at a spinner.
  final CheckoutRefusalKind? repricingFailed;

  bool get awaitingConfirmation => newQuote != null;

  /// Whether the number actually moved.
  ///
  /// Often it has not: a republished menu invalidates a hash without changing a
  /// single price. Saying "the total is the same" is a better answer than
  /// showing an identical figure twice and leaving the customer to compare.
  bool get totalUnchanged =>
      newQuote != null && newQuote!.total == previousTotal;

  CheckoutPriceMoved copyWith({
    PricedCart? newQuote,
    CheckoutRefusalKind? repricingFailed,
  }) => CheckoutPriceMoved(
    reason: reason,
    previousTotal: previousTotal,
    newQuote: newQuote ?? this.newQuote,
    repricingFailed: repricingFailed ?? this.repricingFailed,
  );
}

/// The order exists.
final class CheckoutPlaced extends CheckoutStage {
  const CheckoutPlaced(this.order);

  final PlacedOrder order;
}

/// The platform said no, for a reason that is not about the price.
final class CheckoutRefused extends CheckoutStage {
  const CheckoutRefused({
    required this.kind,
    this.reason,
    this.correlationId,
    this.unavailableItems = const <String>[],
  });

  final CheckoutRefusalKind kind;

  /// The platform's own stable code, for support and for choosing wording.
  final String? reason;

  final String? correlationId;
  final List<String> unavailableItems;
}

/// What has happened about paying.
enum PaymentStage {
  /// Cash, or an order that is not waiting on money.
  notNeeded,

  opening,

  /// The customer was sent to the provider. Whether they paid is decided by the
  /// provider's callback and never by this application: a browser coming back
  /// proves nothing on either provider.
  handedOff,

  /// The link could not be opened on this device.
  couldNotOpen,

  /// The platform has no surface to give: no merchant account, no adapter, no
  /// intent. The order is safe; the payment is not startable here.
  unavailable,

  /// The provider did not answer and it is unknown whether the customer was
  /// charged. **Never retried.** A second attempt is a second charge with
  /// nothing to key it on.
  outcomeUncertain,

  alreadyPaid,
}

/// Fulfilment mode, branch, payment method, and placing the order.
///
/// The controller holds no arithmetic and no price. It holds the customer's
/// choices, the platform's answers, and the one piece of state that stops a
/// duplicate order: the idempotency key.
class CheckoutController extends ChangeNotifier {
  CheckoutController({
    required this._cart,
    required this._repository,
    required this._fulfilmentMode,
    this._launcher = const UnwiredPaymentLauncher(),
    this._paymentMethod = PaymentMethodChoice.cash,
    DateTime Function()? now,
  }) : _now = now ?? DateTime.now;

  final CartController _cart;
  final CheckoutRepository _repository;
  final PaymentLauncher _launcher;
  final DateTime Function() _now;

  /// Fixed when the basket was opened.
  ///
  /// Held here because `CartResponse` does not echo it — the platform stores the
  /// mode on the cart row and does not publish it back — so a cart read cold
  /// after a restart cannot tell this application whether it is a delivery
  /// order. Passed in by whoever opened the cart, and recorded as a contract
  /// gap rather than guessed from anything on screen.
  final FulfilmentMode _fulfilmentMode;

  PaymentMethodChoice _paymentMethod;
  CheckoutStage _stage = const CheckoutEditing();
  PaymentStage _paymentStage = PaymentStage.notNeeded;
  PaymentSession? _paymentSession;
  GeoPoint? _destination;
  DeliveryFeeQuote? _deliveryFee;
  bool _resolvingFee = false;

  /// The key for this customer's intent to place this order.
  ///
  /// One key for one intent, and it survives every retry of that intent —
  /// including the retry the API client itself performs after a token refresh,
  /// and the retry a customer makes after the connection dropped. It is thrown
  /// away only when the request body genuinely changes, which happens exactly
  /// once: when a new quote is adopted after a refusal. Reusing it across two
  /// different bodies is what the platform answers `IDEMPOTENCY_KEY_REUSED` to,
  /// and it is a client bug rather than a retry.
  IdempotencyKey? _orderKey;

  /// One key per order for the payment attempt, so coming back to an abandoned
  /// checkout re-presents the same attempt rather than opening a second.
  IdempotencyKey? _paymentKey;

  CheckoutStage get stage => _stage;
  PaymentStage get paymentStage => _paymentStage;
  PaymentSession? get paymentSession => _paymentSession;
  FulfilmentMode get fulfilmentMode => _fulfilmentMode;
  PaymentMethodChoice get paymentMethod => _paymentMethod;
  GeoPoint? get destination => _destination;
  DeliveryFeeQuote? get deliveryFee => _deliveryFee;
  bool get isResolvingDeliveryFee => _resolvingFee;

  /// The total the customer is agreeing to, and the only total on the screen.
  Money? get total => switch (_stage) {
    CheckoutPriceMoved(newQuote: final PricedCart? quote)
        when quote != null =>
      quote.total,
    _ => _cart.quote?.total,
  };

  bool get isDelivery => _fulfilmentMode == FulfilmentMode.delivery;

  /// Whether the button that places the order should be live.
  ///
  /// A quote that this client can already see has lapsed is not offered to the
  /// platform: the answer would be the same refusal, one round trip later.
  bool get canPlace {
    if (_stage is CheckoutPlacing || _stage is CheckoutPlaced) return false;
    if (_stage case final CheckoutPriceMoved moved) {
      return moved.awaitingConfirmation;
    }
    return _cart.hasUsableQuote;
  }

  void choosePaymentMethod(PaymentMethodChoice method) {
    if (_paymentMethod == method) return;
    _paymentMethod = method;
    // The method is part of the checkout body, so a key minted for the previous
    // choice describes a request that is no longer the one being made.
    _orderKey = null;
    notifyListeners();
  }

  /// Sets where a delivery order goes, and asks the resolver about it.
  ///
  /// The point is used for two questions and is sent nowhere else: does this
  /// branch deliver here, and what does the branch charge. **Nothing in this
  /// release stores a delivery address** — `V0022` says so in as many words, the
  /// cart carries no address column, and `CheckoutRequest` has no address member
  /// — so this is a serviceability check and not an address book.
  Future<void> setDestination(GeoPoint point) async {
    _destination = point;
    _deliveryFee = null;
    _resolvingFee = true;
    notifyListeners();
    try {
      _deliveryFee = await _repository.deliveryFee(
        destination: point,
        currency: _cart.cart?.currency ?? 'UZS',
        // The resolver compares the basket against the zone's minimum and its
        // free-delivery threshold, so it needs the goods subtotal. The server's
        // own subtotal, never a figure assembled here.
        subtotalMinor: _cart.quote?.subtotal.amountMinor ?? 0,
      );
    } catch (_) {
      // A fee that cannot be resolved leaves the row empty rather than showing
      // a number. There is no fallback fee, because the only correct source for
      // one is the resolver that just failed to answer.
      _deliveryFee = null;
    } finally {
      _resolvingFee = false;
      notifyListeners();
    }
  }

  /// How much more the basket needs before this zone will deliver.
  ///
  /// A comparison of two figures the resolver published, not a calculation of a
  /// fee. Null when there is no minimum, or when the basket already clears it.
  Money? get deliveryShortfall {
    final Money? minimum = _deliveryFee?.minimumBasket;
    final Money? subtotal = _cart.quote?.subtotal;
    if (minimum == null || subtotal == null) return null;
    if (minimum.currency != subtotal.currency) return null;
    if (subtotal.compareTo(minimum) >= 0) return null;
    return minimum - subtotal;
  }

  /// Places the order.
  ///
  /// Idempotent from end to end: the same key goes out on every attempt at this
  /// intent, so a dropped connection, a token refresh mid-flight, or a customer
  /// pressing the button twice all converge on one order.
  Future<void> place() async {
    if (_stage is CheckoutPlacing || _stage is CheckoutPlaced) return;

    final Cart? cart = _cart.cart;
    final PricedCart? quote = _quoteToPresent();
    if (cart == null || quote == null) {
      _stage = const CheckoutRefused(kind: CheckoutRefusalKind.cartGone);
      notifyListeners();
      return;
    }

    // Expiry this client can already see is the same event as expiry the server
    // reports, and it is handled the same way: the customer is told, the basket
    // is priced again, and they agree to the new number. Sending a quote that
    // has visibly lapsed would only move the refusal one round trip later.
    if (quote.isExpiredAt(_now()) && _stage is! CheckoutPriceMoved) {
      _enterPriceMoved(StaleQuoteReason.quoteExpired, quote.total);
      await reprice();
      return;
    }

    _orderKey ??= IdempotencyKey.generate();
    _stage = const CheckoutPlacing();
    notifyListeners();

    try {
      final PlacedOrder order = await _repository.placeOrder(
        cartId: cart.cartId,
        cartVersion: quote.cartVersion,
        quoteId: quote.quoteId,
        contextHash: quote.contextHash,
        paymentMethod: _paymentMethod,
        idempotencyKey: _orderKey!,
      );

      // A replay is a success. The platform returned the order it had already
      // created for this key rather than creating a second one, which is
      // exactly what the key is for.
      _stage = CheckoutPlaced(await _withPromise(order));
      _cart.clearAfterCheckout();
      notifyListeners();

      if (_paymentMethod.needsProvider && order.status.isAwaitingPayment) {
        await openPayment();
      }
    } on ApiTransportException catch (failure) {
      // The key is kept. The server's state is unknown, and repeating the same
      // request with the same key is the only safe recovery — either it replays
      // the order that was created, or it creates the one that was not.
      _stage = CheckoutRefused(
        kind: CheckoutRefusalKind.offline,
        correlationId: failure.correlationId,
      );
      notifyListeners();
    } on ApiException catch (failure) {
      await _handlePlacementFailure(failure, quote);
    }
  }

  /// Prices the basket again after a refusal, and holds the new quote for the
  /// customer to accept.
  Future<void> reprice() async {
    final CheckoutStage current = _stage;
    if (current is! CheckoutPriceMoved) return;

    final PricedCart? repriced = await _cart.refreshPrice();
    if (repriced == null) {
      _stage = current.copyWith(
        repricingFailed: _refusalFromCart(_cart.problem),
      );
      notifyListeners();
      return;
    }

    // The body of the next checkout now names a different quote, a different
    // hash and a different cart version, so it is a different request and must
    // not travel under the old key. The platform would answer
    // `IDEMPOTENCY_KEY_REUSED`, and it would be right to.
    _orderKey = null;
    _stage = current.copyWith(newQuote: repriced);
    notifyListeners();
  }

  /// Opens the provider's checkout surface for an order that is waiting on
  /// money (ADR 0013).
  Future<void> openPayment() async {
    final CheckoutStage current = _stage;
    if (current is! CheckoutPlaced) return;
    if (_paymentStage == PaymentStage.outcomeUncertain) {
      // The platform's instruction, and it is not advisory: a surface presented
      // now would be a retry of a charge that may already have happened.
      return;
    }

    _paymentKey ??= IdempotencyKey.generate();
    _paymentStage = PaymentStage.opening;
    notifyListeners();

    try {
      final PaymentSession session = await _repository.openPaymentSession(
        orderId: current.order.orderId,
        idempotencyKey: _paymentKey!,
      );
      _paymentSession = session;

      if (!session.hasSurface) {
        // A push has already reached the customer's phone and there is nothing
        // to open; anything else with no URL is a platform gap.
        _paymentStage = session.isPush
            ? PaymentStage.handedOff
            : PaymentStage.unavailable;
      } else {
        final bool opened = await _launcher.open(session.checkoutUrl!);
        _paymentStage = opened
            ? PaymentStage.handedOff
            : PaymentStage.couldNotOpen;
      }
    } on ApiTransportException {
      _paymentStage = PaymentStage.couldNotOpen;
    } on ApiException catch (failure) {
      _paymentStage = switch (failure.problem.reason) {
        'ALREADY_PAID' => PaymentStage.alreadyPaid,
        'PAYMENT_OUTCOME_UNCERTAIN' => PaymentStage.outcomeUncertain,
        _ => PaymentStage.unavailable,
      };
    } finally {
      notifyListeners();
    }
  }

  // ---------------------------------------------------------------- internals

  /// The quote that would be sent: the accepted replacement if there is one,
  /// otherwise the cart's own.
  PricedCart? _quoteToPresent() {
    final CheckoutStage current = _stage;
    if (current is CheckoutPriceMoved && current.newQuote != null) {
      return current.newQuote;
    }
    return _cart.quote;
  }

  void _enterPriceMoved(StaleQuoteReason reason, Money previousTotal) {
    _orderKey = null;
    _stage = CheckoutPriceMoved(reason: reason, previousTotal: previousTotal);
    notifyListeners();
  }

  Future<void> _handlePlacementFailure(
    ApiException failure,
    PricedCart presented,
  ) async {
    final ProblemDetails problem = failure.problem;
    final String? reason = problem.reason;

    if (failure.isForbidden) {
      _stage = CheckoutRefused(
        kind: CheckoutRefusalKind.notPermitted,
        reason: problem.requiredCapability ?? reason,
        correlationId: problem.correlationId,
      );
      notifyListeners();
      return;
    }

    final StaleQuoteReason? stale = _staleReason(failure);
    if (stale != null) {
      _enterPriceMoved(stale, presented.total);
      // Priced again straight away rather than behind a second tap. The
      // customer has already asked to buy this basket; what they are missing is
      // the number, and making them press "show me" first adds a step to a path
      // that is already an interruption.
      await reprice();
      return;
    }

    final List<String> unavailable = <String>[
      for (final Object? item
          in (problem.extensions['unavailableItems'] as List<Object?>? ??
              const <Object?>[]))
        if (item != null) item.toString(),
    ];

    final CheckoutRefusalKind kind = switch (reason) {
      'AT_CAPACITY' => CheckoutRefusalKind.atCapacity,
      'NOT_SERVICEABLE' ||
      'CHANNEL_NOT_SELLABLE' ||
      'GUEST_ORDERS_NOT_ALLOWED' => CheckoutRefusalKind.notServiceable,
      'PAYMENT_METHOD_UNAVAILABLE' =>
        CheckoutRefusalKind.paymentMethodUnavailable,
      'CART_EXPIRED' ||
      'CART_NOT_ACTIVE' ||
      'CART_NOT_FOUND' ||
      'CART_EMPTY' => CheckoutRefusalKind.cartGone,
      _ when unavailable.isNotEmpty => CheckoutRefusalKind.itemsUnavailable,
      _ => CheckoutRefusalKind.unexpected,
    };

    if (problem.code == ApiErrorCode.idempotencyKeyReused) {
      // This key was committed to a different body. Nothing can be recovered by
      // repeating it, so the next attempt is a new intent with a new key.
      _orderKey = null;
    }

    _stage = CheckoutRefused(
      kind: kind,
      reason: reason,
      correlationId: problem.correlationId,
      unavailableItems: unavailable,
    );
    notifyListeners();
  }

  /// Whether this refusal means "the price you were shown is no longer valid".
  ///
  /// Four platform codes say that, for four different reasons, and the customer
  /// is told which. `QUOTE_NOT_BOUND_TO_CART` and `CART_VERSION_STALE` are
  /// grouped because both mean the basket moved — typically on the customer's
  /// other device — and both are answered by pricing it again.
  static StaleQuoteReason? _staleReason(ApiException failure) {
    if (failure.code == ApiErrorCode.priceChanged) {
      return StaleQuoteReason.priceChanged;
    }
    return switch (failure.problem.reason) {
      'PRICE_CHANGED' => StaleQuoteReason.priceChanged,
      'QUOTE_EXPIRED' || 'QUOTE_NOT_FOUND' => StaleQuoteReason.quoteExpired,
      'PUBLICATION_CHANGED' => StaleQuoteReason.menuRepublished,
      'QUOTE_NOT_BOUND_TO_CART' ||
      'QUOTE_SCOPE_MISMATCH' ||
      'CART_VERSION_STALE' => StaleQuoteReason.basketChanged,
      _ =>
        failure.isStaleVersion ? StaleQuoteReason.basketChanged : null,
    };
  }

  static CheckoutRefusalKind _refusalFromCart(CartProblem? problem) =>
      switch (problem?.kind) {
        CartProblemKind.notServiceable => CheckoutRefusalKind.notServiceable,
        CartProblemKind.cartGone => CheckoutRefusalKind.cartGone,
        CartProblemKind.notPermitted => CheckoutRefusalKind.notPermitted,
        CartProblemKind.offline => CheckoutRefusalKind.offline,
        CartProblemKind.pricingRefused =>
          CheckoutRefusalKind.itemsUnavailable,
        _ => CheckoutRefusalKind.unexpected,
      };

  /// Fills in the promised time, if the platform will say it.
  ///
  /// Best-effort and deliberately silent on failure: the confirmation screen is
  /// correct without a promise, and the read endpoint declares `ORDER_READ`,
  /// which a customer principal does not hold today either. What this never
  /// does is compute one — V0023 stores a promise decided at checkout and never
  /// recomputed, and a client-side estimate beside it would be a second,
  /// contradicting number.
  Future<PlacedOrder> _withPromise(PlacedOrder order) async {
    if (order.promisedAt != null) return order;
    try {
      final PlacedOrder detail = await _repository.readOrder(order.orderId);
      return detail.promisedAt == null
          ? order
          : order.withPromise(detail.promisedAt);
    } catch (_) {
      return order;
    }
  }
}
