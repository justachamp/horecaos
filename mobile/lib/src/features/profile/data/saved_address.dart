/// A saved delivery address, and the parts of it that actually find a door.
///
/// **Everything in this file is personal data under ADR 0029**, with one
/// exception: the coordinate, which the platform keeps in clear because a
/// courier cannot be routed to a ciphertext and a point identifies a building
/// rather than a person. The consequences for this client are concrete and they
/// are enforced here rather than left to a reviewer's memory:
///
/// - **Nothing in this file is written to durable storage.** There is no cache,
///   no `flutter_secure_storage` key, no file. An address exists in the memory
///   of the screen showing it and dies with that screen.
/// - **`toString` redacts.** A crash reporter calls `toString`, and an address
///   in a crash report is an address in a third party's database. The overrides
///   below print the identifier and the shape, never the values.
/// - **Nothing here reaches telemetry.** `ApiTelemetry` accepts a method, a
///   redacted path, a status and a duration, and this feature emits no
///   analytics events of its own.
library;

/// The address document (ADR 0015).
///
/// The platform encrypts these nine fields as one JSON document rather than as
/// nine columns, because no query needs a street name.
///
/// [entrance] (подъезд), [floor] (этаж), [apartment] (квартира) and [landmark]
/// (ориентир) are structured fields and not an afterthought folded into a
/// street line. A courier standing in front of a Soviet-era block cannot find a
/// flat from a street line, and for a large share of addresses in this market
/// the landmark is the only thing that locates the building at all.
final class AddressFields {
  const AddressFields({
    this.line1,
    this.line2,
    this.city,
    this.district,
    this.postalCode,
    this.entrance,
    this.floor,
    this.apartment,
    this.landmark,
  });

  /// Street and house number.
  final String? line1;

  /// Block, building, or a second line of the street address.
  final String? line2;

  final String? city;
  final String? district;

  /// Carried through unchanged.
  ///
  /// The form does not show it: postal codes are not how anything is delivered
  /// here. It stays on the record so that editing an address does not silently
  /// erase a value some other surface wrote — a form that round-trips only the
  /// fields it renders is a form that deletes data.
  final String? postalCode;

  /// подъезд.
  final String? entrance;

  /// этаж.
  final String? floor;

  /// квартира.
  final String? apartment;

  /// ориентир — "opposite the pharmacy, blue gate".
  final String? landmark;

  bool get hasLandmark => (landmark ?? '').trim().isNotEmpty;

  AddressFields copyWith({
    String? line1,
    String? line2,
    String? city,
    String? district,
    String? postalCode,
    String? entrance,
    String? floor,
    String? apartment,
    String? landmark,
  }) => AddressFields(
    line1: line1 ?? this.line1,
    line2: line2 ?? this.line2,
    city: city ?? this.city,
    district: district ?? this.district,
    postalCode: postalCode ?? this.postalCode,
    entrance: entrance ?? this.entrance,
    floor: floor ?? this.floor,
    apartment: apartment ?? this.apartment,
    landmark: landmark ?? this.landmark,
  );

  static AddressFields fromJson(Map<String, Object?> json) => AddressFields(
    line1: json['line1'] as String?,
    line2: json['line2'] as String?,
    city: json['city'] as String?,
    district: json['district'] as String?,
    postalCode: json['postalCode'] as String?,
    entrance: json['entrance'] as String?,
    floor: json['floor'] as String?,
    apartment: json['apartment'] as String?,
    landmark: json['landmark'] as String?,
  );

  /// Omits absent fields rather than sending nulls.
  ///
  /// The server stores this document verbatim, and a document full of explicit
  /// nulls is a document that says "the customer cleared their flat number"
  /// when they simply never had one.
  Map<String, Object?> toJson() => <String, Object?>{
    if (_present(line1)) 'line1': line1!.trim(),
    if (_present(line2)) 'line2': line2!.trim(),
    if (_present(city)) 'city': city!.trim(),
    if (_present(district)) 'district': district!.trim(),
    if (_present(postalCode)) 'postalCode': postalCode!.trim(),
    if (_present(entrance)) 'entrance': entrance!.trim(),
    if (_present(floor)) 'floor': floor!.trim(),
    if (_present(apartment)) 'apartment': apartment!.trim(),
    if (_present(landmark)) 'landmark': landmark!.trim(),
  };

  static bool _present(String? value) => value != null && value.trim().isNotEmpty;

