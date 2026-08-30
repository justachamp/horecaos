import 'package:flutter/material.dart';

import 'qoida_theme.dart';
import 'qoida_tokens.dart';

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
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(QoidaGeometry.spaceLg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.center,
          children: <Widget>[
            Text(title, style: text.titleMedium, textAlign: TextAlign.center),
            if (body != null) ...<Widget>[
              const SizedBox(height: QoidaGeometry.spaceSm),
              Text(
                body!,
                style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
                textAlign: TextAlign.center,
              ),
            ],
            if (actionLabel != null && onAction != null) ...<Widget>[
              const SizedBox(height: QoidaGeometry.spaceMd),
              TextButton(onPressed: onAction, child: Text(actionLabel!)),
            ],
          ],
        ),
      ),
    );
  }
}
