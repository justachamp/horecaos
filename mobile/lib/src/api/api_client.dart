import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:uuid/uuid.dart';

import 'api_exception.dart';
import 'api_telemetry.dart';
import 'idempotency_key.dart';
import 'page.dart';
import 'problem_details.dart';

/// Supplies the bearer token, and refreshes it when the platform rejects one.
///
/// An interface rather than a concrete dependency on the auth package: the API
/// client is testable without Keycloak, and the auth package does not have to
/// know that HTTP exists.
abstract interface class AccessTokens {
  /// The current access token, or null when there is no session.
  Future<String?> current();

  /// Refreshes after a 401 and returns the new token, or null if the session
  /// is over. Implementations must collapse concurrent calls into one refresh.
  Future<String?> refresh();
}

/// A successful response, with the parts of the envelope callers need.
final class ApiResponse<T> {
  const ApiResponse({
    required this.value,
    required this.status,
    this.version,
    this.correlationId,
    this.replayed = false,
  });

  final T value;
  final int status;

  /// The aggregate version parsed from the `ETag`, for the next `If-Match`.
  ///
  /// The platform renders a weak validator, `W/"7"`; the quotes and the `W/`
  /// are stripped here so a caller passes an `int` and never assembles a header.
  final int? version;

  final String? correlationId;

  /// True when the server returned a stored response for this idempotency key
  /// rather than performing the effect again (`Idempotency-Replayed: true`).
  ///
  /// Worth surfacing: a checkout that replays did not create a second order,
  /// and a screen that treats it as a fresh success is correct, whereas one
  /// that treats it as a failure and retries is not.
  final bool replayed;
}

/// The HorecaOS platform HTTP client (ADR 0031).
///
/// Verified against the platform's own `ApiProblem`, `AggregateVersion`,
/// `IdempotencyInterceptor`, `CorrelationIdFilter` and `Page`, and against
/// `StorefrontOrderingController` as a worked example, rather than against the
/// ADR text alone.
///
/// This is a hand-written client and it is temporary. ADR 0035 requires
/// generated clients pinned to a published OpenAPI document, with CI failing if
/// regenerating produces a diff. That machinery does not exist yet. What is
/// here is the transport and the conventions — the layer a generated client
/// would sit on top of, not a substitute for one. Response *types* are
/// deliberately absent: hand-copying those is the specific failure ADR 0035
/// forbids.
final class HorecaOSApiClient {
  HorecaOSApiClient({
    required this._baseUri,
    required http.Client httpClient,
    required this._tokens,
    this._telemetry = const NullApiTelemetry(),
    this._timeout = const Duration(seconds: 20),
    String Function()? correlationIds,
  }) : _http = httpClient,
       _correlationIds = correlationIds ?? _uuidV4;

  final Uri _baseUri;
  final http.Client _http;
  final AccessTokens _tokens;
  final ApiTelemetry _telemetry;
  final Duration _timeout;
  final String Function() _correlationIds;

  /// The platform's `CorrelationIdFilter.HEADER_NAME`.
  ///
  /// Spelled `X-Correlation-ID` there and `X-Correlation-Id` in ADR 0031's
  /// prose. HTTP field names are case-insensitive so both reach the same
  /// filter; the code's spelling is used because the code is what runs.
  static const String correlationIdHeader = 'X-Correlation-ID';
  static const String idempotencyKeyHeader = 'Idempotency-Key';
  static const String idempotencyReplayedHeader = 'Idempotency-Replayed';

  /// Reads a response header without assuming its case.
  ///
  /// Header names are case-insensitive by RFC 9110, and while `package:http`
  /// lowercases what it parses off a real socket, nothing guarantees that for a
  /// response another client — or a test double — constructed by hand. Indexing
  /// the map directly makes correctness depend on who built the response, and
  /// the failure is silent: a replayed checkout reads as a fresh one, and a
  /// screen that retries it is the duplicate-order bug in person.

  static String _uuidV4() => const Uuid().v4();

  Future<ApiResponse<T>> get<T>(
    String path, {
    required T Function(Map<String, Object?> json) decode,
    Map<String, String>? query,
  }) => _send<T>(method: 'GET', path: path, query: query, decode: decode);

  /// One page of a cursor-paginated collection.
  ///
  /// [cursor] is whatever the previous page's `nextCursor` was, opaque and
  /// unmodified. There is no page number to pass because there are no pages to
  /// number.
  Future<ApiResponse<Page<T>>> getPage<T>(
    String path, {
    required T Function(Map<String, Object?> item) decodeItem,
    String? cursor,
    int? limit,
    Map<String, String>? query,
  }) {
    return _send<Page<T>>(
      method: 'GET',
      path: path,
      query: <String, String>{
        ...?query,
        'cursor': ?cursor,
        if (limit != null) 'limit': '$limit',
      },
      decode: (Map<String, Object?> json) => Page.fromJson<T>(json, decodeItem),
    );
  }

