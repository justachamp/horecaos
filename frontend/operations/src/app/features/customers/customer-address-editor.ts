import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { LocationScope } from '../../core/api/operations-paths';
import { TPipe } from '../../core/i18n/t.pipe';
import { AddressPicker, AddressParts, PickedAddress } from '../../shared/ui/map/address-picker';
import { LatLng } from '../../shared/ui/map/map-provider';
import { FALLBACK_MAP_CENTRE, MapRegionService } from '../delivery/map-region';
import {
  CustomerAddressFields,
  CustomerCoordinateSource,
  RevealedCustomerAddress,
} from './customers-api';

/**
 * What the editor hands its host: a complete address as the platform stores it, and whether it may
 * be saved. `latitude`/`longitude`/`coordinateSource` always agree with each other, which the server
 * insists on (`CustomerProfileService#requireCoordinatesMatchSource`): a point with a source that
 * claims one, or no point with a source that claims none.
 */
export interface CustomerAddressDraft {
  readonly label: string;
  readonly fields: CustomerAddressFields;
  readonly latitude: number | null;
  readonly longitude: number | null;
  readonly coordinateSource: CustomerCoordinateSource;
  /** The three fields the platform requires (`line1`, `city`, `district`) are all filled in. */
  readonly valid: boolean;
}

const EMPTY_PARTS: AddressParts = {
  formatted: '',
  street: null,
  house: null,
  locality: null,
  district: null,
  entrance: null,
  floor: null,
  flat: null,
  landmark: null,
};

/**
 * A customer's address, entered or corrected on a map (rows `1.3b` and `5.2c`, ADR 0145): the one
 * editor New order's address pane and the customer's saved-address form share, so the two cannot
 * come to disagree about what an operator may enter or what is saved.
 *
 * **It is `q-address-picker` plus what a customer record needs around it**: a label, the city and
 * district the platform requires (offered from what the provider recognised and overwritable, because
 * the provider's district is not always the one the brand's zones are named for), and, when asked,
 * the second address line and postal code. Entrance, floor, flat and landmark are the picker's own:
 * they are never the provider's, and a courier needs exactly those.
 *
 * **Saving an untouched address changes nothing about its pin.** Until the picker has said
 * something, the draft is the saved address as it came in, point and source included; a text-only
 * edit therefore keeps a customer's own pin as the customer's, which a rebuilt draft would have
 * silently re-labelled as the operator's (row `5.2c` guards exactly this). A point the operator has
 * moved is `OPERATOR_PIN`; a point that is where it was keeps the source it had; a pin taken away
 * leaves `NOT_GEOCODED`, or `LANDMARK_ONLY` when a landmark is all there is. **It can never produce
 * `GEOCODER`**: a provider's answer is a suggestion a person confirmed by looking at it (ADR 0145
 * decision 5), and the picker's types do not allow the source to be named.
 *
 * Nothing here calls the platform to save: the host owns the request, its capability and its
 * version.
 */
