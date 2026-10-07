import { Provider } from '@angular/core';

import {
  LatLng,
  MAP_PROVIDER,
  MapBounds,
  MapHandle,
  MapOptions,
  MapProvider,
  PinHandle,
  PinOptions,
  PolygonHandle,
  PolygonOptions,
  RectangleHandle,
  RectangleOptions,
  Unsubscribe,
} from './map-provider';

/**
 * The adapter that draws nothing and remembers everything (ADR 0145: "the `MapProvider` fake
 * drives the components").
 *
 * What a component spec needs is not a picture but two things: to see what the component asked
 * the map to do, and to be the person who then does something to it. So every overlay a
 * component creates is recorded here, and `simulate*` plays the part of a hand dragging a pin,
 * clicking empty map or moving a corner. No vendor SDK, no network, no DOM beyond the element
 * the component hands over, which is why a spec using it costs nothing and cannot flake.
 *
 * Exported for specs and for any screen's spec that contains a map; never provided in the
 * application itself.
 */
export class NullMapProvider implements MapProvider {
  readonly code = 'NULL';

  /** How many times a component asked for the vendor's script. */
  loadCount = 0;

  /** When set, {@link load} rejects with it, as a vendor origin that cannot be reached does. */
  loadError: Error | null = null;

  /** Held by {@link holdLoad}, so a spec can observe the loading state before the vendor "arrives". */
  private gate: Promise<void> | null = null;

  readonly maps: NullMap[] = [];

  /** Holds {@link load} pending until the returned function is called. */
  holdLoad(): () => void {
    let release: () => void = () => undefined;
    this.gate = new Promise<void>((resolve) => (release = resolve));
    return release;
  }

  async load(): Promise<void> {
    this.loadCount += 1;
    if (this.gate) {
      await this.gate;
    }
    if (this.loadError) {
      throw this.loadError;
    }
  }

  createMap(_element: HTMLElement, options: MapOptions): MapHandle {
    const map = new NullMap(options);
    this.maps.push(map);
    return map;
  }

  /** The most recently created map, which is the one a spec nearly always means. */
  get map(): NullMap {
    const latest = this.maps[this.maps.length - 1];
    if (!latest) {
      throw new Error('No map has been created yet');
    }
    return latest;
  }
}

export class NullMap implements MapHandle {
  readonly pins: NullPin[] = [];
  readonly polygons: NullPolygon[] = [];
  readonly rectangles: NullRectangle[] = [];
  readonly fitted: MapBounds[] = [];
  centre: LatLng;
  zoom: number;
  destroyed = false;

  private readonly clickListeners = new Set<(position: LatLng) => void>();

  constructor(options: MapOptions) {
    this.centre = options.center;
    this.zoom = options.zoom;
  }

  addPin(options: PinOptions): PinHandle {
    const pin = new NullPin(options);
    this.pins.push(pin);
    return pin;
  }

  addPolygon(options: PolygonOptions): PolygonHandle {
    const polygon = new NullPolygon(options);
    this.polygons.push(polygon);
    return polygon;
  }

  addRectangle(options: RectangleOptions): RectangleHandle {
    const rectangle = new NullRectangle(options);
    this.rectangles.push(rectangle);
    return rectangle;
  }

  fitBounds(bounds: MapBounds): void {
    this.fitted.push(bounds);
  }

  setCenter(center: LatLng, zoom?: number): void {
    this.centre = center;
    if (zoom !== undefined) {
      this.zoom = zoom;
    }
  }

  onClick(listener: (position: LatLng) => void): Unsubscribe {
    this.clickListeners.add(listener);
    return () => this.clickListeners.delete(listener);
  }

  destroy(): void {
    this.destroyed = true;
    this.clickListeners.clear();
  }

  /** A person clicks empty map. */
  simulateClick(position: LatLng): void {
    [...this.clickListeners].forEach((listener) => listener(position));
  }

  get livePins(): NullPin[] {
    return this.pins.filter((pin) => !pin.removed);
  }

  get livePolygons(): NullPolygon[] {
    return this.polygons.filter((polygon) => !polygon.removed);
  }

  get liveRectangles(): NullRectangle[] {
    return this.rectangles.filter((rectangle) => !rectangle.removed);
  }
}

export class NullPin implements PinHandle {
  position: LatLng;
  draggable: boolean;
  readonly label: string | undefined;
  removed = false;

  private readonly listeners = new Set<(position: LatLng) => void>();

  constructor(options: PinOptions) {
    this.position = options.position;
    this.draggable = options.draggable;
    this.label = options.label;
  }

  setPosition(position: LatLng): void {
    this.position = position;
  }

  setDraggable(draggable: boolean): void {
    this.draggable = draggable;
  }

  onMoved(listener: (position: LatLng) => void): Unsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  remove(): void {
    this.removed = true;
    this.listeners.clear();
  }

  /** A person drags the pin and lets go. Does nothing to a pin that is not draggable, as a real map would not. */
  simulateDrag(to: LatLng): void {
    if (!this.draggable || this.removed) {
      return;
    }
    this.position = to;
    [...this.listeners].forEach((listener) => listener(to));
  }
}

export class NullPolygon implements PolygonHandle {
  ring: readonly LatLng[];
  editable: boolean;
  drawing = false;
  removed = false;

  private readonly listeners = new Set<(ring: readonly LatLng[]) => void>();

  constructor(options: PolygonOptions) {
    this.ring = options.ring;
    this.editable = options.editable;
  }

  setRing(ring: readonly LatLng[]): void {
    this.ring = ring;
  }

  setEditable(editable: boolean): void {
    this.editable = editable;
  }

  startDrawing(): void {
    this.drawing = true;
  }

  stopDrawing(): void {
    this.drawing = false;
  }

  onChanged(listener: (ring: readonly LatLng[]) => void): Unsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  remove(): void {
    this.removed = true;
    this.listeners.clear();
  }

  /** A person adds, moves or removes a corner; the whole outline arrives. */
  simulateEdit(ring: readonly LatLng[]): void {
    if (!(this.editable || this.drawing) || this.removed) {
      return;
    }
    this.ring = ring;
    [...this.listeners].forEach((listener) => listener(ring));
  }
}

export class NullRectangle implements RectangleHandle {
  bounds: MapBounds;
  editable: boolean;
  removed = false;

  private readonly listeners = new Set<(bounds: MapBounds) => void>();

  constructor(options: RectangleOptions) {
    this.bounds = options.bounds;
    this.editable = options.editable;
  }

  setBounds(bounds: MapBounds): void {
    this.bounds = bounds;
  }

  setEditable(editable: boolean): void {
    this.editable = editable;
  }

  onChanged(listener: (bounds: MapBounds) => void): Unsubscribe {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  remove(): void {
    this.removed = true;
    this.listeners.clear();
  }

  /** A person drags a corner of the rectangle. */
  simulateResize(bounds: MapBounds): void {
    if (!this.editable || this.removed) {
      return;
    }
    this.bounds = bounds;
    [...this.listeners].forEach((listener) => listener(bounds));
  }
}

/** `providers: [provideNullMapProvider(provider)]` in a spec that contains a map. */
export function provideNullMapProvider(
  provider: NullMapProvider = new NullMapProvider(),
): Provider {
  return { provide: MAP_PROVIDER, useValue: provider };
}
