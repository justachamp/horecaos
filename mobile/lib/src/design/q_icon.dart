import 'package:flutter/material.dart';

import 'qoida_theme.dart';

/// The icons this application is allowed to draw.
///
/// A closed set, for the same reason the type scale is closed: an open one
/// drifts. Adding an entry is a deliberate act with a design review behind it.
enum QIconName { home, orders, cart, profile, back, close, search, chevronRight }

/// The single icon seam.
///
/// ADR 0035 requires one bundled icon font behind one widget — the same
/// replaceability seam the web `Icon` component uses to substitute Lucide for
/// Carbon. SF Symbols were rejected because they do not exist on Android and
/// would have forced two icon sets.
///
/// The icon font is not bundled yet, so this maps onto Material's built-in set
/// today. That is a stand-in, not the decision: when the Qoida icon font lands,
/// only [_glyph] changes and no call site moves. Call sites must therefore never
/// use `Icon(Icons.…)` directly — test/design/design_system_lint_test.dart fails
/// the build on one.
class QIcon extends StatelessWidget {
  const QIcon(this.name, {super.key, this.size = 24, this.color});

  final QIconName name;
  final double size;

  /// Defaults to the ambient icon colour, which the theme sets from tokens.
  final Color? color;

  @override
  Widget build(BuildContext context) {
    return Icon(
      _glyph(name),
      size: size,
      color: color ?? IconTheme.of(context).color ?? context.qoida.ink,
    );
  }

  static IconData _glyph(QIconName name) => switch (name) {
    QIconName.home => Icons.storefront_outlined,
    QIconName.orders => Icons.receipt_long_outlined,
    QIconName.cart => Icons.shopping_bag_outlined,
    QIconName.profile => Icons.person_outline,
    QIconName.back => Icons.arrow_back,
    QIconName.close => Icons.close,
    QIconName.search => Icons.search,
    QIconName.chevronRight => Icons.chevron_right,
  };
}
