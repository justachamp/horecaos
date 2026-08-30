import 'package:flutter/material.dart';

import '../../../../design/horecaos_theme.dart';
import '../../../../design/horecaos_tokens.dart';
import '../../../../format/horecaos_formats.dart';
import '../../../../l10n/generated/app_localizations.dart';
import '../../application/order_milestones.dart';
import '../../data/order_codes.dart';
import '../order_strings.dart';
import 'status_dot.dart';

/// The live view of an order, and the whole of it.
///
/// **There is no map here, and its absence is the design.** ADR 0045 settled on
/// 2026-08-23 that a customer sees status milestones only: a courier's position
/// is collected for dispatch and never published. The legacy iOS application
/// showed a Yandex map with the courier moving across it, so this is a real
/// downgrade, and the answer is not to apologise for it on the screen but to
/// make the screen answer the question the map was being asked. "Where is my
/// order" is answered here by a handover that actually happened and a promised
/// time the platform recorded — both facts, neither inferred from where a
/// phone is.
///
/// What is deliberately not drawn: a map, a pin, a route, a distance, a
/// countdown, a vehicle, a plate, and a phone number.
class MilestoneRail extends StatelessWidget {
  const MilestoneRail({
    required this.steps,
    super.key,
    this.mode,
    this.courierFirstName,
    this.isLate = false,
  });

  final List<MilestoneStep> steps;
  final FulfillmentMode? mode;

  /// Rendered on the handover step only, and only if the platform sent one.
  final String? courierFirstName;

  /// Marks the step in progress as late. Derived from `promised_at` against
  /// the clock, never read from a field.
  final bool isLate;

  @override
  Widget build(BuildContext context) {
    if (steps.isEmpty) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        for (int i = 0; i < steps.length; i++)
          _RailRow(
            step: steps[i],
            mode: mode,
            courierFirstName: courierFirstName,
            isFirst: i == 0,
            isLast: i == steps.length - 1,
            isLate: isLate && steps[i].isCurrent,
          ),
      ],
    );
  }
}

class _RailRow extends StatelessWidget {
  const _RailRow({
    required this.step,
    required this.isFirst,
    required this.isLast,
    required this.isLate,
    this.mode,
    this.courierFirstName,
  });

  final MilestoneStep step;
  final FulfillmentMode? mode;
  final String? courierFirstName;
  final bool isFirst;
  final bool isLast;
  final bool isLate;

  /// The rail column's width. Wide enough that the dot's centre sits under the
  /// connector without arithmetic at the call site.
  static const double _railWidth = 24;
  static const double _dotSize = 12;

  @override
  Widget build(BuildContext context) {
    final HorecaOSTokens tokens = context.horecaos;
    final TextTheme text = Theme.of(context).textTheme;
    final AppLocalizations l10n = AppLocalizations.of(context);

    final bool reached = step.isDone || step.isCurrent;
    final StatusTone tone = isLate
        ? StatusTone.warning
        : (reached ? StatusTone.active : StatusTone.neutral);

    final Color connectorAbove = step.isDone || step.isCurrent
        ? tokens.accent
        : tokens.hairline;
    final Color connectorBelow = step.isDone ? tokens.accent : tokens.hairline;

    final String label = OrderStrings.milestone(
      l10n,
      step.milestone,
      mode,
      courierFirstName: courierFirstName,
    );

    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          SizedBox(
            width: _railWidth,
            child: Column(
              children: <Widget>[
                Expanded(
                  child: _Connector(
                    colour: connectorAbove,
                    visible: !isFirst,
                  ),
                ),
                StatusDot(tone: tone, size: _dotSize, filled: reached),
                Expanded(
                  child: _Connector(colour: connectorBelow, visible: !isLast),
                ),
              ],
            ),
          ),
          const SizedBox(width: HorecaOSGeometry.spaceSm),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(
                vertical: HorecaOSGeometry.spaceSm,
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text(
                    label,
                    style: step.isCurrent
                        ? text.labelLarge
                        : text.bodyMedium?.copyWith(
                            color: reached ? tokens.ink : tokens.inkSubtle,
                          ),
                  ),
                  if (step.at != null)
                    Text(
                      HorecaOSFormats.dayMonthTime(
                        HorecaOSFormats.toLocal(step.at!),
                        locale: Localizations.localeOf(context).toLanguageTag(),
                      ),
                      style: text.bodySmall,
                    ),
                  if (isLate)
                    Text(
                      l10n.orderLate,
                      style: text.bodySmall?.copyWith(color: tokens.warningInk),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// A hairline between two dots. Invisible rather than absent, so every row is
/// the same shape and the dots line up down the column.
class _Connector extends StatelessWidget {
  const _Connector({required this.colour, required this.visible});

  final Color colour;
  final bool visible;

  @override
  Widget build(BuildContext context) => Center(
    // A childless Container grows to the constraints it is given, which is what
    // makes the segment span the gap between two dots however tall the row's
    // text turns out to be.
    child: Container(
      width: HorecaOSGeometry.hairline,
      color: visible ? colour : null,
    ),
  );
}