  /// A mutation.
  ///
  /// [idempotencyKey] is required and has no default. A default would let a
  /// caller take one without deciding where it comes from, and the whole value
  /// of the header is that it survives a retry — see [IdempotencyKey].
  ///
  /// [expectedVersion] becomes `If-Match: W/"<version>"`. The platform rejects a
  /// mutation of a versioned aggregate that arrives without one, so omitting it
  /// where the endpoint wants it produces `INVALID_REQUEST` rather than an
  /// unchecked write.
  Future<ApiResponse<T>> post<T>(
    String path, {
    required IdempotencyKey idempotencyKey,
    required T Function(Map<String, Object?> json) decode,
    Object? body,
    int? expectedVersion,
    Map<String, String>? query,
  }) => _send<T>(
    method: 'POST',
    path: path,
    query: query,
    body: body,
    idempotencyKey: idempotencyKey,
    expectedVersion: expectedVersion,
    decode: decode,
  );

  Future<ApiResponse<T>> put<T>(
    String path, {
    required IdempotencyKey idempotencyKey,
    required T Function(Map<String, Object?> json) decode,
    Object? body,
    int? expectedVersion,
    Map<String, String>? query,
  }) => _send<T>(
    method: 'PUT',
    path: path,
    query: query,
    body: body,
    idempotencyKey: idempotencyKey,
    expectedVersion: expectedVersion,
    decode: decode,
  );

  Future<ApiResponse<T>> patch<T>(
    String path, {
    required IdempotencyKey idempotencyKey,
    required T Function(Map<String, Object?> json) decode,
    Object? body,
    int? expectedVersion,
    Map<String, String>? query,
  }) => _send<T>(
    method: 'PATCH',
    path: path,
    query: query,
    body: body,
    idempotencyKey: idempotencyKey,
    expectedVersion: expectedVersion,
    decode: decode,
  );

  Future<ApiResponse<T>> delete<T>(
    String path, {
    required IdempotencyKey idempotencyKey,
    required T Function(Map<String, Object?> json) decode,
    int? expectedVersion,
    Map<String, String>? query,
  }) => _send<T>(
    method: 'DELETE',
    path: path,
    query: query,
    idempotencyKey: idempotencyKey,
    expectedVersion: expectedVersion,
    decode: decode,
  );

  Future<ApiResponse<T>> _send<T>({
    required String method,
    required String path,
    required T Function(Map<String, Object?> json) decode,
    Map<String, String>? query,
    Object? body,
    IdempotencyKey? idempotencyKey,
    int? expectedVersion,
  }) async {
    final Stopwatch clock = Stopwatch()..start();
    final String correlationId = _correlationIds();
    final Uri uri = _resolve(path, query);
    final String? encodedBody = body == null ? null : jsonEncode(body);

    http.Response response = await _perform(
      method: method,
      uri: uri,
      correlationId: correlationId,
      encodedBody: encodedBody,
      idempotencyKey: idempotencyKey,
      expectedVersion: expectedVersion,
      token: await _tokens.current(),
    );

    // One retry, and only after a refresh. Repeating a mutation is safe here
    // for exactly one reason: the same idempotency key goes out again, so the
    // platform either replays its stored response or performs the effect once.
    // Without that key this retry would be a duplicate-order generator.
    if (response.statusCode == 401) {
      final String? refreshed = await _tokens.refresh();
      if (refreshed != null) {
        response = await _perform(
          method: method,
          uri: uri,
          correlationId: correlationId,
          encodedBody: encodedBody,
          idempotencyKey: idempotencyKey,
          expectedVersion: expectedVersion,
          token: refreshed,
        );
      }
    }

    clock.stop();
    final String responseCorrelationId =
        _header(response, correlationIdHeader) ?? correlationId;
    final bool replayed = _header(response, idempotencyReplayedHeader) == 'true';

    if (response.statusCode >= 400) {
      final ApiException failure = _failureFrom(response, responseCorrelationId);
      _telemetry.record(
        ApiCallRecord(
          method: method,
          redactedPath: redactPath(uri.path),
          durationMs: clock.elapsedMilliseconds,
          status: response.statusCode,
          correlationId: responseCorrelationId,
          errorCode: failure.code.value,
          replayed: replayed,
        ),
      );
      throw failure;
    }

    _telemetry.record(
      ApiCallRecord(
        method: method,
        redactedPath: redactPath(uri.path),
        durationMs: clock.elapsedMilliseconds,
        status: response.statusCode,
        correlationId: responseCorrelationId,
        replayed: replayed,
      ),
    );

    return ApiResponse<T>(
      value: decode(_decodeJsonObject(response)),
      status: response.statusCode,
      version: parseETag(response.headers['etag']),
      correlationId: responseCorrelationId,
      replayed: replayed,
    );
  }

