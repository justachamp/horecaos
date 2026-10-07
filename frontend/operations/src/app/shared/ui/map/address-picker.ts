import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { BrandScope } from '../../../core/api/catalog-paths';
import {
  GeoLookupApi,
  GeoLookupContext,
  GeoResult,
  GeoSuggestion,
  GeoUnavailableReason,
} from '../../../core/api/geo-lookup-api';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../combobox';
import { InlineAlert } from '../inline-alert';
import { MapConfigService } from './map-config';
import { MapPin } from './map-pin';
import { LatLng, MapBounds } from './map-provider';

/**
 * Where a point came from, as an address picker is allowed to say it.
 *
 * **There is no `GEOCODER` in this type, and that is the rule, not an omission.** ADR 0145
 * decision 5: what the platform persists is a pin a person confirmed, never a geocoder's answer;
 * the storefront already does this and the server refuses `GEOCODER` from that surface. An
 * address picker that could emit it would be one careless `as` away from storing a provider's
 * guess as a fact under the licence's storage terms, which is the single choice the record
 * deliberately avoided.
 */
export type PinSource = 'OPERATOR_PIN' | 'CUSTOMER_PIN';

/** {@link PinSource}, or {@link NOT_GEOCODED} when no point was confirmed (ADR 0145 decision 6b). */
export type PickedCoordinateSource = PinSource | 'NOT_GEOCODED';

/**
 * The structured address (ADR 0015): what the provider recognised, and what only a person
 * knows. Entrance, floor, flat and landmark are never the provider's; a courier needs exactly
 * those, and the provider cannot know them.
 */
export interface AddressParts {
  readonly formatted: string;
  readonly street: string | null;
  readonly house: string | null;
  readonly locality: string | null;
  readonly entrance: string | null;
  readonly floor: string | null;
  readonly flat: string | null;
  readonly landmark: string | null;
}

/** What the picker emits (ADR 0145: `{point, coordinateSource, components}`). */
export interface PickedAddress {
  /** Present only once a person has confirmed it on the map. */
  readonly point: LatLng | null;
  readonly coordinateSource: PickedCoordinateSource;
  readonly components: AddressParts;
}

type TextPart = 'street' | 'house' | 'entrance' | 'floor' | 'flat' | 'landmark';

/**
 * Picks an address: a type-ahead field, the pin it implies, and the structured parts a courier
 * needs (ADR 0145, row `X.4`: `AddressPicker`; the shared primitive under New order's address
 * pane `1.3b`, the saved-address form `5.2c` and the branch pin `10.2b`).
 *
 * **Degrades in the order ADR 0145 decision 6 fixes, and never to a dead end.**
 *
 *  1. Suggestions fail, the map loads: the field is plain text, the person drops a pin.
 *  2. The map also fails: the address is typed, the coordinates are two fields, and with no
 *     point the emitted source is `NOT_GEOCODED` with its structured parts and landmark.
 *  3. Either way it says which of the two is true, in words, rather than showing an empty list.
 *
 * **A suggestion is a proposal and the pin is the decision.** Choosing a line resolves it through
 * the platform and places a pin for the person to look at, and nothing is emitted with a point
 * until they confirm it: drag it, click the map, type the coordinates or press "Use this point".
 * A result the provider itself marks `LOW_CONFIDENCE` (or that the platform marked so for lying
 * outside the region) says so beside the pin, because that is precisely the answer most likely
 * to be a plausible street in the wrong city.
 *
 * **It never overwrites what a person typed.** Moving the pin changes the point and nothing
 * else; the address text is changed only by choosing a suggestion, which is the person's act.
 */
