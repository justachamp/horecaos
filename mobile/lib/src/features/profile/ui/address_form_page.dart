import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../../api/idempotency_key.dart';
import '../../../design/qoida_theme.dart';
import '../../../design/qoida_tokens.dart';
import '../../../l10n/generated/app_localizations.dart';
import '../data/saved_address.dart';
import '../data/saved_address_repository.dart';
import 'profile_scope.dart';
import 'profile_widgets.dart';

/// Adding or correcting one saved address.
///
/// **The landmark is a first-class field.** It sits directly under the street
/// line, with its own explanation, above the entrance and floor — not at the
/// bottom under "anything else". For a large share of addresses in this market
/// the ориентир is the only thing that finds the building, and a form that
/// treats it as an afterthought produces addresses that need a phone call.
///
/// **A coordinate is optional, and its absence has two meanings.** The form
/// makes the customer say which one, because ADR 0015's `coordinate_source`
/// distinguishes an address nobody has geocoded yet — worth retrying — from one
/// that genuinely has no point, which must never be re-queued. A customer
/// describing a mahalla house by its landmark is the second, and this form lets
/// them finish without a pin.
///
/// **There is no map here, and that is stated rather than hidden.** ADR 0035
/// names the Yandex MapKit binding as the weakest dependency in the stack and it
/// is not in this build. So the form never writes a point: it produces
/// `NOT_GEOCODED` or `LANDMARK_ONLY`, and an address that already carries a
/// point keeps it, along with whoever placed it. Offering a "drop a pin" control
/// with no map behind it, or relabelling somebody else's pin as the customer's,
/// would both be the client claiming a provenance it does not have.
class AddressFormPage extends StatefulWidget {
  const AddressFormPage({super.key, this.existing});

  /// The address being corrected, or null when adding one.
  final SavedAddress? existing;

  @override
  State<AddressFormPage> createState() => _AddressFormPageState();
}

class _AddressFormPageState extends State<AddressFormPage> {
  late final TextEditingController _label;
  late final TextEditingController _line1;
  late final TextEditingController _landmark;
  late final TextEditingController _entrance;
  late final TextEditingController _floor;
  late final TextEditingController _apartment;
  late final TextEditingController _district;
  late final TextEditingController _city;
  late final TextEditingController _instructions;

  /// Which of the two no-pin meanings the customer chose.
  late CoordinateSource _noPinChoice;

  /// Held across retries.
  ///
  /// The key belongs to the intent — "save this address" — and not to the HTTP
  /// call. A save that fails on a dropped connection may well have reached the
  /// platform, and retrying with a fresh key is how a customer ends up with the
  /// same address saved twice. It is cleared only after a save that is known to
  /// have succeeded.
  IdempotencyKey? _saveKey;

  bool _saving = false;
  AddressDraftProblem? _problem;
  Object? _failure;

  @override
  void initState() {
    super.initState();
    final SavedAddress? existing = widget.existing;
    final AddressFields fields = existing?.fields ?? const AddressFields();
    _label = TextEditingController(text: existing?.label ?? '');
    _line1 = TextEditingController(text: fields.line1 ?? '');
    _landmark = TextEditingController(text: fields.landmark ?? '');
    _entrance = TextEditingController(text: fields.entrance ?? '');
    _floor = TextEditingController(text: fields.floor ?? '');
    _apartment = TextEditingController(text: fields.apartment ?? '');
    _district = TextEditingController(text: fields.district ?? '');
    _city = TextEditingController(text: fields.city ?? '');
    _instructions = TextEditingController(
      text: existing?.deliveryInstructions ?? '',
    );

    // A new address defaults to NOT_GEOCODED and not to LANDMARK_ONLY. The
    // default has to be the honest one: nothing has been attempted yet, and
    // LANDMARK_ONLY is a statement about the place that only the customer can
    // make.
    _noPinChoice =
        existing != null &&
            existing.coordinateSource == CoordinateSource.landmarkOnly
        ? CoordinateSource.landmarkOnly
        : CoordinateSource.notGeocoded;
  }

  @override
  void dispose() {
    // Every controller holds address text. Disposing them is ordinary Flutter
    // hygiene and it is also the moment this screen stops holding personal
    // data.
    for (final TextEditingController controller in <TextEditingController>[
      _label,
      _line1,
      _landmark,
      _entrance,
      _floor,
      _apartment,
      _district,
      _city,
      _instructions,
    ]) {
      controller.dispose();
    }
    super.dispose();
  }

