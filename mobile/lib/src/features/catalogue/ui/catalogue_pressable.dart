import 'package:flutter/material.dart';

import '../../../design/horecaos_theme.dart';
import '../../../design/horecaos_tokens.dart';

/// A tappable row with the design system's press state.
///
/// ADR 0035 replaces the Material ink ripple with "a token-controlled press
/// state", and `HorecaOSTheme` switches the ripple off globally — which on its own
/// leaves a row that gives no feedback at all. This is the other half: the
/// surface changes to `surface1` while the finger is down and back when it
/// lifts. No animation, because only `transform` and `opacity` animate in this
/// system and a cross-fading background is neither.
///
/// It lives in the catalogue because it was needed here first. It is not a
/// catalogue concept and belongs in `lib/src/design/` beside `QEmptyState` the
/// moment a second feature wants it.
class CataloguePressable extends StatefulWidget {
  const CataloguePressable({
    required this.child,
    super.key,
    this.onTap,
    this.padding = const EdgeInsets.symmetric(
      horizontal: HorecaOSGeometry.spaceMd,
      vertical: HorecaOSGeometry.spaceMd,
    ),
    this.semanticsLabel,
  });

  final Widget child;

  /// Null makes the row inert and, deliberately, still legible. An item the
  /// branch has stopped is shown and not orderable; hiding it would leave the
  /// customer wondering where it went.
  final VoidCallback? onTap;

  final EdgeInsetsGeometry padding;
  final String? semanticsLabel;

  @override
  State<CataloguePressable> createState() => _CataloguePressableState();
}

class _CataloguePressableState extends State<CataloguePressable> {
  bool _pressed = false;

  void _setPressed(bool value) {
    if (_pressed == value) return;
    setState(() => _pressed = value);
  }

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final bool enabled = widget.onTap != null;

    return Semantics(
      button: enabled,
      label: widget.semanticsLabel,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onTap,
        onTapDown: enabled ? (TapDownDetails _) => _setPressed(true) : null,
        onTapUp: enabled ? (TapUpDetails _) => _setPressed(false) : null,
        onTapCancel: enabled ? () => _setPressed(false) : null,
        child: ColoredBox(
          color: _pressed && enabled ? tokens.surface1 : tokens.canvas,
          child: ConstrainedBox(
            // 48dp on both platforms, from the tokens rather than from
            // Material's own default, which is the same number today and is
            // not the one this design system stated.
            constraints: BoxConstraints(minHeight: tokens.minTarget),
            child: Padding(padding: widget.padding, child: widget.child),
          ),
        ),
      ),
    );
  }
}
