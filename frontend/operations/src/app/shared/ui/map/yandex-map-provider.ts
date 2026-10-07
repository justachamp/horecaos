import {
  LatLng,
  MapBounds,
  MapHandle,
  MapOptions,
  MapProvider,
  PinHandle,
  PinOptions,
  PolygonHandle,
  PolygonLook,
  PolygonOptions,
  RectangleHandle,
  RectangleOptions,
  Unsubscribe,
} from './map-provider';
import { boundsOf, openRing } from './geometry';

/**
 * Yandex Maps JavaScript API 2.1 behind the provider-neutral seam (ADR 0145 decision 3: Yandex is
 * the first adapter, because it is what both storefronts already run).
 *
 * **This module is a lazy chunk and must stay one.** It is reached only through
 * `LazyMapProvider`'s dynamic `import()`, never through a static import from any component or
 * route, so none of it is in the initial bundle (`frontend/operations` has a bundle budget and a
 * vendor SDK in the initial chunk is not an option). The vendor's *script* is a second lazy step:
 * it is injected from the vendor's own origin the first time a map is asked for, and never
 * bundled.
 *
 * **Yandex orders coordinates `[latitude, longitude]` here and `longitude,latitude` in its
 * geocoder.** Every conversion lives in {@link toYandex} and {@link fromYandex} and nowhere else,
 * because "which one is first" is the bug that puts a branch in the Indian Ocean; the rest of the
 * console names a point `{latitude, longitude}`.
 *
 * The vendor's types are not imported, because there are none to import: the slice of the API
 * this adapter uses is declared below, and a vendor change that breaks it fails the adapter's own
 * spec, not a component's.
 */

type YCoord = [number, number];

interface YEventManager {
  add(type: string, handler: (event: YEvent) => void): unknown;
  remove(type: string, handler: (event: YEvent) => void): unknown;
}

interface YEvent {
  get(name: string): unknown;
}

interface YGeometry {
  getCoordinates(): unknown;
  setCoordinates(coordinates: unknown): unknown;
  events: YEventManager;
}

interface YGeoObject {
  geometry: YGeometry;
  events: YEventManager;
  options: { set(key: string, value: unknown): unknown };
  properties?: { set(key: string, value: unknown): unknown };
  editor?: {
    startEditing(): unknown;
    stopEditing(): unknown;
    startDrawing?(): unknown;
    stopDrawing?(): unknown;
  };
}

interface YMap {
  geoObjects: { add(object: YGeoObject): unknown; remove(object: YGeoObject): unknown };
  events: YEventManager;
  setBounds(bounds: YCoord[], options?: object): unknown;
  setCenter(center: YCoord, zoom?: number): unknown;
  destroy(): unknown;
}

/** The part of the `ymaps` global this adapter calls. */
export interface Ymaps {
  ready(callback: () => void): unknown;
  Map: new (element: HTMLElement, state: object, options?: object) => YMap;
  Placemark: new (coordinates: YCoord, properties?: object, options?: object) => YGeoObject;
  Polygon: new (coordinates: YCoord[][], properties?: object, options?: object) => YGeoObject;
  Rectangle: new (coordinates: YCoord[], properties?: object, options?: object) => YGeoObject;
}

/** Fetches the vendor's script and resolves its global. Replaceable, so the spec needs no network. */
export type YandexScriptLoader = (apiKey: string, language: string) => Promise<Ymaps>;

/** The vendor's own origin. The only host this module loads script from. */
export const YANDEX_SCRIPT_ORIGIN = 'https://api-maps.yandex.ru';

/** A script that has not arrived in this long is treated as unreachable, so a screen stops waiting. */
const SCRIPT_TIMEOUT_MS = 15_000;

const OVERLAY_STROKE = '#1f5fd6';
const OVERLAY_FILL = '#1f5fd6';
/** An outline with nothing to say about it is lightly filled; an area with an intensity runs from here up. */
const PLAIN_FILL_OPACITY = 0.2;
const MIN_FILL_OPACITY = 0.08;
const MAX_FILL_OPACITY = 0.65;

/** 0 to 1 onto a fill opacity a person can still read the map through. Out-of-range values are clamped, not trusted. */
export function fillOpacityOf(intensity: number | null | undefined): number {
  if (intensity === null || intensity === undefined || !Number.isFinite(intensity)) {
    return PLAIN_FILL_OPACITY;
  }
  const clamped = Math.min(1, Math.max(0, intensity));
  return MIN_FILL_OPACITY + clamped * (MAX_FILL_OPACITY - MIN_FILL_OPACITY);
}

