import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  YANDEX_SCRIPT_ORIGIN,
  Ymaps,
  YandexMapProvider,
  fromYandex,
  loadYandexScript,
  toYandex,
} from './yandex-map-provider';
import { LatLng } from './map-provider';

/**
 * The adapter against a hand-made `ymaps`, because there is no vendor script to run and no key to
 * fetch one with. What this proves is the translation and nothing about Yandex: that coordinates
 * go out and come back in the right order, that a closed ring is opened, that the adapter does
 * not report its own writes as a person's edits, and that the vendor's script is fetched from the
 * vendor's origin, once, and not at all until a map is asked for.
 */

type Handler = (event: { get(name: string): unknown }) => void;

class Events {
  readonly handlers = new Map<string, Set<Handler>>();
  add(type: string, handler: Handler): void {
    (this.handlers.get(type) ?? this.handlers.set(type, new Set()).get(type)!).add(handler);
  }
  remove(type: string, handler: Handler): void {
    this.handlers.get(type)?.delete(handler);
  }
  fire(type: string, values: Record<string, unknown> = {}): void {
    this.handlers.get(type)?.forEach((handler) => handler({ get: (name) => values[name] }));
  }
  count(type: string): number {
    return this.handlers.get(type)?.size ?? 0;
  }
}

class FakeGeometry {
  readonly events = new Events();
  constructor(public coordinates: unknown) {}
  getCoordinates(): unknown {
    return this.coordinates;
  }
  setCoordinates(next: unknown): void {
    this.coordinates = next;
    this.events.fire('change');
  }
}

class FakeObject {
  readonly events = new Events();
  readonly geometry: FakeGeometry;
  readonly options = {
    values: new Map<string, unknown>(),
    set: (k: string, v: unknown) => void this.options.values.set(k, v),
  };
  readonly editor = {
    calls: [] as string[],
    startEditing() {
      this.calls.push('startEditing');
    },
    stopEditing() {
      this.calls.push('stopEditing');
    },
    startDrawing() {
      this.calls.push('startDrawing');
    },
    stopDrawing() {
      this.calls.push('stopDrawing');
    },
  };
  constructor(
    coordinates: unknown,
    readonly properties: object | undefined,
    readonly initialOptions: Record<string, unknown> | undefined,
  ) {
    this.geometry = new FakeGeometry(coordinates);
  }
}

class FakeMap {
  readonly events = new Events();
  readonly added: FakeObject[] = [];
  readonly removed: FakeObject[] = [];
  bounds: unknown;
  centre: unknown;
  destroyed = false;
  readonly geoObjects = {
    add: (object: FakeObject) => void this.added.push(object),
    remove: (object: FakeObject) => void this.removed.push(object),
  };
  constructor(
    readonly element: HTMLElement,
    readonly state: Record<string, unknown>,
    readonly options: object | undefined,
  ) {}
  setBounds(bounds: unknown): void {
    this.bounds = bounds;
  }
  setCenter(center: unknown, zoom?: number): void {
    this.centre = [center, zoom];
  }
  destroy(): void {
    this.destroyed = true;
  }
}

function fakeYmaps(): { ymaps: Ymaps; maps: FakeMap[] } {
  const maps: FakeMap[] = [];
  const ymaps = {
    ready: (callback: () => void) => callback(),
    Map: class extends FakeMap {
      constructor(element: HTMLElement, state: Record<string, unknown>, options?: object) {
        super(element, state, options);
        maps.push(this);
      }
    },
    Placemark: FakeObject,
    Polygon: FakeObject,
    Rectangle: FakeObject,
  } as unknown as Ymaps;
  return { ymaps, maps };
}

const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });

