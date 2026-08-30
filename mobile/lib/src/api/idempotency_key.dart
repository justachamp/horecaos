import 'package:uuid/uuid.dart';

/// A client-generated key that makes one mutation safe to repeat (ADR 0031).
///
/// **A key belongs to a user intent, not to an HTTP call.** The customer taps
/// "place order" once; the application may send that request three times over a
/// flaky connection, and all three must carry the same key or the customer gets
/// three orders. So the key is created where the intent is — the moment the tap
/// is handled — and passed into every attempt, including the attempt that
/// follows a token refresh.
///
/// This is why the API client takes a key rather than generating one per call.
/// A per-call key would satisfy the header requirement and defeat its purpose.
final class IdempotencyKey {
  const IdempotencyKey(this.value);

  /// A fresh key for a new user intent.
  ///
  /// Version 4, so two devices offline at once cannot collide, and opaque, so
  /// it carries no customer data into a server-side record (ADR 0029).
  factory IdempotencyKey.generate() => IdempotencyKey(const Uuid().v4());

  final String value;

  @override
  bool operator ==(Object other) =>
      other is IdempotencyKey && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value;
}
