/// RFC 9457 Problem Details, in the shape every Qoida surface returns
/// (ADR 0031).
///
/// Verified against `uz.qoida.platform.web.api.ApiProblem` and `ErrorCode` in
/// the platform repository, and against the extension members
/// `StorefrontOrderingController` actually sets — `reason`, `unavailableItems`,
/// `warnings`, `currentVersion`, `requiredCapability` — rather than against the
/// ADR's example alone.
final class ProblemDetails {
  const ProblemDetails({
    required this.status,
    required this.code,
    this.type,
    this.title,
    this.detail,
    this.instance,
    this.correlationId,
    this.errors = const <ProblemFieldError>[],
    this.extensions = const <String, Object?>{},
  });

  final int status;

  /// The stable machine-readable identifier.
  ///
  /// ADR 0031 is explicit that this, and not `title` or `detail`, is what
  /// clients branch on. `title` and `detail` are written for a developer
  /// reading a response and are not translated.
  final ApiErrorCode code;

  final Uri? type;
  final String? title;
  final String? detail;
  final String? instance;
  final String? correlationId;
  final List<ProblemFieldError> errors;

  /// Members beyond the RFC's own, such as `currentVersion` on a
  /// `STALE_VERSION` or `reason` on a refused checkout.
  final Map<String, Object?> extensions;

  /// The server's current version, on `STALE_VERSION`.
  ///
  /// `ApiException.staleVersion` in the platform sets `expectedVersion` and
  /// `currentVersion`; re-reading and retrying against `currentVersion` is what
  /// resolves the conflict.
  int? get currentVersion => _int('currentVersion');

  /// The capability the principal lacks, on `INSUFFICIENT_CAPABILITY`.
  String? get requiredCapability => extensions['requiredCapability'] as String?;

  /// The plan feature the tenant lacks, on `ENTITLEMENT_REQUIRED`.
  String? get entitlementKey => extensions['entitlementKey'] as String?;

  /// The domain reason behind a conflict, where the server supplied one, such
  /// as `NOT_SERVICEABLE` or `CART_EXPIRED`.
  String? get reason => extensions['reason'] as String?;

  int? _int(String key) {
    final Object? value = extensions[key];
    if (value is int) return value;
    if (value is num) return value.toInt();
    return null;
  }

  static const Set<String> _rfcMembers = <String>{
    'type',
    'title',
    'status',
    'detail',
    'instance',
    'code',
    'correlationId',
    'errors',
  };

  /// Parses a `application/problem+json` body.
  ///
  /// [transportStatus] is the HTTP status actually received. It wins over the
  /// body's `status` member only when the body has none: a proxy that rewrites
  /// one and not the other should not be able to make a 500 look like a 200.
  factory ProblemDetails.fromJson(
    Map<String, Object?> json, {
    required int transportStatus,
  }) {
    final Object? bodyStatus = json['status'];
    final Object? rawErrors = json['errors'];

    return ProblemDetails(
      status: bodyStatus is int ? bodyStatus : transportStatus,
      code: ApiErrorCode(
        json['code'] as String? ?? ApiErrorCode.unparseable.value,
      ),
      type: switch (json['type']) {
        final String value => Uri.tryParse(value),
        _ => null,
      },
      title: json['title'] as String?,
      detail: json['detail'] as String?,
      instance: json['instance'] as String?,
      correlationId: json['correlationId'] as String?,
      errors: rawErrors is List
          ? rawErrors
                .whereType<Map<String, Object?>>()
                .map(ProblemFieldError.fromJson)
                .toList(growable: false)
          : const <ProblemFieldError>[],
      extensions: <String, Object?>{
        for (final MapEntry<String, Object?> entry in json.entries)
          if (!_rfcMembers.contains(entry.key)) entry.key: entry.value,
      },
    );
  }