  /// Whether the address being edited already carries a point somebody placed.
  bool get _keepsExistingPoint => widget.existing?.hasPoint ?? false;

  AddressDraft get _draft {
    final SavedAddress? existing = widget.existing;
    final AddressFields fields = AddressFields(
      line1: _line1.text,
      // Not shown by this form; carried so that saving does not erase what
      // another surface wrote.
      line2: existing?.fields.line2,
      city: _city.text,
      district: _district.text,
      postalCode: existing?.fields.postalCode,
      entrance: _entrance.text,
      floor: _floor.text,
      apartment: _apartment.text,
      landmark: _landmark.text,
    );

    if (_keepsExistingPoint) {
      return AddressDraft(
        label: _label.text,
        fields: fields,
        deliveryInstructions: _instructions.text,
        latitude: existing!.latitude,
        longitude: existing.longitude,
        coordinateSource: existing.coordinateSource,
      );
    }

    return AddressDraft(
      label: _label.text,
      fields: fields,
      deliveryInstructions: _instructions.text,
      coordinateSource: _noPinChoice,
    );
  }

  Future<void> _save() async {
    final AddressDraft draft = _draft;
    final AddressDraftProblem? problem = draft.problem;
    if (problem != null) {
      setState(() {
        _problem = problem;
        _failure = null;
      });
      return;
    }

    final SavedAddressRepository repository = await ProfileScope.of(
      context,
    ).addresses();
    if (!mounted) {
      return;
    }

    setState(() {
      _problem = null;
      _failure = null;
      _saving = true;
      _saveKey ??= IdempotencyKey.generate();
    });

    try {
      final SavedAddress? existing = widget.existing;
      if (existing == null) {
        await repository.add(draft, idempotencyKey: _saveKey!);
      } else {
        await repository.replace(
          existing.id,
          draft,
          idempotencyKey: _saveKey!,
        );
      }
      _saveKey = null;
      if (mounted) {
        context.pop(true);
      }
    } on Object catch (failure) {
      if (mounted) {
        setState(() {
          _saving = false;
          _failure = failure;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);
    final QoidaTokens tokens = context.qoida;
    final TextTheme text = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.existing == null
              ? l10n.profileAddressNewTitle
              : l10n.profileAddressEditTitle,
        ),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(QoidaGeometry.spaceMd),
          children: <Widget>[
            _Field(
              controller: _label,
              label: l10n.profileAddressFieldLabel,
              hint: l10n.profileAddressFieldLabelHint,
              textInputAction: TextInputAction.next,
            ),
            _Field(
              controller: _line1,
              label: l10n.profileAddressFieldStreet,
              textInputAction: TextInputAction.next,
              errorText: _problem == AddressDraftProblem.noStreetAndNoLandmark
                  ? l10n.profileAddressNothingToFind
                  : null,
            ),

            // The landmark, immediately under the street and above everything
            // else. Its position on the screen is the design decision.
            _Field(
              controller: _landmark,
              label: l10n.profileAddressFieldLandmark,
              hint: l10n.profileAddressFieldLandmarkHint,
              helper: l10n.profileAddressFieldLandmarkHelp,
              maxLines: 2,
              errorText: _problem == AddressDraftProblem.landmarkMissing
                  ? l10n.profileAddressLandmarkNeeded
                  : null,
              onChanged: (String _) {
                if (_problem != null) {
                  setState(() => _problem = null);
                }
              },
            ),

            const SizedBox(height: QoidaGeometry.spaceSm),
            Text(l10n.profileAddressInsideTitle, style: text.titleSmall),
            const SizedBox(height: QoidaGeometry.spaceSm),
            _Field(
              controller: _entrance,
              label: l10n.profileAddressFieldEntrance,
              keyboardType: TextInputType.text,
              textInputAction: TextInputAction.next,
            ),
            _Field(
              controller: _floor,
              label: l10n.profileAddressFieldFloor,
              textInputAction: TextInputAction.next,
            ),
            _Field(
              controller: _apartment,
              label: l10n.profileAddressFieldApartment,
              textInputAction: TextInputAction.next,
            ),
            _Field(
              controller: _district,
              label: l10n.profileAddressFieldDistrict,
              textInputAction: TextInputAction.next,
            ),
            _Field(
              controller: _city,
              label: l10n.profileAddressFieldCity,
              textInputAction: TextInputAction.next,
            ),
            _Field(
              controller: _instructions,
              label: l10n.profileAddressFieldInstructions,
              maxLines: 2,
            ),

            const SizedBox(height: QoidaGeometry.spaceMd),
            Text(l10n.profileAddressPinTitle, style: text.titleSmall),
            const SizedBox(height: QoidaGeometry.spaceSm),
            if (_keepsExistingPoint)
              _PinKept(l10n: l10n)
            else
              _NoPinChoice(
                choice: _noPinChoice,
                onChanged: (CoordinateSource choice) => setState(() {
                  _noPinChoice = choice;
                  _problem = null;
                }),
              ),

            if (_failure != null) ...<Widget>[
              const SizedBox(height: QoidaGeometry.spaceMd),
              Text(
                l10n.profileAddressSaveFailed,
                style: text.bodyMedium?.copyWith(color: tokens.errorInk),
              ),
            ],

            const SizedBox(height: QoidaGeometry.spaceLg),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: _saving ? null : _save,
                child: Text(l10n.profileAddressSave),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// One text field, with the theme's decoration and nothing invented.
class _Field extends StatelessWidget {
  const _Field({
    required this.controller,
    required this.label,
    this.hint,
    this.helper,
    this.errorText,
    this.maxLines = 1,
    this.keyboardType,
    this.textInputAction,
    this.onChanged,
  });

  final TextEditingController controller;
  final String label;
  final String? hint;
  final String? helper;
  final String? errorText;
  final int maxLines;
  final TextInputType? keyboardType;
  final TextInputAction? textInputAction;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: QoidaGeometry.spaceMd),
      child: TextField(
        controller: controller,
        maxLines: maxLines,
        keyboardType: keyboardType,
        textInputAction: textInputAction,
        onChanged: onChanged,
        // No autofill hints and no `AutofillGroup`. An address is personal data
        // and the platform's own copy is inside an encrypted document; handing
        // it to the operating system's autofill service would put it somewhere
        // this application does not control (ADR 0029).
        autofillHints: const <String>[],
        decoration: InputDecoration(
          labelText: label,
          hintText: hint,
          helperText: helper,
          helperMaxLines: 3,
          errorText: errorText,
          errorMaxLines: 3,
        ),
      ),
    );
  }
}

/// The two meanings of a missing pin, as a choice the customer makes.
class _NoPinChoice extends StatelessWidget {
  const _NoPinChoice({required this.choice, required this.onChanged});

  final CoordinateSource choice;
  final ValueChanged<CoordinateSource> onChanged;

  @override
  Widget build(BuildContext context) {
    final AppLocalizations l10n = AppLocalizations.of(context);

    return RadioGroup<CoordinateSource>(
      groupValue: choice,
      onChanged: (CoordinateSource? next) {
        if (next != null) {
          onChanged(next);
        }
      },
      child: Column(
        children: <Widget>[
          const ProfileDivider(),
          ProfileRow(
            title: l10n.profileAddressPinLater,
            detail: l10n.profileAddressPinLaterHelp,
            trailing: const Radio<CoordinateSource>(
              value: CoordinateSource.notGeocoded,
            ),
            onTap: () => onChanged(CoordinateSource.notGeocoded),
          ),
          const ProfileDivider(),
          ProfileRow(
            title: l10n.profileAddressPinNever,
            detail: l10n.profileAddressPinNeverHelp,
            trailing: const Radio<CoordinateSource>(
              value: CoordinateSource.landmarkOnly,
            ),
            onTap: () => onChanged(CoordinateSource.landmarkOnly),
          ),
          const ProfileDivider(),
        ],
      ),
    );
  }
}

/// An address that already carries a point.
class _PinKept extends StatelessWidget {
  const _PinKept({required this.l10n});

  final AppLocalizations l10n;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: <Widget>[
        const ProfileDivider(),
        ProfileRow(
          title: l10n.profileAddressPinSet,
          detail: l10n.profileAddressPinKeptHelp,
        ),
        const ProfileDivider(),
      ],
    );
  }
}
