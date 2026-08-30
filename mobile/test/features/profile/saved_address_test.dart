import 'package:flutter_test/flutter_test.dart';
import 'package:horecaos_mobile/src/features/profile/data/saved_address.dart';

/// The address model, and the rules that decide whether a draft may be sent.
///
/// These are the rules ADR 0015 encodes in `coordinate_source` and the platform
/// enforces in `requireCoordinatesMatchSource`. They are asserted here because
/// getting them wrong is not a cosmetic failure: a landmark address recorded as
/// `NOT_GEOCODED` is re-queued for geocoding forever, and one recorded as
/// `LANDMARK_ONLY` while a geocoder is still working on it is never retried.
void main() {
  const AddressFields landmarkAddress = AddressFields(
    city: 'Toshkent',
    landmark: 'Dorixona ro\'parasida, ko\'k darvoza',
  );

  group('a landmark-only address', () {
    test('is sendable with no coordinate at all', () {
      const AddressDraft draft = AddressDraft(
        fields: landmarkAddress,
        coordinateSource: CoordinateSource.landmarkOnly,
      );

      expect(draft.problem, isNull);
      expect(draft.isSendable, isTrue);
    });

    test('sends no latitude and no longitude', () {
      const AddressDraft draft = AddressDraft(
        fields: landmarkAddress,
        coordinateSource: CoordinateSource.landmarkOnly,
      );

      final Map<String, Object?> body = draft.toJson();
      expect(body['latitude'], isNull);
      expect(body['longitude'], isNull);
      expect(body['coordinateSource'], 'LANDMARK_ONLY');
    });

    test('carries the landmark into the encrypted document', () {
      const AddressDraft draft = AddressDraft(
        fields: landmarkAddress,
        coordinateSource: CoordinateSource.landmarkOnly,
      );

      final Map<String, Object?> fields =
          draft.toJson()['fields']! as Map<String, Object?>;
      expect(fields['landmark'], "Dorixona ro'parasida, ko'k darvoza");
    });

    test('is refused when there is no landmark in it', () {
      // Neither a point nor a description is not an address anybody can
      // deliver to. The platform would accept this; the client will not.
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(line1: 'Amir Temur 12'),
        coordinateSource: CoordinateSource.landmarkOnly,
      );

      expect(draft.problem, AddressDraftProblem.landmarkMissing);
    });

    test('is refused when a coordinate is attached anyway', () {
      const AddressDraft draft = AddressDraft(
        fields: landmarkAddress,
        coordinateSource: CoordinateSource.landmarkOnly,
        latitude: 41.31,
        longitude: 69.24,
      );

      expect(draft.problem, AddressDraftProblem.pointUnexpected);
    });
  });

  group('an address with no landmark', () {
    test('is sendable as not yet geocoded', () {
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(line1: 'Amir Temur 12', city: 'Toshkent'),
        coordinateSource: CoordinateSource.notGeocoded,
      );

      expect(draft.problem, isNull);
    });

    test('is refused with neither a street line nor a landmark', () {
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(city: 'Toshkent'),
        coordinateSource: CoordinateSource.notGeocoded,
      );

      expect(draft.problem, AddressDraftProblem.noStreetAndNoLandmark);
    });
  });

  group('coordinates and their source', () {
    test('half a coordinate is refused', () {
      // A latitude with no longitude points at the prime meridian.
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(line1: 'Amir Temur 12'),
        coordinateSource: CoordinateSource.customerPin,
        latitude: 41.31,
      );

      expect(draft.problem, AddressDraftProblem.halfACoordinate);
    });

    test('a source that claims a point is refused without one', () {
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(line1: 'Amir Temur 12'),
        coordinateSource: CoordinateSource.customerPin,
      );

      expect(draft.problem, AddressDraftProblem.pointMissing);
    });

    test('a point somebody else placed keeps its own provenance', () {
      // An address being corrected does not have its GEOCODER pin relabelled
      // as the customer's. It stays sendable and stays honest.
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(line1: 'Amir Temur 12'),
        coordinateSource: CoordinateSource.geocoder,
        latitude: 41.31,
        longitude: 69.24,
      );

      expect(draft.problem, isNull);
      expect(draft.toJson()['coordinateSource'], 'GEOCODER');
    });

    test('the migration-only source is refused', () {
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(line1: 'Amir Temur 12'),
        coordinateSource: CoordinateSource.legacyUnsourced,
        latitude: 41.31,
        longitude: 69.24,
      );

      expect(draft.problem, AddressDraftProblem.sourceNotWritable);
    });

    test('an unrecognised source decodes rather than throwing', () {
      // ADR 0031 evolves enums additively. A client that threw here would turn
      // a compatible server change into a crash on a screen the customer
      // cannot leave.
      expect(
        CoordinateSource.fromWire('SOMETHING_ADDED_LATER'),
        CoordinateSource.unknown,
      );
      expect(CoordinateSource.fromWire(null), CoordinateSource.unknown);
    });

    test('an unrecognised source is never sent back', () {
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(line1: 'Amir Temur 12'),
        coordinateSource: CoordinateSource.unknown,
      );

      expect(draft.problem, AddressDraftProblem.sourceNotWritable);
    });
  });

  group('the wire shape', () {
    test('absent fields are omitted rather than sent as null', () {
      // An explicit null in the document says "the customer cleared their flat
      // number" when they simply never had one.
      const AddressDraft draft = AddressDraft(
        fields: AddressFields(line1: ' Amir Temur 12 ', apartment: '   '),
        coordinateSource: CoordinateSource.notGeocoded,
      );

      final Map<String, Object?> fields =
          draft.toJson()['fields']! as Map<String, Object?>;
      expect(fields.containsKey('apartment'), isFalse);
      expect(fields['line1'], 'Amir Temur 12');
    });

    test('an address decodes from what the platform returns', () {
      final SavedAddress address = SavedAddress.fromJson(<String, Object?>{
        'id': 'a-1',
        'label': 'Uy',
        'fields': <String, Object?>{
          'line1': 'Amir Temur 12',
          'entrance': '3',
          'floor': '5',
          'landmark': 'Dorixona',
        },
        'deliveryInstructions': 'Call on arrival',
        'latitude': null,
        'longitude': null,
        'coordinateSource': 'LANDMARK_ONLY',
      });

      expect(address.hasPoint, isFalse);
      expect(address.coordinateSource, CoordinateSource.landmarkOnly);
      expect(address.fields.entrance, '3');
      expect(address.fields.hasLandmark, isTrue);
    });

    test('a draft made from a saved address keeps the fields the form hides', () {
      // Round-tripping only the rendered fields is how an edit form deletes
      // data nobody meant to delete.
      final SavedAddress address = SavedAddress.fromJson(<String, Object?>{
        'id': 'a-1',
        'fields': <String, Object?>{
          'line1': 'Amir Temur 12',
          'postalCode': '100000',
        },
        'coordinateSource': 'NOT_GEOCODED',
      });

      expect(address.toDraft().fields.postalCode, '100000');
    });
  });

  group('nothing prints an address', () {
    // A crash reporter calls toString. An address in a crash report is an
    // address in a third party's database (ADR 0029).
    const AddressFields fields = AddressFields(
      line1: 'Amir Temur 12',
      apartment: '44',
      landmark: 'Dorixona ro\'parasida',
    );

    test('the fields print their count and not their values', () {
      final String printed = fields.toString();
      expect(printed, isNot(contains('Amir Temur')));
      expect(printed, isNot(contains('44')));
      expect(printed, isNot(contains('Dorixona')));
      expect(printed, contains('3 fields set'));
    });

    test('a saved address prints its identifier and its pin state only', () {
      const SavedAddress address = SavedAddress(
        id: 'a-1',
        label: 'Uy',
        fields: fields,
        coordinateSource: CoordinateSource.landmarkOnly,
      );

      final String printed = address.toString();
      expect(printed, contains('a-1'));
      expect(printed, isNot(contains('Amir Temur')));
      expect(printed, isNot(contains('Uy')));
    });

    test('a draft prints nothing but its source', () {
      const AddressDraft draft = AddressDraft(
        label: 'Uy',
        fields: fields,
        deliveryInstructions: 'Call on arrival',
        coordinateSource: CoordinateSource.landmarkOnly,
      );

      final String printed = draft.toString();
      expect(printed, isNot(contains('Amir Temur')));
      expect(printed, isNot(contains('Call on arrival')));
    });

    test('the field names are what a diagnostic may say', () {
      expect(fields.populatedFieldNames, <String>[
        'line1',
        'apartment',
        'landmark',
      ]);
    });
  });
}