@Component({
  selector: 'q-address-picker',
  imports: [Combobox, MapPin, InlineAlert, TPipe],
  templateUrl: './address-picker.html',
  styleUrl: './address-picker.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AddressPicker {
  private readonly lookups = inject(GeoLookupApi);
  private readonly configuration = inject(MapConfigService);
  private readonly i18n = inject(I18n);

  readonly scope = input.required<BrandScope>();
  /** The branch the person is working at: selects the lookup path a branch grant covers. */
  readonly locationId = input<string | null>(null);
  readonly regionId = input<string | null>(null);
  /** The region's box: the map opens on it and a point outside it is warned about. */
  readonly region = input<MapBounds | null>(null);
  readonly center = input.required<LatLng>();
  /** Whose pin this is. Operators use the default; the storefront would say `CUSTOMER_PIN`. */
  readonly source = input<PinSource>('OPERATOR_PIN');
  /** The address to start from: a saved one being edited. */
  readonly initial = input<PickedAddress | null>(null);

  readonly pickedChange = output<PickedAddress>();

  protected readonly query = signal('');
  protected readonly options = signal<readonly ComboboxOption[]>([]);
  protected readonly loading = signal(false);
  protected readonly unavailable = signal<GeoUnavailableReason | null>(null);
  protected readonly pinPosition = signal<LatLng | null>(null);
  protected readonly confirmed = signal(false);
  protected readonly lowConfidence = signal(false);
  protected readonly nothingResolved = signal(false);
  protected readonly parts = signal<Record<TextPart, string>>({
    street: '',
    house: '',
    entrance: '',
    floor: '',
    flat: '',
    landmark: '',
  });
  private readonly locality = signal<string | null>(null);

  protected readonly attribution = computed(() => {
    const config = this.configuration.config();
    return config?.features.includes('SUGGEST') ? config.attribution : null;
  });

  protected readonly unavailableKey = computed<MessageKey | null>(() => {
    switch (this.unavailable()) {
      case null:
        return null;
      case 'NOT_CONFIGURED':
        return 'ui.map.address.unavailable.notConfigured';
      case 'PROVIDER_REFUSED':
        return 'ui.map.address.unavailable.refused';
      case 'RATE_LIMITED':
        return 'ui.map.address.unavailable.rateLimited';
      case 'NO_REGION':
        return 'ui.map.address.unavailable.noRegion';
      case 'REQUEST_FAILED':
        return 'ui.map.address.unavailable.failed';
      default:
        return 'ui.map.address.unavailable.unavailable';
    }
  });

  private suggestions: readonly GeoSuggestion[] = [];
  /** The text a chosen suggestion resolved to, for as long as the person has not typed over it. */
  private chosen: string | null = null;
  private searchSequence = 0;

  constructor() {
    void this.configuration.ensureLoaded();

    effect(() => {
      const start = this.initial();
      untracked(() => {
        if (start === null) {
          return;
        }
        this.query.set(start.components.formatted);
        this.chosen = start.components.formatted;
        this.locality.set(start.components.locality);
        this.parts.set({
          street: start.components.street ?? '',
          house: start.components.house ?? '',
          entrance: start.components.entrance ?? '',
          floor: start.components.floor ?? '',
          flat: start.components.flat ?? '',
          landmark: start.components.landmark ?? '',
        });
        this.pinPosition.set(start.point);
        this.confirmed.set(start.point !== null);
      });
    });
  }

  protected onQuery(text: string): void {
    this.query.set(text);
    if (this.chosen !== null && text !== this.chosen) {
      this.chosen = null;
    }
    this.emit();
  }

  /** Fired by the field once typing settles. */
  protected async onSearch(text: string): Promise<void> {
    const trimmed = text.trim();
    const sequence = ++this.searchSequence;
    if (trimmed.length < 2) {
      this.options.set([]);
      this.unavailable.set(null);
      this.loading.set(false);
      return;
    }
    this.loading.set(true);
    const answer = await this.lookups.suggest(
      this.context(),
      trimmed,
      this.pinPosition() ?? this.center(),
    );
    if (sequence !== this.searchSequence) {
      // A newer search has started; this answer is for text that is no longer in the field.
      return;
    }
    this.loading.set(false);
    if (answer.status === 'UNAVAILABLE') {
      this.suggestions = [];
      this.options.set([]);
      this.unavailable.set(answer.reason);
      return;
    }
    this.unavailable.set(null);
    this.suggestions = answer.value;
    this.options.set(
      answer.value.map((suggestion, index) => ({
        id: String(index),
        label: suggestion.title,
        sublabel: suggestion.subtitle,
      })),
    );
  }

  protected async onSuggestionChosen(option: ComboboxOption): Promise<void> {
    const suggestion = this.suggestions[Number(option.id)];
    if (suggestion === undefined) {
      return;
    }
    this.query.set(suggestion.fullText);
    this.chosen = suggestion.fullText;
    this.options.set([]);
    this.nothingResolved.set(false);
    this.lowConfidence.set(false);

    const answer = await this.lookups.resolve(this.context(), suggestion.fullText);
    if (this.chosen !== suggestion.fullText) {
      return;
    }
    if (answer.status === 'UNAVAILABLE') {
      this.unavailable.set(answer.reason);
      this.emit();
      return;
    }
    this.unavailable.set(null);
    const best: GeoResult | undefined = answer.value[0];
    if (best === undefined) {
      this.nothingResolved.set(true);
      this.emit();
      return;
    }
    this.locality.set(best.components.locality);
    this.parts.update((current) => ({
      ...current,
      street: best.components.street ?? current.street,
      house: best.components.house ?? current.house,
    }));
    this.lowConfidence.set(best.confidence === 'LOW_CONFIDENCE');
    // Shown, not yet decided: the person has to see it on the map and agree.
    this.pinPosition.set({ latitude: best.latitude, longitude: best.longitude });
    this.confirmed.set(false);
    this.emit();
  }

  /** "Use it as I typed it": the provider does not know this address, or is not answering. */
  protected useAsTyped(): void {
    this.chosen = null;
    this.options.set([]);
    this.emit();
  }

  protected onPin(at: LatLng | null): void {
    this.pinPosition.set(at);
    this.confirmed.set(at !== null);
    if (at !== null) {
      this.lowConfidence.set(false);
    }
    this.emit();
  }

  protected confirm(): void {
    if (this.pinPosition() !== null) {
      this.confirmed.set(true);
      this.lowConfidence.set(false);
      this.emit();
    }
  }

  protected setPart(part: TextPart, text: string): void {
    this.parts.update((current) => ({ ...current, [part]: text }));
    this.emit();
  }

  private context(): GeoLookupContext {
    return {
      scope: this.scope(),
      locationId: this.locationId(),
      regionId: this.regionId(),
      locale: this.i18n.locale(),
    };
  }

  private emit(): void {
    const point = this.confirmed() ? this.pinPosition() : null;
    const parts = this.parts();
    this.pickedChange.emit({
      point,
      coordinateSource: point === null ? 'NOT_GEOCODED' : this.source(),
      components: {
        formatted: this.query().trim(),
        street: blankToNull(parts.street),
        house: blankToNull(parts.house),
        locality: this.locality(),
        entrance: blankToNull(parts.entrance),
        floor: blankToNull(parts.floor),
        flat: blankToNull(parts.flat),
        landmark: blankToNull(parts.landmark),
      },
    });
  }
}

function blankToNull(text: string): string | null {
  const trimmed = text.trim();
  return trimmed === '' ? null : trimmed;
}
