import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  effect,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { InlineAlert } from '../inline-alert';
import { boundsContain, isLatLng, isLatitude, isLongitude, parseCoordinate } from './geometry';
import { MapCanvas } from './map-canvas';
import { LatLng, MapBounds, MapHandle, PinHandle } from './map-provider';

/**
 * One draggable pin on a map, with its coordinates beside it as plain fields (ADR 0145, row
 * `X.4`: `MapPin`). The way an operator places or corrects the point of a branch, an address or an
 * order.
 *
 * **Three ways to say where, and all three always work.** Click the map, drag the pin, or type the
 * two numbers. The fields are not a fallback that appears when the map breaks; they are the
 * keyboard equivalent of a drag, which an interface that can only be operated by dragging does not
 * have, and they are what is left when the provider is not configured, which in this repository is
 * the state of every environment until the owner obtains a key.
 *
 * **It never decides what a point means.** It reports where a person put the pin. Whether that is
 * an operator's or a customer's pin (`OPERATOR_PIN`, `CUSTOMER_PIN`) is the host's to say, and
 * there is deliberately no way here to produce a `GEOCODER` point: a geocoder result is a
 * suggestion a person confirms by seeing it on the map, and what is stored is theirs (ADR 0145
 * decision 5).
 *
 * A point outside the {@link region} is a warning and not a refusal: the box is there to catch a
 * transposed latitude, and a branch genuinely at the edge of it is the host's to resolve.
 */
@Component({
  selector: 'q-map-pin',
  imports: [MapCanvas, TPipe, InlineAlert],
  templateUrl: './map-pin.html',
  styleUrl: './map-pin.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MapPin {
  /** Where the pin is; `null` is "not placed yet". */
  readonly position = input<LatLng | null>(null);
  /** Where the map opens when there is no pin. */
  readonly center = input.required<LatLng>();
  readonly region = input<MapBounds | null>(null);
  readonly editable = input(true, { transform: booleanAttribute });
  /** The zoom when there is a pin to look at. */
  readonly zoom = input(16);
  readonly label = input<string | null>(null);

  /** A person moved, placed or typed the pin; `null` when they removed it. */
  readonly positionChange = output<LatLng | null>();

  private readonly canvas = viewChild.required(MapCanvas);
  private readonly handle = signal<MapHandle | null>(null);

  protected readonly latitudeText = signal('');
  protected readonly longitudeText = signal('');
  protected readonly invalid = signal(false);

  protected readonly outsideRegion = computed(() => {
    const box = this.region();
    const at = this.position();
    return box !== null && at !== null && !boundsContain(box, at);
  });

  private pin: PinHandle | null = null;
  private stopMoves: (() => void) | null = null;
  /** The last point this component itself reported, so its own echo is not mistaken for news. */
  private lastReported: LatLng | null = null;

  constructor() {
    // The fields follow the pin when something other than typing moved it. A person halfway
    // through typing "41.3" must not have their text rewritten to "41.3" by their own keystroke.
    effect(() => {
      const at = this.position();
      // Untracked: this runs when the pin moves, never because a key was pressed.
      const typed = untracked(() => this.typedPoint());
      if (at === null) {
        if (typed !== null) {
          this.latitudeText.set('');
          this.longitudeText.set('');
        }
        return;
      }
      if (typed === null || typed.latitude !== at.latitude || typed.longitude !== at.longitude) {
        this.latitudeText.set(String(at.latitude));
        this.longitudeText.set(String(at.longitude));
        this.invalid.set(false);
      }
    });

    effect(() => {
      const map = this.handle();
      const at = this.position();
      if (map === null) {
        return;
      }
      this.syncPin(map, at);
    });
  }

  protected onReady(map: MapHandle): void {
    this.handle.set(map);
  }

  protected onMapClick(at: LatLng): void {
    if (this.editable()) {
      this.report(at);
    }
  }

  protected onLatitude(text: string): void {
    this.latitudeText.set(text);
    this.fieldsChanged();
  }

  protected onLongitude(text: string): void {
    this.longitudeText.set(text);
    this.fieldsChanged();
  }

  protected remove(): void {
    this.invalid.set(false);
    this.lastReported = null;
    this.positionChange.emit(null);
  }

  private fieldsChanged(): void {
    const latitude = parseCoordinate(this.latitudeText());
    const longitude = parseCoordinate(this.longitudeText());
    if (latitude === null && longitude === null) {
      this.remove();
      return;
    }
    if (
      latitude === null ||
      longitude === null ||
      !isLatitude(latitude) ||
      !isLongitude(longitude)
    ) {
      // Half a point, or not a number: said, not reported. A pin cannot be half placed.
      this.invalid.set(true);
      return;
    }
    this.invalid.set(false);
    this.report({ latitude, longitude });
  }

  private report(at: LatLng): void {
    if (!isLatLng(at)) {
      return;
    }
    this.lastReported = at;
    this.positionChange.emit(at);
  }

  private typedPoint(): LatLng | null {
    const latitude = parseCoordinate(this.latitudeText());
    const longitude = parseCoordinate(this.longitudeText());
    return latitude !== null && longitude !== null && isLatLng({ latitude, longitude })
      ? { latitude, longitude }
      : null;
  }

  private syncPin(map: MapHandle, at: LatLng | null): void {
    if (at === null) {
      this.pin?.remove();
      this.stopMoves?.();
      this.pin = null;
      this.stopMoves = null;
      return;
    }
    if (this.pin === null) {
      this.pin = map.addPin({
        position: at,
        draggable: this.editable(),
        label: this.label() ?? undefined,
      });
      this.stopMoves = this.pin.onMoved((moved) => this.report(moved));
    } else {
      this.pin.setPosition(at);
      this.pin.setDraggable(this.editable());
    }
    const echoed =
      this.lastReported !== null &&
      this.lastReported.latitude === at.latitude &&
      this.lastReported.longitude === at.longitude;
    if (!echoed) {
      // Moved by something other than this component: bring it into view.
      this.canvas().recenter(at, this.zoom());
    }
  }
}
