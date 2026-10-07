import { InjectionToken } from '@angular/core';

/**
 * The provider-neutral map seam (ADR 0145 decision 1, ADR 0101, row `X.4`).
 *
 * **Nothing in this file, or in any component that imports it, names a vendor.** The vendor's SDK
 * is loaded from the vendor's own origin the first time a map screen opens and is never bundled
 * (`frontend/operations` has an initial-bundle budget; a map SDK in the initial chunk is not an
 * option), and the only code that touches its types is the adapter that owns them
 * (`yandex-map-provider.ts`). A component asks for a pin, a polygon or a rectangle and is told
 * when a person moved one; which vendor drew it is a configuration fact read from
 * `GET .../map-config`.
 *
 * Coordinates are named as the platform names them (`GeoPoint`: `latitude`, `longitude`, WGS 84
 * decimal degrees), never as a vendor's array pair, because the vendors disagree about which
 * element is first and the disagreement is exactly how a branch ends up in the Indian Ocean.
 */

/** WGS 84 decimal degrees. */
export interface LatLng {
  readonly latitude: number;
  readonly longitude: number;
}

/** A south-west / north-east box, the shape of a region (ADR 0037). */
export interface MapBounds {
  readonly southWest: LatLng;
  readonly northEast: LatLng;
}

export interface MapOptions {
  readonly center: LatLng;
  readonly zoom: number;
}

export type Unsubscribe = () => void;

export interface PinOptions {
  readonly position: LatLng;
  readonly draggable: boolean;
  /** Already translated, or tenant data; shown as the pin's hint. */
  readonly label?: string;
}

export interface PinHandle {
  setPosition(position: LatLng): void;
  setDraggable(draggable: boolean): void;
  /** Fires when a person finishes dragging the pin, with where it now is. */
  onMoved(listener: (position: LatLng) => void): Unsubscribe;
  remove(): void;
}

/**
 * How a polygon looks, for the overlays that carry a meaning beyond "here is an outline" (the
 * order-density view, row `7.10`). Both are optional and both are display only: a polygon being
 * edited is never given an intensity.
 */
export interface PolygonLook {
  /**
   * 0 (faint) to 1 (strong): how much of something this area has. A choropleth is the same shape
   * drawn with a fill that tells the difference, so the vendor adapter turns this into a fill
   * opacity and no component names a colour.
   */
  readonly intensity?: number | null;
  /** Already translated, or tenant data; the hint shown when the area is hovered. */
  readonly label?: string | null;
}

export interface PolygonOptions extends PolygonLook {
  /** The outline, open: the last corner is not repeated as the first. */
  readonly ring: readonly LatLng[];
  readonly editable: boolean;
}

export interface PolygonHandle {
  setRing(ring: readonly LatLng[]): void;
  setEditable(editable: boolean): void;
  /** Restyles a display-only outline; a field left out keeps what it had. */
  setLook(look: PolygonLook): void;
  /** Lets the person click corners onto the map. Ends when {@link stopDrawing} is called. */
  startDrawing(): void;
  stopDrawing(): void;
  /** Fires with the whole outline, open, whenever a person adds, moves or removes a corner. */
  onChanged(listener: (ring: readonly LatLng[]) => void): Unsubscribe;
  remove(): void;
}

export interface RectangleOptions {
  readonly bounds: MapBounds;
  readonly editable: boolean;
}

export interface RectangleHandle {
  setBounds(bounds: MapBounds): void;
  setEditable(editable: boolean): void;
  onChanged(listener: (bounds: MapBounds) => void): Unsubscribe;
  remove(): void;
}

/** One live map. Everything drawn on it is removed with it. */
export interface MapHandle {
  addPin(options: PinOptions): PinHandle;
  addPolygon(options: PolygonOptions): PolygonHandle;
  addRectangle(options: RectangleOptions): RectangleHandle;
  fitBounds(bounds: MapBounds): void;
  setCenter(center: LatLng, zoom?: number): void;
  /** Fires when a person clicks (or taps) empty map. */
  onClick(listener: (position: LatLng) => void): Unsubscribe;
  destroy(): void;
}

/**
 * A vendor's map, behind one contract (ADR 0145's `MapProvider`: `load`, `createMap`,
 * `addPin`, `addPolygon`, `fitBounds`, `onPinMoved`, `destroy`).
 */
export interface MapProvider {
  /** `YANDEX`, or `NULL` for the test adapter. */
  readonly code: string;

  /**
   * Fetches the vendor's script and waits until it is usable. Idempotent: every call returns
   * the same settled promise. Rejects with {@link MapUnavailableError} when there is nothing to
   * load, and with anything else when the vendor's origin could not be reached.
   */
  load(): Promise<void>;

  /** Only after {@link load} has resolved. */
  createMap(element: HTMLElement, options: MapOptions): MapHandle;
}

export type MapUnavailableReason = 'NOT_CONFIGURED' | 'NO_TILES' | 'LOAD_FAILED';

/**
 * The map cannot be drawn here, and why. A screen turns this into a sentence and keeps working:
 * every component built on the seam also offers the coordinates as plain fields, so a provider
 * outage never blanks a page that merely contains a map (ADR 0145, negative consequences).
 */
export class MapUnavailableError extends Error {
  constructor(readonly reason: MapUnavailableReason) {
    super(`The map is unavailable: ${reason}`);
    this.name = 'MapUnavailableError';
  }
}

/**
 * Which {@link MapProvider} the application uses. Provided at the root as a lazy provider that
 * reads the map configuration and imports the configured vendor's adapter on first use; tests
 * replace it with `provideNullMapProvider()`.
 */
export const MAP_PROVIDER = new InjectionToken<MapProvider>('MapProvider');