  /// The names of the fields that carry a value. Never the values.
  ///
  /// This is what a diagnostic is allowed to say about an address.
  List<String> get populatedFieldNames => <String>[
    if (_present(line1)) 'line1',
    if (_present(line2)) 'line2',
    if (_present(city)) 'city',
    if (_present(district)) 'district',
    if (_present(postalCode)) 'postalCode',
    if (_present(entrance)) 'entrance',
    if (_present(floor)) 'floor',
    if (_present(apartment)) 'apartment',
    if (_present(landmark)) 'landmark',
  ];

  /// Redacted. See the library comment: a crash reporter calls this.
  @override
  String toString() => 'AddressFields(${populatedFieldNames.length} fields set)';
}

/// Why an address does or does not carry a point (ADR 0015).
///
/// The distinction this enum exists for: a null coordinate had two meanings and
/// no way to tell them apart. [notGeocoded] is retryable and [landmarkOnly] is
/// finished, and a backfill that cannot tell them apart either re-queries every
/// landmark address forever or gives up on the ones a provider outage left
/// empty.
enum CoordinateSource {
  /// Not attempted, or attempted and failed. Worth retrying.
  notGeocoded('NOT_GEOCODED', requiresPoint: false, customerMayChoose: true),

  /// Deliberately no point. A mahalla house given by its ориентир is a complete
  /// address in this market; dispatch reaches it by calling.
  landmarkOnly('LANDMARK_ONLY', requiresPoint: false, customerMayChoose: true),

  /// Resolved by the platform's geocoding port. Written by the server.
  geocoder('GEOCODER', requiresPoint: true, customerMayChoose: false),

  /// The customer dropped a pin.
  customerPin('CUSTOMER_PIN', requiresPoint: true, customerMayChoose: true),

  /// An operator placed the pin, usually while on the phone.
  operatorPin('OPERATOR_PIN', requiresPoint: true, customerMayChoose: false),

  /// Predates the column. Readable, never writable — the platform refuses it on
  /// a new address, and so does this client.
  legacyUnsourced('LEGACY_UNSOURCED', requiresPoint: true, customerMayChoose: false),

  /// A value this build does not know.
  ///
  /// ADR 0031 evolves enums additively within a major version, so a client that
  /// threw on an unrecognised value would turn a compatible server change into
  /// a crash on a screen the customer cannot leave. An unknown source renders as
  /// "no pin" and is never sent back.
  unknown('', requiresPoint: false, customerMayChoose: false);

  const CoordinateSource(
    this.wire, {
    required this.requiresPoint,
    required this.customerMayChoose,
  });

  /// The `SCREAMING_SNAKE_CASE` value on the wire.
  final String wire;

  /// Whether a coordinate pair must accompany this source. The platform checks
  /// this in both directions and so does the form, so a customer sees a field
  /// error rather than a 400.
  final bool requiresPoint;

  /// Whether this application may write it.
  final bool customerMayChoose;

  static CoordinateSource fromWire(String? value) {
    for (final CoordinateSource source in CoordinateSource.values) {
      if (source != CoordinateSource.unknown && source.wire == value) {
        return source;
      }
    }
    return CoordinateSource.unknown;
  }
}

/// One saved address as the platform returns it.
final class SavedAddress {
  const SavedAddress({
    required this.id,
    required this.fields,
    required this.coordinateSource,
    this.label,
    this.deliveryInstructions,
    this.latitude,
    this.longitude,
  });

  final String id;

  /// "Home", "work" — the customer's own word for it, and optional.
  final String? label;

  final AddressFields fields;

  /// A note for the courier. Encrypted server-side like the rest.
  final String? deliveryInstructions;

  final double? latitude;
  final double? longitude;

  final CoordinateSource coordinateSource;

  bool get hasPoint => latitude != null && longitude != null;

  static SavedAddress fromJson(Map<String, Object?> json) {
    final Object? id = json['id'];
    if (id is! String || id.isEmpty) {
      throw const FormatException('Address carried no id');
    }
    final Object? rawFields = json['fields'];
    return SavedAddress(
      id: id,
      label: json['label'] as String?,
      fields: rawFields is Map<String, Object?>
          ? AddressFields.fromJson(rawFields)
          : const AddressFields(),
      deliveryInstructions: json['deliveryInstructions'] as String?,
      latitude: _double(json['latitude']),
      longitude: _double(json['longitude']),
      coordinateSource: CoordinateSource.fromWire(
        json['coordinateSource'] as String?,
      ),
    );
  }

  static double? _double(Object? value) {
    if (value is double) return value;
    if (value is num) return value.toDouble();
    return null;
  }

  /// A draft that starts from this address, for the edit form.
  AddressDraft toDraft() => AddressDraft(
    label: label,
    fields: fields,
    deliveryInstructions: deliveryInstructions,
    latitude: latitude,
    longitude: longitude,
    coordinateSource: coordinateSource,
  );

