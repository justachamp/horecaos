import 'package:flutter/material.dart';

import 'horecaos_theme.dart';
import 'horecaos_tokens.dart';

/// The design system's empty state: a caption and a tertiary action.
///
/// No illustration, no emoji, no oversized icon. An empty list is a fact, and
/// decorating it makes it look like an error.
class QEmptyState extends StatelessWidget {
  const QEmptyState({
    required this.title,
    super.key,
    this.body,
    this.actionLabel,
    this.onAction,
  });

  final String title;
  final String? body;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(HorecaOSGeometry.spaceLg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: <Widget>[
            Text(title, style: text.titleMedium, textAlign: TextAlign.center),
            if (body != null) ...<Widget>[
              const SizedBox(height: HorecaOSGeometry.spaceSm),
              Text(
                body!,
                style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
                textAlign: TextAlign.center,
              ),
            ],
            if (actionLabel != null && onAction != null) ...<Widget>[
              const SizedBox(height: HorecaOSGeometry.spaceMd),
              TextButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}