@Component({
  selector: 'q-customer-address-editor',
  imports: [AddressPicker, TPipe],
  templateUrl: './customer-address-editor.html',
  styleUrl: './customer-address-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CustomerAddressEditor {
  private readonly regions = inject(MapRegionService);

  /** The branch the operator works at: it selects the lookup path their grant covers. */
  readonly scope = input.required<LocationScope>();
  /** The saved address being edited, or `null` for a new one. */
  readonly initial = input<RevealedCustomerAddress | null>(null);
  /** Also offer the second address line and the postal code, which the customer's own record has. */
  readonly extended = input(false, { transform: booleanAttribute });

  readonly draftChange = output<CustomerAddressDraft>();

  protected readonly label = signal('');
  protected readonly city = signal('');
  protected readonly district = signal('');
  protected readonly line2 = signal('');
  protected readonly postalCode = signal('');
  private readonly picked = signal<PickedAddress | null>(null);
  private cityTouched = false;
  private districtTouched = false;

  /** The saved address as the picker starts from it. */
  protected readonly pickerInitial = computed<PickedAddress | null>(() => {
    const saved = this.initial();
    if (saved === null) {
      return null;
    }
    const point = pointOf(saved);
    return {
      point,
      coordinateSource: point === null ? 'NOT_GEOCODED' : 'OPERATOR_PIN',
      components: {
        formatted: saved.fields.line1,
        street: saved.fields.line1,
        house: null,
        locality: saved.fields.city,
        district: saved.fields.district,
        entrance: saved.fields.entrance ?? null,
        floor: saved.fields.floor ?? null,
        flat: saved.fields.apartment ?? null,
        landmark: saved.fields.landmark ?? null,
      },
    };
  });

  protected readonly region = computed(() => this.regions.primary());
  protected readonly center = computed<LatLng>(
    () =>
      (this.initial() ? pointOf(this.initial()!) : null) ??
      this.region()?.centre ??
      FALLBACK_MAP_CENTRE,
  );

  /** Everything the host needs to save, rebuilt whenever anything the person can touch changes. */
  protected readonly draft = computed<CustomerAddressDraft>(() => {
    const saved = this.initial();
    const picked = this.picked();
    const parts = picked?.components ?? this.pickerInitial()?.components ?? EMPTY_PARTS;
    // The street and house the person sees in the two fields beside the map; failing those, the line
    // they typed into the search box. A street with no house is still a street.
    const line1 = (
      [parts.street, parts.house]
        .filter((part): part is string => !!part && part.trim() !== '')
        .join(' ') || parts.formatted
    ).trim();
    const point = picked === null ? (this.pickerInitial()?.point ?? null) : picked.point;
    const landmark = parts.landmark ?? null;

    let coordinateSource: CustomerCoordinateSource;
    if (point === null) {
      coordinateSource = landmark === null ? 'NOT_GEOCODED' : 'LANDMARK_ONLY';
    } else if (saved !== null && sameAs(point, pointOf(saved))) {
      // Where it was, whoever put it there: the source stays that person's.
      coordinateSource = saved.coordinateSource;
    } else {
      coordinateSource = picked?.coordinateSource ?? 'OPERATOR_PIN';
    }

    const fields: CustomerAddressFields = {
      line1,
      line2: this.extended() ? this.line2().trim() || null : (saved?.fields.line2 ?? null),
      city: this.city().trim(),
      district: this.district().trim(),
      postalCode: this.extended()
        ? this.postalCode().trim() || null
        : (saved?.fields.postalCode ?? null),
      entrance: parts.entrance,
      floor: parts.floor,
      apartment: parts.flat,
      landmark,
    };
    return {
      label: this.label().trim(),
      fields,
      latitude: point?.latitude ?? null,
      longitude: point?.longitude ?? null,
      coordinateSource,
      valid: fields.line1 !== '' && fields.city !== '' && fields.district !== '',
    };
  });

  constructor() {
    void this.regions.ensureLoaded();

    effect(() => {
      const saved = this.initial();
      untracked(() => {
        this.label.set(saved?.label ?? '');
        this.city.set(saved?.fields.city ?? '');
        this.district.set(saved?.fields.district ?? '');
        this.line2.set(saved?.fields.line2 ?? '');
        this.postalCode.set(saved?.fields.postalCode ?? '');
        this.cityTouched = false;
        this.districtTouched = false;
        this.picked.set(null);
      });
    });

    // What the provider recognised fills the city and district, until the person types their own.
    effect(() => {
      const picked = this.picked();
      untracked(() => {
        if (picked?.components.locality && !this.cityTouched) {
          this.city.set(picked.components.locality);
        }
        if (picked?.components.district && !this.districtTouched) {
          this.district.set(picked.components.district);
        }
      });
    });

    effect(() => this.draftChange.emit(this.draft()));
  }

  protected onPicked(next: PickedAddress): void {
    this.picked.set(next);
  }

  protected setCity(text: string): void {
    this.cityTouched = true;
    this.city.set(text);
  }

  protected setDistrict(text: string): void {
    this.districtTouched = true;
    this.district.set(text);
  }
}

function pointOf(address: RevealedCustomerAddress): LatLng | null {
  return address.latitude !== null && address.longitude !== null
    ? { latitude: address.latitude, longitude: address.longitude }
    : null;
}

function sameAs(a: LatLng | null, b: LatLng | null): boolean {
  return a !== null && b !== null && a.latitude === b.latitude && a.longitude === b.longitude;
}
