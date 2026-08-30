import 'package:flutter/foundation.dart';

import '../../api/api_exception.dart';
import '../../api/problem_details.dart';
import 'cart_models.dart';
import 'cart_repository.dart';

/// A cart refusal the customer has to understand, rather than a toast.
///
/// Every value here is a fact about the world — the branch shut, the basket
/// lapsed, an item stopped being sold — and each one has a different thing the
/// customer can do next. Collapsing them into "something went wrong" is what
/// makes an application feel broken when it is in fact working correctly.
enum CartProblemKind {
  /// Four hours passed, or the cart was abandoned or already converted. The
  /// basket is gone and the only move is to start another one.
  cartGone,

  /// ADR 0036 says this branch cannot take this order now: closed, out of
  /// hours, the channel stopped selling.
  notServiceable,

  /// The cart could not be priced at all — an item with no active price, or one
  /// the location no longer sells. Carries the offending id so the screen can
  /// name it.
  pricingRefused,

  /// The platform refused the principal. Today this is the expected answer for
  /// every real customer: the storefront ordering endpoints declare
  /// `ORDER_PLACE`, which no customer principal holds until ADR 0025 settles
  /// what a non-staff principal is. Surfaced as its own state rather than
  /// disguised, because pretending it is a network error would send people to
  /// re-try a thing that cannot succeed.
  notPermitted,

  /// The request never reached the platform.
  offline,

  /// Anything else the platform answered.
  unexpected,
}

/// A refusal, with everything the screen needs to explain it.
final class CartProblem {
  const CartProblem(this.kind, {this.reason, this.subjectId, this.correlationId});

  final CartProblemKind kind;

  /// The platform's own stable code — `NOT_SERVICEABLE`, `CART_EXPIRED`,
  /// `CLOSED` — never rendered as-is, and used to pick the wording.
  final String? reason;

  /// The item a pricing refusal blamed.
  final String? subjectId;

  /// For a customer reporting the problem to support.
  final String? correlationId;

  static CartProblem from(Object error) {
    if (error is ApiTransportException) {
      return CartProblem(
        CartProblemKind.offline,
        correlationId: error.correlationId,
      );
    }
    if (error is! ApiException) {
      return const CartProblem(CartProblemKind.unexpected);
    }

    final ProblemDetails problem = error.problem;
    final String? reason = problem.reason;
    final CartProblemKind kind;
    if (error.isForbidden) {
      kind = CartProblemKind.notPermitted;
    } else if (reason == 'NOT_SERVICEABLE' || reason == 'CHANNEL_NOT_SELLABLE') {
      kind = CartProblemKind.notServiceable;
    } else if (reason == 'CART_EXPIRED' ||
        reason == 'CART_NOT_EDITABLE' ||
        reason == 'CART_NOT_FOUND' ||
        error.isNotFound) {
      kind = CartProblemKind.cartGone;
    } else if (problem.code == ApiErrorCode.validationFailed && reason != null) {
      // `CartPricingPort.PricingRefusedException` arrives this way, with the
      // offending id attached so the screen can name the item instead of
      // leaving the customer to delete things one at a time.
      kind = CartProblemKind.pricingRefused;
    } else {
      kind = CartProblemKind.unexpected;
    }

    return CartProblem(
      kind,
      reason: reason,
      subjectId: problem.extensions['subjectId'] as String?,
      correlationId: problem.correlationId,
    );
  }
}

/// The cart, and every mutation that can be made to it.
///
/// A `ChangeNotifier` rather than a stream or a third-party state library: the
/// application has one, the router already listens to one, and adding a second
/// idiom would mean two ways of doing the same thing in a codebase whose whole
/// argument is that there is one.
///
/// **This class holds no arithmetic.** It never sums a line, never applies a
/// fee, never adjusts a total. Those come from the quote, and the quote comes
/// from the server, and the only thing done to them here is deciding whether the
/// one being held is still the one that belongs to the basket on screen.
class CartController extends ChangeNotifier {
  CartController({required this._repository, DateTime Function()? now})
    : _now = now ?? DateTime.now;

  final CartRepository _repository;
  final DateTime Function() _now;