describe('YandexMapProvider', () => {
  it('converts every coordinate between the console’s order and Yandex’s, and back', () => {
    expect(toYandex(at(41.3, 69.2))).toEqual([41.3, 69.2]);
    expect(fromYandex([41.3, 69.2])).toEqual(at(41.3, 69.2));
  });

  it('fetches nothing until load() and then once, however many maps follow', async () => {
    const { ymaps } = fakeYmaps();
    const loader = vi.fn(() => Promise.resolve(ymaps));
    const provider = new YandexMapProvider('public-key', 'ru_RU', loader);

    expect(loader).not.toHaveBeenCalled();
    await Promise.all([provider.load(), provider.load()]);
    await provider.load();

    expect(loader).toHaveBeenCalledTimes(1);
    expect(loader).toHaveBeenCalledWith('public-key', 'ru_RU');
  });

  it('forgets a failed load, so the next screen to open a map tries the vendor again', async () => {
    const { ymaps } = fakeYmaps();
    const loader = vi
      .fn<() => Promise<Ymaps>>()
      .mockRejectedValueOnce(new Error('unreachable'))
      .mockResolvedValue(ymaps);
    const provider = new YandexMapProvider('k', 'ru_RU', loader);

    await expect(provider.load()).rejects.toThrow('unreachable');
    await expect(provider.load()).resolves.toBeUndefined();
    expect(loader).toHaveBeenCalledTimes(2);
  });

  it('refuses to make a map before the script has been loaded', () => {
    const provider = new YandexMapProvider('k', 'ru_RU', () => Promise.resolve(fakeYmaps().ymaps));

    expect(() =>
      provider.createMap(document.createElement('div'), { center: at(1, 2), zoom: 3 }),
    ).toThrow('before load');
  });

  describe('a live map', () => {
    let maps: FakeMap[];
    let handle: ReturnType<YandexMapProvider['createMap']>;

    beforeEach(async () => {
      const made = fakeYmaps();
      maps = made.maps;
      const provider = new YandexMapProvider('k', 'ru_RU', () => Promise.resolve(made.ymaps));
      await provider.load();
      handle = provider.createMap(document.createElement('div'), {
        center: at(41.3, 69.2),
        zoom: 12,
      });
    });

    it('opens on the centre it was given, latitude first', () => {
      expect(maps[0].state['center']).toEqual([41.3, 69.2]);
      expect(maps[0].state['zoom']).toBe(12);
    });

    it('reports a click in the console’s own coordinates', () => {
      const clicks: LatLng[] = [];
      const stop = handle.onClick((p) => clicks.push(p));

      maps[0].events.fire('click', { coords: [41.31, 69.21] });
      stop();
      maps[0].events.fire('click', { coords: [1, 2] });

      expect(clicks).toEqual([at(41.31, 69.21)]);
      expect(maps[0].events.count('click')).toBe(0);
    });

    it('places a pin, reports where a drag ended, and moves and removes it', () => {
      const pin = handle.addPin({ position: at(41.3, 69.2), draggable: true, label: 'Branch' });
      const placemark = maps[0].added[0];
      expect(placemark.geometry.getCoordinates()).toEqual([41.3, 69.2]);
      expect(placemark.initialOptions).toEqual({ draggable: true });

      const moved: LatLng[] = [];
      pin.onMoved((p) => moved.push(p));
      placemark.geometry.coordinates = [41.4, 69.3];
      placemark.events.fire('dragend');
      expect(moved).toEqual([at(41.4, 69.3)]);

      pin.setPosition(at(41.5, 69.4));
      pin.setDraggable(false);
      expect(placemark.geometry.getCoordinates()).toEqual([41.5, 69.4]);
      expect(placemark.options.values.get('draggable')).toBe(false);

      pin.remove();
      expect(maps[0].removed).toEqual([placemark]);
    });

    it('draws a polygon from an open ring and reports edits as an open ring again', () => {
      const ring = [at(41.3, 69.2), at(41.3, 69.3), at(41.4, 69.3)];
      const polygon = handle.addPolygon({ ring, editable: true });
      const object = maps[0].added[0];
      expect(object.geometry.getCoordinates()).toEqual([
        [
          [41.3, 69.2],
          [41.3, 69.3],
          [41.4, 69.3],
        ],
      ]);
      expect(object.editor.calls).toEqual(['startEditing']);

      const edits: (readonly LatLng[])[] = [];
      polygon.onChanged((r) => edits.push(r));
      // Yandex closes the outline by repeating the first corner when a person edits it.
      object.geometry.coordinates = [
        [
          [41.3, 69.2],
          [41.3, 69.3],
          [41.4, 69.3],
          [41.45, 69.25],
          [41.3, 69.2],
        ],
      ];
      object.geometry.events.fire('change');

      expect(edits).toEqual([[at(41.3, 69.2), at(41.3, 69.3), at(41.4, 69.3), at(41.45, 69.25)]]);
    });

    it('does not report its own writes as a person’s edits, which would loop forever', () => {
      const polygon = handle.addPolygon({ ring: [at(1, 1), at(1, 2), at(2, 2)], editable: true });
      const edits: unknown[] = [];
      polygon.onChanged((r) => edits.push(r));

      polygon.setRing([at(1, 1), at(1, 3), at(3, 3)]);
      polygon.setRing([at(1, 1), at(1, 3), at(3, 3)]);

      expect(edits).toEqual([]);
      expect(maps[0].added[0].geometry.getCoordinates()).toEqual([
        [
          [1, 1],
          [1, 3],
          [3, 3],
        ],
      ]);
    });

    it('starts and stops drawing and editing through the vendor’s own editor', () => {
      const polygon = handle.addPolygon({ ring: [], editable: false });
      polygon.startDrawing();
      polygon.stopDrawing();
      polygon.setEditable(true);
      polygon.setEditable(false);

      expect(maps[0].added[0].editor.calls).toEqual([
        'stopEditing',
        'startDrawing',
        'stopDrawing',
        'startEditing',
        'stopEditing',
      ]);
    });

    it('reports a rectangle as south-west then north-east however the vendor leaves its corners', () => {
      const rectangle = handle.addRectangle({
        bounds: { southWest: at(41.15, 69.04), northEast: at(41.47, 69.46) },
        editable: true,
      });
      const object = maps[0].added[0];
      expect(object.geometry.getCoordinates()).toEqual([
        [41.15, 69.04],
        [41.47, 69.46],
      ]);

      const edits: unknown[] = [];
      rectangle.onChanged((b) => edits.push(b));
      // Dragged across itself: the stored corners are now the other diagonal.
      object.geometry.coordinates = [
        [41.5, 69.0],
        [41.1, 69.5],
      ];
      object.geometry.events.fire('change');

      expect(edits).toEqual([{ southWest: at(41.1, 69.0), northEast: at(41.5, 69.5) }]);
    });

    it('fits and recentres in the vendor’s order, and destroys the map', () => {
      handle.fitBounds({ southWest: at(41.15, 69.04), northEast: at(41.47, 69.46) });
      handle.setCenter(at(41.2, 69.1), 14);
      handle.destroy();

      expect(maps[0].bounds).toEqual([
        [41.15, 69.04],
        [41.47, 69.46],
      ]);
      expect(maps[0].centre).toEqual([[41.2, 69.1], 14]);
      expect(maps[0].destroyed).toBe(true);
    });
  });
});

