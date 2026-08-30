/// What a cart line is called, and whether it can still be ordered.
///
/// `CartLineResponse` carries a line key, a variant id, a quantity and a
/// note flag, and no name at all. Something has to turn a variant id into words,
/// and the only thing that can is the published menu.
final class CartItemName {
  const CartItemName({
    required this.productName,
    required this.orderable,
    this.variantLabel,
  });

  final String productName;

  /// The variant's own label, when the product has more than one and the row
  /// would otherwise be ambiguous. Null on a single-variant product, where
  /// repeating the size on every line is noise.
  final String? variantLabel;

  /// False means the branch has stopped serving it. The variant is still on the
  /// menu and still in the basket — ADR 0016 shows a sold-out item rather than
  /// hiding it — and pricing will refuse it, so saying so in the basket is
  /// kinder than saying it at checkout.
  final bool orderable;
}

/// Resolves names for the variants and modifier options in a cart.
///
/// **A port, and deliberately not a menu client.** The catalogue feature already
/// loads, decodes and indexes the published menu; a second loader here would be
/// a second HTTP call, a second decoder and a second answer to "is this item
/// orderable" that could disagree with the first. So the cart states what it
/// needs and the composition root wires the catalogue's index to it through
/// [DelegatedCartItemNaming].
abstract interface class CartItemNaming {
  /// Null when nothing is known about this variant — either the menu has not
  /// loaded, or the publication no longer contains it.
  ///
  /// Null rather than a placeholder string: "we do not know what this is" and
  /// "this is called nothing" are different facts, and the screen renders the
  /// first as a neutral label rather than as a claim about the menu.
  CartItemName? nameFor(String variantId);

  /// The option's own label, or null when the publication does not name it.
  ///
  /// `MenuModifierOption` carries an authoring code and no translated name, so
  /// what comes back here is a code. The catalogue feature reached the same
  /// conclusion; the missing localised option name is one catalogue gap, not two.
  String? modifierOptionLabel(String optionId);
}

/// Nothing is known, so every line falls back to its neutral label.
///
/// The default, so a cart screen can be built and tested without a menu, and so
/// an unwired composition root produces a basket that is honest about what it
/// does not know rather than one that fails to build.
final class EmptyCartItemNaming implements CartItemNaming {
  const EmptyCartItemNaming();

  @override
  CartItemName? nameFor(String variantId) => null;

  @override
  String? modifierOptionLabel(String optionId) => null;
}

/// Naming supplied by whoever owns the menu.
///
/// Two closures rather than an interface the catalogue must implement: the
/// dependency then points from the composition root at both features instead of
/// from this one at the other, and neither has to know the other's types.
final class DelegatedCartItemNaming implements CartItemNaming {
  const DelegatedCartItemNaming({
    required this._variantNames,
    required this._optionLabels,
  });

  final CartItemName? Function(String variantId) _variantNames;
  final String? Function(String optionId) _optionLabels;

  @override
  CartItemName? nameFor(String variantId) => _variantNames(variantId);

  @override
  String? modifierOptionLabel(String optionId) => _optionLabels(optionId);
}
