/// What the API client is allowed to record about a request (ADR 0029).
///
/// Personal data never appears in a log, trace, metric or analytics event. The
/// fields on this record are the whole allowance:
///
/// - the method, and a **redacted** path with identifiers replaced by `{id}`,
///   because a raw path carries order and account identifiers;
/// - the status and the duration;
/// - the correlation identifier, which is how a support conversation is joined
///   to a server-side trace without naming the customer.
///
/// Not here, and not by omission: the query string, the request body, the
/// response body, any header, the bearer token, the customer's phone number,
/// address or name. If a future field is added to this record, the question to
/// answer first is whether it could carry any of those.
final class ApiCallRecord {
  const ApiCallRecord({
    required this.method,
    required this.redactedPath,
    required this.durationMs,
    this.status,
    this.correlationId,
    this.errorCode,
    this.replayed = false,
  });

  final String method;
  final String redactedPath;
  final int durationMs;
  final int? status;
  final String? correlationId;

  /// The ADR 0031 error code, which is a constant from a registry and carries
  /// no customer data.
  final String? errorCode;

  /// Whether the server replayed a stored response for this idempotency key.
  final bool replayed;

  @override
  String toString() =>
      '$method $redactedPath ${status ?? '-'} ${durationMs}ms'
      '${replayed ? ' replayed' : ''}'
      '${errorCode != null ? ' $errorCode' : ''}'
      '${correlationId != null ? ' cid=$correlationId' : ''}';
}

/// Where [ApiCallRecord]s go.
abstract interface class ApiTelemetry {
  void record(ApiCallRecord call);
}

/// The default. Records nothing.
///
/// A no-op rather than a print: a debug print in a release build is a log the
/// team stopped reading and an attacker can read. Wiring a real sink is a
/// deliberate act, and whatever is wired inherits the constraints above.
final class NullApiTelemetry implements ApiTelemetry {
  const NullApiTelemetry();

  @override
  void record(ApiCallRecord call) {}
}
