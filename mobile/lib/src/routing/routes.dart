/// Every path in the application, in one place.
///
/// Constants rather than string literals at call sites, so a renamed route is a
/// compile error rather than a dead link discovered by a customer.
abstract final class Routes {
  /// Shown while the session is being restored from the keystore.
  ///
  /// A route rather than a flag, so the guard has one rule with three outcomes
  /// instead of a rule plus a special case.
  static const String starting = '/starting';

  static const String signIn = '/sign-in';

  /// The default destination for a signed-in customer.
  static const String menu = '/menu';

  static const String orders = '/orders';
}