  /// Redacted. The identifier is a row reference; the rest is where somebody
  /// lives.
  @override
  String toString() => 'SavedAddress($id, ${coordinateSource.name})';
}

/// What the customer typed, on its way to the server.
final class AddressDraft {
  const AddressDraft({
    required this.fields,
    required this.coordinateSource,
    this.label,
    this.deliveryInstructions,
    this.latitude,
    this.longitude,
  });

  final String? label;
  final AddressFields fields;
  final String? deliveryInstructions;
  final double? latitude;
  final double? longitude;
  final CoordinateSource coordinateSource;

  /// Why this draft cannot be sent, or null when it can.
  ///
  /// The same rules the platform's `requireCoordinatesMatchSource` applies,
  /// checked here first so the customer gets a field error instead of a 400 —
  /// and one rule the platform does not have: a [CoordinateSource.landmarkOnly]
  /// address with no landmark. The server would accept it, and it would be an
  /// address with neither a point nor a description, which is not something
  /// anybody can deliver to.
  AddressDraftProblem? get problem {
    if (!fields.hasLandmark && !AddressFields._present(fields.line1)) {
      return AddressDraftProblem.noStreetAndNoLandmark;
    }
    if (coordinateSource == CoordinateSource.legacyUnsourced ||
        coordinateSource == CoordinateSource.unknown) {
      // The platform refuses LEGACY_UNSOURCED on a new address so that no row
      // can claim an unknown origin, and an unrecognised source has no wire
      // value to send. Neither is a thing this client may write. Note that
      // GEOCODER and OPERATOR_PIN are *not* refused here: an address being
      // edited keeps the point somebody else placed, and rewriting its
      // provenance to CUSTOMER_PIN would be this client claiming credit for a
      // pin it did not place.
      return AddressDraftProblem.sourceNotWritable;
    }
    if ((latitude == null) != (longitude == null)) {
      // Half a coordinate points at the equator or the prime meridian.
      return AddressDraftProblem.halfACoordinate;
    }
    final bool hasPoint = latitude != null && longitude != null;
    if (coordinateSource.requiresPoint && !hasPoint) {
      return AddressDraftProblem.pointMissing;
    }
    if (!coordinateSource.requiresPoint && hasPoint) {
      return AddressDraftProblem.pointUnexpected;
    }
    if (coordinateSource == CoordinateSource.landmarkOnly &&
        !fields.hasLandmark) {
      return AddressDraftProblem.landmarkMissing;
    }
    return null;
  }

  bool get isSendable => problem == null;

  /// The body of `POST .../addresses`.
  Map<String, Object?> toJson() => <String, Object?>{
    'label': _trimmedOrNull(label),
    'fields': fields.toJson(),
    'deliveryInstructions': _trimmedOrNull(deliveryInstructions),
    'latitude': latitude,
    'longitude': longitude,
    'coordinateSource': coordinateSource.wire,
  };

  AddressDraft copyWith({
    String? label,
    AddressFields? fields,
    String? deliveryInstructions,
    double? latitude,
    double? longitude,
    CoordinateSource? coordinateSource,
    bool clearPoint = false,
  }) => AddressDraft(
    label: label ?? this.label,
    fields: fields ?? this.fields,
    deliveryInstructions: deliveryInstructions ?? this.deliveryInstructions,
    latitude: clearPoint ? null : (latitude ?? this.latitude),
    longitude: clearPoint ? null : (longitude ?? this.longitude),
    coordinateSource: coordinateSource ?? this.coordinateSource,
  );

  static String? _trimmedOrNull(String? value) {
    final String? trimmed = value?.trim();
    return (trimmed == null || trimmed.isEmpty) ? null : trimmed;
  }

  /// Redacted, for the same reason as everything else here.
  @override
  String toString() => 'AddressDraft(${coordinateSource.name})';
}

/// Why a draft cannot be sent.
///
/// A code rather than a message: the message is chosen from the localisations,
/// exactly as ADR 0031 has clients branch on an error code and never on prose.
enum AddressDraftProblem {
  /// Neither a street line nor a landmark. There is nothing to find.
  noStreetAndNoLandmark,

  /// A landmark-only address with no landmark in it.
  landmarkMissing,

  /// A source only the server or an operator may write.
  sourceNotWritable,

  /// A latitude without a longitude, or the reverse.
  halfACoordinate,

  /// The source claims a point and none was supplied.
  pointMissing,

  /// The source means there is no point, and one was supplied.
  pointUnexpected,
}
