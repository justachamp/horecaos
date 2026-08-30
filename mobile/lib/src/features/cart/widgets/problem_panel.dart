import 'package:flutter/material.dart';

import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';

/// How serious the thing being reported is.
///
/// Tone picks the dot, and the dot is the whole signal. ADR 0035: status is a
/// dot plus text and never colour alone, so the text below says the same thing
/// the colour does and the panel reads correctly to somebody who cannot tell
/// the two dots apart.
enum ProblemTone {
  /// A fact about the world, not a fault. A branch that is shut, a basket that
  /// lapsed, a price that moved.
  neutral,

  /// Something the customer should act on before it costs them.
  warning,

  /// The thing they asked for did not happen.
  failure,
}

/// One refusal, explained.
///
/// Deliberately not a snackbar. A snackbar is for something that happened and
/// is over; every state this panel renders is a state the customer is still in,
/// with a decision attached, and a message that slides away after four seconds
/// is the wrong shape for one.
class QProblemPanel extends StatelessWidget {
  const QProblemPanel({
    required this.title,
    required this.body,
    super.key,
    this.tone = ProblemTone.neutral,
    this.actionLabel,
    this.onAction,
    this.correlationId,
    this.child,
  });

  final String title;
  final String body;
  final ProblemTone tone;
  final String? actionLabel;
  final VoidCallback? onAction;

  /// Shown as a quotable reference and never as an error message. A customer
  /// reads this to support; they do not read a stack trace to anybody.
  final String? correlationId;

  /// Extra content between the body and the action — a before-and-after price,
  /// a list of items that ran out.
  final Widget? child;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
      decoration: BoxDecoration(
        color: tokens.surface1,
        borderRadius: BorderRadius.circular(tokens.radius),
        border: Border.all(
          color: tokens.hairline,
          width: QoidaGeometry.hairline,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Padding(
                // Centres the dot on the first line of the title rather than on
                // the row, so it does not drift down as the title wraps.
                padding: const EdgeInsets.only(top: QoidaGeometry.spaceSm),
                child: _Dot(tone: tone),
              ),
              const SizedBox(width: QoidaGeometry.spaceSm),
              Expanded(child: Text(title, style: text.titleSmall)),
            ],
          ),
          const SizedBox(height: QoidaGeometry.spaceXs),
          Text(body, style: text.bodyMedium?.copyWith(color: tokens.inkMuted)),
          if (child != null) ...<Widget>[
            const SizedBox(height: QoidaGeometry.spaceMd),
            child!,
          ],
          if (correlationId != null) ...<Widget>[
            const SizedBox(height: QoidaGeometry.spaceSm),
            Text(
              AppLocalizations.of(context).supportReference(correlationId!),
              style: text.bodySmall,
            ),
          ],
          if (actionLabel != null && onAction != null) ...<Widget>[
            const SizedBox(height: QoidaGeometry.spaceSm),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton(onPressed: onAction, child: Text(actionLabel!)),
            ),
          ],
        ],
      ),
    );
  }
}

class _Dot extends StatelessWidget {
  const _Dot({required this.tone});

  final ProblemTone tone;

  @override
  Widget build(BuildContext context) {
    final QoidaTokens tokens = context.qoida;
    return SizedBox(
      width: QoidaGeometry.spaceSm,
      height: QoidaGeometry.spaceSm,
      child: DecoratedBox(
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: switch (tone) {
            ProblemTone.neutral => tokens.inkSubtle,
            // The yellow token is a dot and nothing else in this system; its
            // text pair is the darker warning ink, which is why the words above
            // are not drawn in it.
            ProblemTone.warning => tokens.warningDot,
            ProblemTone.failure => tokens.error,
          },
        ),
      ),
    );
  }
}
