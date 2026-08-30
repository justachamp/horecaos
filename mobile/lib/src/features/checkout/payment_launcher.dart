/// Sends the customer to a payment page.
///
/// A port, because opening an external URL is a platform-channel call and this
/// application has no plugin for it yet: `pubspec.yaml` carries `http`,
/// `go_router`, `crypto`, `uuid`, secure storage and `flutter_web_auth_2`, and
/// none of those opens an arbitrary link. `flutter_web_auth_2` is deliberately
/// not reused for this — it waits for a redirect back on a registered custom
/// scheme, and a Click or Payme checkout does not come back that way. Adding
/// `url_launcher` is a dependency decision that belongs with whoever owns the
/// manifest, not with this feature.
///
/// So the seam is here and the implementation is not, and the checkout screen
/// says plainly that the payment page could not be opened rather than pretending
/// it did. The order is already durable at that point — ADR 0013 opens the
/// attempt before any provider is reached — so a failure to launch costs a tap,
/// never a charge.
abstract interface class PaymentLauncher {
  /// Returns false when the URL could not be opened. Throwing is reserved for a
  /// genuine platform failure; "no application can handle this" is an answer.
  Future<bool> open(Uri url);
}

/// Nothing is wired, so nothing opens.
///
/// The default. It is honest about the state of the application rather than
/// silently doing nothing, which is what an empty implementation returning true
/// would be.
final class UnwiredPaymentLauncher implements PaymentLauncher {
  const UnwiredPaymentLauncher();

  @override
  Future<bool> open(Uri url) async => false;
}