  Future<http.Response> _perform({
    required String method,
    required Uri uri,
    required String correlationId,
    required String? encodedBody,
    required IdempotencyKey? idempotencyKey,
    required int? expectedVersion,
    required String? token,
  }) async {
    final http.Request request = http.Request(method, uri)
      ..headers.addAll(<String, String>{
        'Accept': 'application/json, application/problem+json',
        correlationIdHeader: correlationId,
        if (token != null) 'Authorization': 'Bearer $token',
        if (encodedBody != null) 'Content-Type': 'application/json',
        if (idempotencyKey != null) idempotencyKeyHeader: idempotencyKey.value,
        if (expectedVersion != null) 'If-Match': formatETag(expectedVersion),
      });
    if (encodedBody != null) {
      request.body = encodedBody;
    }

    try {
      final http.StreamedResponse streamed = await _http
          .send(request)
          .timeout(_timeout);
      return await http.Response.fromStream(streamed);
    } on TimeoutException {
      throw ApiTransportException('timeout', correlationId: correlationId);
    } on http.ClientException catch (failure) {
      // `failure.message` is the transport's own text — "Connection closed",
      // "Failed host lookup" — and carries no request content, so it is safe to
      // keep. The URI is deliberately not attached: it holds identifiers.
      throw ApiTransportException(failure.message, correlationId: correlationId);
    }
  }

  Uri _resolve(String path, Map<String, String>? query) {
    final String basePath = _baseUri.path.endsWith('/')
        ? _baseUri.path.substring(0, _baseUri.path.length - 1)
        : _baseUri.path;
    final String suffix = path.startsWith('/') ? path : '/$path';
    return _baseUri.replace(
      path: '$basePath$suffix',
      queryParameters: (query == null || query.isEmpty) ? null : query,
    );
  }

  static String? _header(http.BaseResponse response, String name) {
    final String wanted = name.toLowerCase();
    for (final MapEntry<String, String> entry in response.headers.entries) {
      if (entry.key.toLowerCase() == wanted) {
        return entry.value;
      }
    }
    return null;
  }

  ApiException _failureFrom(http.Response response, String? correlationId) {
    ProblemDetails problem;
    final String contentType =
        response.headers['content-type']?.toLowerCase() ?? '';
    if (contentType.contains('problem+json') || contentType.contains('json')) {
      try {
        final Object? decoded = jsonDecode(response.body);
        problem = decoded is Map<String, Object?>
            ? ProblemDetails.fromJson(
                decoded,
                transportStatus: response.statusCode,
              )
            : ProblemDetails.unparseable(response.statusCode);
      } on FormatException {
        problem = ProblemDetails.unparseable(response.statusCode);
      }
    } else {
      // An HTML error page from a gateway is not Problem Details. Saying so is
      // more useful than inventing a code the platform never sent.
      problem = ProblemDetails.unparseable(response.statusCode);
    }

    if (problem.correlationId == null && correlationId != null) {
      problem = ProblemDetails(
        status: problem.status,
        code: problem.code,
        type: problem.type,
        title: problem.title,
        detail: problem.detail,
        instance: problem.instance,
        correlationId: correlationId,
        errors: problem.errors,
        extensions: problem.extensions,
      );
    }

    return ApiException(
      problem,
      retryAfter: parseRetryAfter(response.headers['retry-after']),
    );
  }

  Map<String, Object?> _decodeJsonObject(http.Response response) {
    if (response.statusCode == 204 || response.body.isEmpty) {
      return const <String, Object?>{};
    }
    final Object? decoded = jsonDecode(response.body);
    if (decoded is! Map<String, Object?>) {
      throw const ApiTransportException('response body was not a JSON object');
    }
    return decoded;
  }

  /// `W/"7"` — the weak validator the platform's `AggregateVersion` renders.
  static String formatETag(int version) => 'W/"$version"';

  /// Reads a version out of an `ETag`, tolerating both weak and strong forms.
  static int? parseETag(String? header) {
    if (header == null || header.isEmpty) return null;
    String value = header.trim();
    if (value.startsWith('W/')) {
      value = value.substring(2);
    }
    return int.tryParse(value.replaceAll('"', '').trim());
  }

  /// `Retry-After`, delta-seconds form only.
  ///
  /// The HTTP-date form is permitted by the specification and is not parsed
  /// here: honouring it needs a trusted clock, and a phone's clock is not one.
  /// An unparseable value yields null, and the caller falls back to its own
  /// backoff rather than retrying immediately.
  static Duration? parseRetryAfter(String? header) {
    if (header == null) return null;
    final int? seconds = int.tryParse(header.trim());
    return seconds == null ? null : Duration(seconds: seconds);
  }

  static final RegExp _uuid = RegExp(
    r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}',
  );
  static final RegExp _numericSegment = RegExp(r'^\d+$');

  /// Strips identifiers out of a path so it can be recorded (ADR 0029).
  ///
  /// `/api/v1/storefront/tenants/018f…/brands/019a…/orders/01b2…` becomes
  /// `/api/v1/storefront/tenants/{id}/brands/{id}/orders/{id}`. What is left is
  /// the shape of the call, which is what telemetry is for, and none of the
  /// data, which is what it must never hold.
  static String redactPath(String path) => path
      .split('/')
      .map(
        (String segment) =>
            _uuid.hasMatch(segment) || _numericSegment.hasMatch(segment)
            ? '{id}'
            : segment,
      )
      .join('/');
}