function ymapsGlobal(): Ymaps | undefined {
  return (window as unknown as { ymaps?: Ymaps }).ymaps;
}

/** The production loader: one `<script>` element, once, shared by every map on the page. */
export const loadYandexScript: YandexScriptLoader = (() => {
  let pending: Promise<Ymaps> | null = null;
  return (apiKey: string, language: string): Promise<Ymaps> => {
    pending ??= new Promise<Ymaps>((resolve, reject) => {
      const existing = ymapsGlobal();
      if (existing) {
        existing.ready(() => resolve(existing));
        return;
      }
      const script = document.createElement('script');
      script.async = true;
      script.src = `${YANDEX_SCRIPT_ORIGIN}/2.1/?apikey=${encodeURIComponent(apiKey)}&lang=${encodeURIComponent(language)}`;
      const fail = (message: string): void => {
        clearTimeout(timer);
        script.remove();
        // Forgotten, so the next screen to open a map tries again once the origin is back.
        pending = null;
        reject(new Error(message));
      };
      const timer = setTimeout(
        () => fail('The map script did not arrive in time'),
        SCRIPT_TIMEOUT_MS,
      );
      script.onerror = () => fail('The map script could not be loaded');
      script.onload = () => {
        const loaded = ymapsGlobal();
        if (!loaded) {
          fail('The map script loaded but did not define its API');
          return;
        }
        loaded.ready(() => {
          clearTimeout(timer);
          resolve(loaded);
        });
      };
      document.head.append(script);
    });
    return pending;
  };
})();

export function toYandex(point: LatLng): YCoord {
  return [point.latitude, point.longitude];
}

export function fromYandex(coordinate: readonly number[]): LatLng {
  return { latitude: coordinate[0], longitude: coordinate[1] };
}

function sameRing(a: readonly LatLng[], b: readonly LatLng[]): boolean {
  return (
    a.length === b.length &&
    a.every((p, i) => p.latitude === b[i].latitude && p.longitude === b[i].longitude)
  );
}

export class YandexMapProvider implements MapProvider {
  readonly code = 'YANDEX';

  private ymaps: Ymaps | null = null;
  private loading: Promise<void> | null = null;

  constructor(
    private readonly apiKey: string,
    private readonly language: string,
    private readonly loader: YandexScriptLoader = loadYandexScript,
  ) {}

  load(): Promise<void> {
    this.loading ??= this.loader(this.apiKey, this.language).then(
      (ymaps) => {
        this.ymaps = ymaps;
      },
      (failure: unknown) => {
        this.loading = null;
        throw failure;
      },
    );
    return this.loading;
  }

  createMap(element: HTMLElement, options: MapOptions): MapHandle {
    if (this.ymaps === null) {
      throw new Error('createMap was called before load() resolved');
    }
    const map = new this.ymaps.Map(
      element,
      { center: toYandex(options.center), zoom: options.zoom, controls: ['zoomControl'] },
      { suppressMapOpenBlock: true },
    );
    return new YandexMapHandle(this.ymaps, map);
  }
}

class YandexMapHandle implements MapHandle {
  constructor(
    private readonly ymaps: Ymaps,
    private readonly map: YMap,
  ) {}

  addPin(options: PinOptions): PinHandle {
    const placemark = new this.ymaps.Placemark(
      toYandex(options.position),
      { hintContent: options.label ?? '' },
      { draggable: options.draggable },
    );
    this.map.geoObjects.add(placemark);
    return new YandexPin(this.map, placemark);
  }

  addPolygon(options: PolygonOptions): PolygonHandle {
    const polygon = new this.ymaps.Polygon(
      [options.ring.map(toYandex)],
      { hintContent: options.label ?? '' },
      {
        fillColor: OVERLAY_FILL,
        fillOpacity: fillOpacityOf(options.intensity),
        strokeColor: OVERLAY_STROKE,
        strokeWidth: 2,
        editorMaxPoints: 200,
      },
    );
    this.map.geoObjects.add(polygon);
    const handle = new YandexPolygon(this.map, polygon, options.ring);
    handle.setEditable(options.editable);
    return handle;
  }

  addRectangle(options: RectangleOptions): RectangleHandle {
    const rectangle = new this.ymaps.Rectangle(
      [toYandex(options.bounds.southWest), toYandex(options.bounds.northEast)],
      {},
      {
        fillColor: OVERLAY_FILL,
        fillOpacity: PLAIN_FILL_OPACITY,
        strokeColor: OVERLAY_STROKE,
        strokeWidth: 2,
      },
    );
    this.map.geoObjects.add(rectangle);
    const handle = new YandexRectangle(this.map, rectangle);
    handle.setEditable(options.editable);
    return handle;
  }

