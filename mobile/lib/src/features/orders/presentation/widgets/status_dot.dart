import 'package:flutter/material.dart';

import '../../../../design/qoida_theme.dart';
import '../../../../design/qoida_tokens.dart';
import '../../data/order_codes.dart';

/// The tone a status carries. Never expressed as colour alone.
///
/// ADR 0035: "Status tone is a dot plus text, never colour alone. Yellow is a
/// dot only; its text pair is the darker warning ink." Every use of these tones
/// in this feature puts a word beside the dot, so the screen is readable
/// without colour vision and legible in a photograph of a phone in the sun.
enum StatusTone { neutral, active, success, warning, alert }

/// The dot itself.
///
/// Filled for a fact that has happened, hollow for one that has not. The hollow
/// form is what makes a milestone list say "not yet" without a second colour.
class StatusDot extends StatelessWidget {
  const StatusDot({required this.tone, super.key, this.size = 10, this.filled = true});

  final StatusTone tone;
  final double size;
  final bool filled;

  @override
  Widget build(BuildContext context) {
    final Color colour = colourFor(context, tone);
    return SizedBox(
      width: size,
      height: size,
      child: DecoratedBox(
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: filled ? colour : null,
          border: filled
              ? null
              : Border.all(color: colour, width: QoidaGeometry.hairline),
        ),
      ),
    );
  }

  /// The token behind each tone.
  ///
  /// Read from the theme extension rather than from the palette, so a tenant
  /// accent reaches the active tone the way ADR 0035 intends.
  static Color colourFor(BuildContext context, StatusTone tone) {
    final QoidaTokens tokens = context.qoida;
    return switch (tone) {
      StatusTone.neutral => tokens.inkSubtle,
      StatusTone.active => tokens.accent,
      StatusTone.success => tokens.success,
      StatusTone.warning => tokens.warningDot,
      StatusTone.alert => tokens.error,
    };
  }
}

/// The tone for an order status.
///
/// The four non-completed terminal statuses share the alert tone and differ in
/// their words, because a customer needs to know an order ended and then needs
/// to read *how* it ended — which is a sentence, not a colour.
StatusTone toneForStatus(OrderStatus status) => switch (status.value) {
  'RECEIVED' || 'PAYMENT_AUTHORIZING' || 'AWAITING_APPROVAL' =>
    StatusTone.neutral,
  'CONFIRMED' || 'PREPARING' || 'READY' || 'FULFILLING' => StatusTone.active,
  'COMPLETED' => StatusTone.success,
  'CANCELLED' || 'REJECTED' || 'PAYMENT_FAILED' => StatusTone.alert,
  // An expiry is not a refusal and does not get the refusal's tone: nobody
  // decided, which is a different fact from a branch saying no.
  'EXPIRED' => StatusTone.warning,
  _ => StatusTone.neutral,
};