  Cart? _cart;
  PricedCart? _quote;
  CartProblem? _problem;
  bool _busy = false;

  Cart? get cart => _cart;

  /// The bound quote, or null when the basket has been edited since it was
  /// priced. Null is the normal state after any change, and the screen shows a
  /// "refresh the price" affordance rather than a stale number.
  PricedCart? get quote => _quote;

  CartProblem? get problem => _problem;
  bool get isBusy => _busy;

  bool get isEmpty => _cart == null || _cart!.isEmpty;

  /// Whether the held quote may still be presented to checkout.
  ///
  /// Both halves matter. A quote whose fifteen minutes have run out will be
  /// refused, and so will one bound to an earlier version of the cart.
  bool get hasUsableQuote {
    final PricedCart? held = _quote;
    final Cart? cart = _cart;
    if (held == null || cart == null) return false;
    return held.quoteId == cart.quoteId &&
        held.cartVersion == cart.version &&
        !held.isExpiredAt(_now());
  }

  /// Opens a cart for one fulfilment mode.
  Future<void> open(FulfilmentMode mode) =>
      _run(() async => _adopt(await _repository.createCart(mode)));

  Future<void> load(String cartId) =>
      _run(() async => _adopt(await _repository.readCart(cartId)));

  /// Puts a line into the cart, creating or replacing it.
  ///
  /// [lineKey] is the caller's — the catalogue decides what makes two selections
  /// the same line, because "the same dish with different modifiers" is a
  /// catalogue question and not a cart one.
  Future<void> putLine({
    required String lineKey,
    required String variantId,
    required int quantity,
    List<String> modifierOptionIds = const <String>[],
    String? customerNote,
  }) {
    return _run(() async {
      final Cart updated = await _withFreshVersion(
        (int version) => _repository.putLine(
          cartId: _requireCart().cartId,
          expectedVersion: version,
          lineKey: lineKey,
          variantId: variantId,
          quantity: quantity,
          modifierOptionIds: modifierOptionIds,
          customerNote: customerNote,
        ),
      );
      _adopt(
        updated,
        modifiers: <String, List<String>>{lineKey: modifierOptionIds},
      );
    });
  }

  /// Changes a quantity, removing the line at zero.
  ///
  /// The whole line is re-sent because the platform's `PUT` replaces rather than
  /// patches. Sending only the quantity would strip the customer's modifier
  /// choices, and they would find out at the counter.
  Future<void> setQuantity(String lineKey, int quantity) {
    final CartLine line = _requireLine(lineKey);
    if (quantity <= 0) {
      return removeLine(lineKey);
    }
    return putLine(
      lineKey: lineKey,
      variantId: line.variantId,
      quantity: quantity,
      modifierOptionIds: line.modifierOptionIds,
    );
  }

  Future<void> removeLine(String lineKey) {
    return _run(() async {
      final Cart updated = await _withFreshVersion(
        (int version) => _repository.removeLine(
          cartId: _requireCart().cartId,
          expectedVersion: version,
          lineKey: lineKey,
        ),
      );
      _adopt(updated);
    });
  }

  /// Moves the basket to another branch, which rebuilds it.
  ///
  /// The returned cart is a different cart with a different id and no price.
  /// Adopting it wholesale is deliberate: carrying the old id would leave this
  /// controller mutating a cart the platform has abandoned.
  Future<void> moveToLocation(String locationId) {
    return _run(() async {
      final Cart rebuilt = await _withFreshVersion(
        (int version) => _repository.moveToLocation(
          cartId: _requireCart().cartId,
          expectedVersion: version,
          locationId: locationId,
        ),
      );
      _adopt(rebuilt);
    });
  }

