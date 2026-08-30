import 'problem_details.dart';

/// The base of everything the API client throws.
sealed class ApiFailure implements Exception {
  const ApiFailure();
}

/// The platform answered, and the answer was an error (ADR 0031).
final class ApiException extends ApiFailure {
  const ApiException(this.problem, {this.retryAfter});

  final ProblemDetails problem;

  /// From `Retry-After`, on 429 and 503.
  final Duration? retryAfter;

  int get status => problem.status;
  ApiErrorCode get code => problem.code;

  bool get isStaleVersion => code == ApiErrorCode.staleVersion;
  bool get isUnauthenticated => status == 401;
  bool get isForbidden => status == 403;
  bool get isNotFound => status == 404;
  bool get isConflict => status == 409;

  /// Whether repeating the same request with the same idempotency key is
  /// sensible.
  ///
  /// `IDEMPOTENCY_KEY_IN_PROGRESS` is retryable and `IDEMPOTENCY_KEY_REUSED` is
  /// not: the first means the server is still working on this exact request,
  /// the second means the client changed the body underneath a key it had
  /// already committed to.
  bool get isRetryable =>
      status == 429 ||
      status == 503 ||
      code == ApiErrorCode.idempotencyKeyInProgress;

  @override
  String toString() => 'ApiException(${problem.status} ${problem.code.value})';
}

/// The request never got an answer: no route to host, TLS failure, timeout.
///
/// Distinct from [ApiException] because the two demand different things of the
/// caller. A transport failure on a mutation leaves the server's state unknown,
/// and the only safe recovery is to retry with the same idempotency key — which
/// is exactly what that header exists for.
final class ApiTransportException extends ApiFailure {
  const ApiTransportException(this.reason, {this.correlationId});

  /// A description of the transport failure. Never contains a URL query or a
  /// request body, so it is safe to log (ADR 0029).
  final String reason;

  final String? correlationId;

  @override
  String toString() => 'ApiTransportException($reason)';
}
