import 'package:flutter/material.dart';

import '../../../design/q_icon.dart';
import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';

/// A section heading inside a settings list.
///
/// Sentence case, caption scale, muted ink. No rule above it and no card around
/// it: the hairline between rows is the whole structure this design system uses.
class ProfileSectionHeader extends StatelessWidget {
  const ProfileSectionHeader(this.title, {super.key});

  final String title;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        QoidaGeometry.spaceMd,
        QoidaGeometry.spaceLg,
        QoidaGeometry.spaceMd,
        QoidaGeometry.spaceSm,
      ),
      child: Text(title, style: Theme.of(context).textTheme.bodySmall),
    );
  }
}

/// One row of a settings list.
///
/// The press state is a token colour change and not an ink ripple. ADR 0035
/// switches the ripple off across the application — `splashFactory` is
/// `NoSplash` in the theme — so a `ListTile` here would be a row that never
/// acknowledges a tap at all. This is the replacement the ADR asks for: one
/// surface colour while the finger is down, applied instantly, because only
/// `transform` and `opacity` are allowed to animate.
///
/// Private to the profile area for now. When a second feature needs it, it goes
/// into the design system rather than being copied — a second copy is how two
/// rows end up disagreeing about their own height.
class ProfileRow extends StatefulWidget {
  const ProfileRow({
    required this.title,
    super.key,
    this.value,
    this.detail,
    this.onTap,
    this.trailing,
    this.destructive = false,
  });

  final String title;

  /// The current setting, shown at the end of the row.
  final String? value;

  /// A second line under the title, for a row that needs explaining.
  final String? detail;

  final VoidCallback? onTap;

  /// Replaces the chevron. A switch, usually.
  final Widget? trailing;

  /// Renders the title in the error ink. For sign-out and for removing an
  /// address, and never for anything reversible.
  final bool destructive;

  @override
  State<ProfileRow> createState() => _ProfileRowState();
}

class _ProfileRowState extends State<ProfileRow> {
  bool _pressed = false;

  void _setPressed(bool pressed) {
    if (_pressed != pressed) {
      setState(() => _pressed = pressed);
    }
  }

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;
    final bool interactive = widget.onTap != null;

    final Widget content = Container(
      constraints: const BoxConstraints(minHeight: QoidaGeometry.minTarget),
      color: _pressed && interactive ? tokens.surface1 : tokens.canvas,
      padding: const EdgeInsets.symmetric(
        horizontal: QoidaGeometry.spaceMd,
        vertical: QoidaGeometry.spaceSm,
      ),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: <Widget>[
                Text(
                  widget.title,
                  style: widget.destructive
                      ? text.bodyLarge?.copyWith(color: tokens.errorInk)
                      : text.bodyLarge,
                ),
                if (widget.detail != null) ...<Widget>[
                  const SizedBox(height: QoidaGeometry.spaceXs),
                  Text(widget.detail!, style: text.bodySmall),
                ],
              ],
            ),
          ),
          if (widget.value != null) ...<Widget>[
            const SizedBox(width: QoidaGeometry.spaceMd),
            Text(
              widget.value!,
              style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
            ),
          ],
          if (widget.trailing != null) ...<Widget>[
            const SizedBox(width: QoidaGeometry.spaceSm),
            widget.trailing!,
          ] else if (interactive) ...<Widget>[
            const SizedBox(width: QoidaGeometry.spaceSm),
            QIcon(QIconName.chevronRight, size: 20, color: tokens.inkSubtle),
          ],
        ],
      ),
    );

    if (!interactive) {
      return Semantics(container: true, child: content);
    }

    return Semantics(
      button: true,
      container: true,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTapDown: (TapDownDetails _) => _setPressed(true),
        onTapUp: (TapUpDetails _) => _setPressed(false),
        onTapCancel: () => _setPressed(false),
        onTap: widget.onTap,
        child: content,
      ),
    );
  }
}

/// The hairline between rows.
///
/// Full-bleed rather than inset. An inset divider implies a hierarchy between
/// rows that a settings list does not have.
class ProfileDivider extends StatelessWidget {
  const ProfileDivider({super.key});

  @override
  Widget build(BuildContext context) => Divider(
    height: QoidaGeometry.hairline,
    thickness: QoidaGeometry.hairline,
    color: context.qoida.hairline,
  );
}