  /// Prices the cart and binds the quote to it.
  ///
  /// Called before checkout and after any change the customer makes. Returns the
  /// quote so a caller that needs it immediately — the checkout controller,
  /// recovering from a stale hash — does not have to read it back off a field
  /// that a later notification may have replaced.
  Future<PricedCart?> refreshPrice() async {
    PricedCart? priced;
    await _run(() async {
      priced = await _withFreshVersion(
        (int version) => _repository.price(
          cartId: _requireCart().cartId,
          expectedVersion: version,
        ),
      );
      _quote = priced;
      // The cart's own version and quote binding moved with the pricing call,
      // so the held cart is now behind by exactly those two fields. Re-reading
      // the whole cart for that would cost a round trip to learn what the
      // pricing response already said.
      final Cart cart = _requireCart();
      _cart = Cart(
        cartId: cart.cartId,
        locationId: cart.locationId,
        status: cart.status,
        currency: cart.currency,
        version: priced!.cartVersion,
        quoteId: priced!.quoteId,
        contextHash: priced!.contextHash,
        expiresAt: cart.expiresAt,
        lines: cart.lines,
      );
    });
    return priced;
  }

  /// Forgets the basket after it became an order.
  ///
  /// Called by checkout on success. The platform has already converted the cart;
  /// keeping it on screen would offer the customer a basket they cannot edit.
  void clearAfterCheckout() {
    _cart = null;
    _quote = null;
    _problem = null;
    notifyListeners();
  }

  /// Runs a mutation, retrying exactly once against the server's version.
  ///
  /// Two devices on one cart is ordinary — a phone and a tablet at a table — and
  /// the second edit arriving with a stale `If-Match` is the expected outcome,
  /// not an error. The customer's intent has not changed, so it is re-sent
  /// against the version the platform reports.
  ///
  /// Once, and never in a loop. A cart that keeps moving underneath this client
  /// is a cart somebody else is actively editing, and silently winning that race
  /// on the tenth attempt is worse than telling the customer what happened.
  Future<T> _withFreshVersion<T>(Future<T> Function(int version) attempt) async {
    try {
      return await attempt(_requireCart().version);
    } on ApiException catch (failure) {
      if (!failure.isStaleVersion) rethrow;
      final int? current = failure.problem.currentVersion;
      final int version =
          current ?? (await _repository.readCart(_requireCart().cartId)).version;
      return attempt(version);
    }
  }

  Future<void> _run(Future<void> Function() body) async {
    if (_busy) return;
    _busy = true;
    _problem = null;
    notifyListeners();
    try {
      await body();
    } catch (error) {
      _problem = CartProblem.from(error);
      if (_problem!.kind == CartProblemKind.cartGone) {
        _cart = null;
        _quote = null;
      }
    } finally {
      _busy = false;
      notifyListeners();
    }
  }

  /// Takes a server response as the truth, and decides what happens to the held
  /// quote.
  ///
  /// The rule is the platform's own: any edit clears the binding. So a quote is
  /// kept only when the response still names it. Anything else — including a
  /// response that names a different quote — drops it, because a price shown
  /// beside a basket it was not computed for is the exact failure ADR 0018
  /// exists to prevent.
  void _adopt(Cart cart, {Map<String, List<String>>? modifiers}) {
    Cart adopted = cart.rememberingModifiersFrom(_cart);
    if (modifiers != null && modifiers.isNotEmpty) {
      adopted = Cart(
        cartId: adopted.cartId,
        locationId: adopted.locationId,
        status: adopted.status,
        currency: adopted.currency,
        version: adopted.version,
        quoteId: adopted.quoteId,
        contextHash: adopted.contextHash,
        expiresAt: adopted.expiresAt,
        lines: <CartLine>[
          for (final CartLine line in adopted.lines)
            modifiers.containsKey(line.lineKey)
                ? line.withModifiers(modifiers[line.lineKey]!)
                : line,
        ],
      );
    }

    _cart = adopted;
    if (_quote != null && _quote!.quoteId != adopted.quoteId) {
      _quote = null;
    }
    if (!adopted.status.isEditable) {
      _problem = CartProblem(
        CartProblemKind.cartGone,
        reason: adopted.status.value,
      );
    }
  }

  Cart _requireCart() {
    final Cart? cart = _cart;
    if (cart == null) {
      throw StateError('There is no cart. Call open() or load() first.');
    }
    return cart;
  }

  CartLine _requireLine(String lineKey) {
    for (final CartLine line in _requireCart().lines) {
      if (line.lineKey == lineKey) return line;
    }
    throw StateError('No line $lineKey in this cart.');
  }
}
