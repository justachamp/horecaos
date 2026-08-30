import 'package:flutter/material.dart';

import '../../../../design/horecaos_theme.dart';
import '../../../../design/horecaos_tokens.dart';
import '../../../../format/horecaos_formats.dart';
import '../../../../l10n/generated/app_localizations.dart';
import '../../data/order_codes.dart';
import '../../data/order_models.dart';
import '../order_strings.dart';
import 'status_dot.dart';

/// How an order ended, when it did not end by being handed over.
///
/// ADR 0039's whole argument is that the legacy system recorded one status and
/// one free-text `cancel_reason` for a customer who changed their mind, a
/// restaurant that refused, and an approval nobody answered — three commercial
/// facts that no report could pull apart afterwards. The customer is owed the
/// same distinction, so this panel renders three different titles from
/// `TerminalOutcomeKind` and never the word "cancelled" for all of them.
///
/// It carries, in order: what happened, why, and what happens to the money.
/// It carries nothing else — no stock disposition, no liability party, no
/// reason identifier, no internal reason name. Those are the operations
/// members of the same row, and this surface has no field to hold them.
class OutcomePanel extends StatelessWidget {
  const OutcomePanel({required this.outcome, super.key});

  final OrderOutcome outcome;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);

    final String? reason = OrderStrings.outcomeReason(l10n, outcome);
    final String? refund = OrderStrings.refund(l10n, outcome.refund);
    final DateTime? closedAt = outcome.occurredAt;

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(HorecaOSGeometry.spaceMd),
      decoration: BoxDecoration(
        color: tokens.surface1,
        borderRadius: BorderRadius.circular(tokens.radius),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Padding(
                // Aligns the dot with the first line of the title rather than
                // with the middle of a title that has wrapped onto two.
                padding: const EdgeInsets.only(top: HorecaOSGeometry.spaceSm),
                child: StatusDot(tone: _tone(outcome.kind)),
              ),
              const SizedBox(width: HorecaOSGeometry.spaceSm),
              Expanded(
                child: Text(
                  OrderStrings.outcomeTitle(l10n, outcome.kind),
                  style: text.titleMedium,
                ),
              ),
            ],
          ),
          if (reason != null) ...<Widget>[
            const SizedBox(height: HorecaOSGeometry.spaceSm),
            Text(
              reason,
              style: text.bodyMedium?.copyWith(color: tokens.inkMuted),
            ),
          ],
          if (refund != null) ...<Widget>[
            const SizedBox(height: HorecaOSGeometry.spaceSm),
            Text(refund, style: text.labelLarge),
          ],
          if (closedAt != null) ...<Widget>[
            const SizedBox(height: HorecaOSGeometry.spaceSm),
            Text(
              l10n.orderClosedAt(
                HorecaOSFormats.dayMonthTime(
                  HorecaOSFormats.toLocal(closedAt),
                  locale: Localizations.localeOf(context).toLanguageTag(),
                ),
              ),
              style: text.bodySmall,
            ),
          ],
        ],
      ),
    );
  }

  /// An expiry is not a refusal, and does not borrow the refusal's tone.
  static StatusTone _tone(TerminalOutcomeKind kind) => switch (kind.value) {
    'EXPIRED' => StatusTone.warning,
    'COMPLETED' => StatusTone.success,
    _ => StatusTone.alert,
  };
}