describe('loadYandexScript', () => {
  afterEach(() => {
    document.head
      .querySelectorAll('script[src^="https://api-maps.yandex.ru"]')
      .forEach((s) => s.remove());
    delete (window as unknown as { ymaps?: unknown }).ymaps;
    vi.useRealTimers();
  });

  function injected(): HTMLScriptElement | null {
    return document.head.querySelector<HTMLScriptElement>(`script[src^="${YANDEX_SCRIPT_ORIGIN}"]`);
  }

  it('rejects when the origin cannot be reached, removes its script, and tries again next time', async () => {
    const failing = loadYandexScript('k', 'ru_RU');
    injected()!.onerror?.(new Event('error'));

    await expect(failing).rejects.toThrow('could not be loaded');
    expect(injected()).toBeNull();

    const retry = loadYandexScript('k', 'ru_RU');
    expect(injected()).not.toBeNull();
    injected()!.onerror?.(new Event('error'));
    await expect(retry).rejects.toThrow();
  });

  it('gives up on a script that never arrives, so a screen stops waiting', async () => {
    vi.useFakeTimers();
    const waiting = loadYandexScript('k', 'ru_RU');
    const assertion = expect(waiting).rejects.toThrow('did not arrive');

    await vi.advanceTimersByTimeAsync(15_001);

    await assertion;
    expect(injected()).toBeNull();
  });

  // Last, on purpose: a script that loaded is remembered for the life of the page, as it should be,
  // and every test above needs a loader that has not yet succeeded.
  it('loads the vendor’s script from the vendor’s own origin, with the key and language it was given, and only once', async () => {
    const first = loadYandexScript('pk 1', 'en_US');
    const second = loadYandexScript('pk 1', 'en_US');
    const script = injected()!;

    expect(script.src).toBe(`${YANDEX_SCRIPT_ORIGIN}/2.1/?apikey=pk%201&lang=en_US`);
    expect(
      document.head.querySelectorAll('script[src^="https://api-maps.yandex.ru"]'),
    ).toHaveLength(1);

    const ymaps = { ready: (callback: () => void) => callback() };
    (window as unknown as { ymaps: unknown }).ymaps = ymaps;
    script.onload?.(new Event('load'));

    await expect(first).resolves.toBe(ymaps);
    await expect(second).resolves.toBe(ymaps);
  });
});
