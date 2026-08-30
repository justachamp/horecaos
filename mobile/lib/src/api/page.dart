/// One page of a cursor-paginated collection (ADR 0031).
///
/// There is no total and no page number, and there is not going to be one:
/// offset pagination silently skips and duplicates rows while a list is being
/// paged, which in an order feed means a missed order. Screens are designed
/// around continuation.
final class Page<T> {
  const Page({required this.items, required this.nextCursor});

  final List<T> items;

  /// Opaque and signed. Null means the end of the collection.
  ///
  /// It encodes the sort key and the filter set, so changing a filter
  /// mid-iteration fails rather than returning incoherent pages. Never parse
  /// it, never construct one, and never persist one across a session.
  final String? nextCursor;

  bool get hasMore => nextCursor != null;

  static Page<T> fromJson<T>(
    Map<String, Object?> json,
    T Function(Map<String, Object?> item) decodeItem,
  ) {
    final Object? rawItems = json['items'];
    if (rawItems is! List) {
      throw FormatException('Not a page: items missing or not a list');
    }
    return Page<T>(
      items: rawItems
          .whereType<Map<String, Object?>>()
          .map(decodeItem)
          .toList(growable: false),
      nextCursor: json['nextCursor'] as String?,
    );
  }

  @override
  String toString() =>
      'Page(${items.length} items, ${hasMore ? 'more' : 'end'})';
}