  fitBounds(bounds: MapBounds): void {
    this.map.setBounds([toYandex(bounds.southWest), toYandex(bounds.northEast)], {
      checkZoomRange: true,
    });
  }

  setCenter(center: LatLng, zoom?: number): void {
    this.map.setCenter(toYandex(center), zoom);
  }

  onClick(listener: (position: LatLng) => void): Unsubscribe {
    const handler = (event: YEvent): void => {
      listener(fromYandex(event.get('coords') as number[]));
    };
    this.map.events.add('click', handler);
    return () => this.map.events.remove('click', handler);
  }

  destroy(): void {
    this.map.destroy();
  }
}

class YandexPin implements PinHandle {
  constructor(
    private readonly map: YMap,
    private readonly placemark: YGeoObject,
  ) {}

  setPosition(position: LatLng): void {
    this.placemark.geometry.setCoordinates(toYandex(position));
  }

  setDraggable(draggable: boolean): void {
    this.placemark.options.set('draggable', draggable);
  }

  onMoved(listener: (position: LatLng) => void): Unsubscribe {
    const handler = (): void => {
      listener(fromYandex(this.placemark.geometry.getCoordinates() as number[]));
    };
    this.placemark.events.add('dragend', handler);
    return () => this.placemark.events.remove('dragend', handler);
  }

  remove(): void {
    this.map.geoObjects.remove(this.placemark);
  }
}

class YandexPolygon implements PolygonHandle {
  private current: readonly LatLng[];
  /** Raised while this adapter writes the geometry itself, so its own write is not reported as an edit. */
  private writing = false;

  constructor(
    private readonly map: YMap,
    private readonly polygon: YGeoObject,
    ring: readonly LatLng[],
  ) {
    this.current = ring;
  }

  setRing(ring: readonly LatLng[]): void {
    if (sameRing(ring, this.current)) {
      return;
    }
    this.current = ring;
    this.writing = true;
    try {
      this.polygon.geometry.setCoordinates([ring.map(toYandex)]);
    } finally {
      this.writing = false;
    }
  }

  setEditable(editable: boolean): void {
    if (editable) {
      this.polygon.editor?.startEditing();
    } else {
      this.polygon.editor?.stopEditing();
    }
  }

  setLook(look: PolygonLook): void {
    if (look.intensity !== undefined) {
      this.polygon.options.set('fillOpacity', fillOpacityOf(look.intensity));
    }
    if (look.label !== undefined) {
      this.polygon.properties?.set('hintContent', look.label ?? '');
    }
  }

  startDrawing(): void {
    this.polygon.editor?.startDrawing?.();
  }

  stopDrawing(): void {
    this.polygon.editor?.stopDrawing?.();
  }

  onChanged(listener: (ring: readonly LatLng[]) => void): Unsubscribe {
    const handler = (): void => {
      if (this.writing) {
        return;
      }
      const rings = this.polygon.geometry.getCoordinates() as number[][][];
      // Yandex closes the outline by repeating the first corner; the console's rings are open.
      const ring = openRing((rings[0] ?? []).map(fromYandex));
      this.current = ring;
      listener(ring);
    };
    this.polygon.geometry.events.add('change', handler);
    return () => this.polygon.geometry.events.remove('change', handler);
  }

  remove(): void {
    this.map.geoObjects.remove(this.polygon);
  }
}

class YandexRectangle implements RectangleHandle {
  constructor(
    private readonly map: YMap,
    private readonly rectangle: YGeoObject,
  ) {}

  setBounds(bounds: MapBounds): void {
    this.rectangle.geometry.setCoordinates([
      toYandex(bounds.southWest),
      toYandex(bounds.northEast),
    ]);
  }

  setEditable(editable: boolean): void {
    if (editable) {
      this.rectangle.editor?.startEditing();
    } else {
      this.rectangle.editor?.stopEditing();
    }
  }

  onChanged(listener: (bounds: MapBounds) => void): Unsubscribe {
    const handler = (): void => {
      const corners = (this.rectangle.geometry.getCoordinates() as number[][]).map(fromYandex);
      // A drag can leave the two stored corners in either diagonal; the console's box is always
      // south-west then north-east.
      const bounds = boundsOf(corners);
      if (bounds !== null) {
        listener(bounds);
      }
    };
    this.rectangle.geometry.events.add('change', handler);
    return () => this.rectangle.geometry.events.remove('change', handler);
  }

  remove(): void {
    this.map.geoObjects.remove(this.rectangle);
  }
}