  /// The problem to report when the server did not send one.
  ///
  /// A gateway timeout page or an HTML error from a proxy is not Problem
  /// Details, and pretending it parsed would hide the fact that the request
  /// never reached the platform.
  factory ProblemDetails.unparseable(int transportStatus) => ProblemDetails(
    status: transportStatus,
    code: ApiErrorCode.unparseable,
    title: 'Unparseable error response',
    detail: 'The response was not application/problem+json.',
  );

  @override
  String toString() => 'ProblemDetails($status ${code.value})';
}

/// A field-level validation failure, with a stable code rather than prose.
final class ProblemFieldError {
  const ProblemFieldError({required this.field, required this.code, this.message});

  final String field;
  final String code;

  /// Developer-facing, and not shown to a customer: the customer-facing string
  /// is chosen from [code] in the localisations.
  final String? message;

  factory ProblemFieldError.fromJson(Map<String, Object?> json) =>
      ProblemFieldError(
        field: json['field'] as String? ?? '',
        code: json['code'] as String? ?? '',
        message: json['message'] as String?,
      );

  @override
  String toString() => 'ProblemFieldError($field $code)';
}

/// A stable error code from the platform's registry.
///
/// A wrapper around a string rather than a Dart enum, deliberately. ADR 0031
/// evolves a major version additively and permits new enum values "where the
/// client is documented to tolerate unknown values"; a Dart enum would throw on
/// decoding one, turning an additive server change into a client crash. This is
/// that documented tolerance.
final class ApiErrorCode {
  const ApiErrorCode(this.value);

  final String value;

  @override
  bool operator ==(Object other) =>
      other is ApiErrorCode && other.value == value;

  @override
  int get hashCode => value.hashCode;

  @override
  String toString() => value;

  static const ApiErrorCode validationFailed = ApiErrorCode('VALIDATION_FAILED');
  static const ApiErrorCode invalidRequest = ApiErrorCode('INVALID_REQUEST');
  static const ApiErrorCode malformedBody = ApiErrorCode('MALFORMED_BODY');
  static const ApiErrorCode idempotencyKeyRequired = ApiErrorCode(
    'IDEMPOTENCY_KEY_REQUIRED',
  );
  static const ApiErrorCode unauthenticated = ApiErrorCode('UNAUTHENTICATED');
  static const ApiErrorCode insufficientCapability = ApiErrorCode(
    'INSUFFICIENT_CAPABILITY',
  );
  static const ApiErrorCode entitlementRequired = ApiErrorCode(
    'ENTITLEMENT_REQUIRED',
  );
  static const ApiErrorCode tenantAccessDenied = ApiErrorCode(
    'TENANT_ACCESS_DENIED',
  );
  static const ApiErrorCode resourceNotFound = ApiErrorCode('RESOURCE_NOT_FOUND');
  static const ApiErrorCode resourceConflict = ApiErrorCode('RESOURCE_CONFLICT');
  static const ApiErrorCode staleVersion = ApiErrorCode('STALE_VERSION');
  static const ApiErrorCode idempotencyKeyReused = ApiErrorCode(
    'IDEMPOTENCY_KEY_REUSED',
  );
  static const ApiErrorCode idempotencyKeyInProgress = ApiErrorCode(
    'IDEMPOTENCY_KEY_IN_PROGRESS',
  );
  static const ApiErrorCode priceChanged = ApiErrorCode('PRICE_CHANGED');
  static const ApiErrorCode unsupportedMediaType = ApiErrorCode(
    'UNSUPPORTED_MEDIA_TYPE',
  );
  static const ApiErrorCode rateLimitExceeded = ApiErrorCode('RATE_LIMIT_EXCEEDED');
  static const ApiErrorCode internalError = ApiErrorCode('INTERNAL_ERROR');

  /// Not a platform code. Assigned locally when the response carried none.
  static const ApiErrorCode unparseable = ApiErrorCode('CLIENT_UNPARSEABLE_ERROR');
}
