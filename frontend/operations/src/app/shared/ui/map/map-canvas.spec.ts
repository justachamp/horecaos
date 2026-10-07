import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { MapArea, MapCanvas, MapMarker } from './map-canvas';
import { LatLng, MapHandle, MapUnavailableError, MapUnavailableReason } from './map-provider';
import { NullMapProvider, provideNullMapProvider } from './null-map-provider';

const CENTRE: LatLng = { latitude: 41.31, longitude: 69.24 };
const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function text(fixture: ComponentFixture<unknown>): string {
  return (fixture.nativeElement as HTMLElement).textContent ?? '';
}

function query<T extends Element>(fixture: ComponentFixture<unknown>, testId: string): T | null {
  return (fixture.nativeElement as HTMLElement).querySelector<T>(`[data-testid="${testId}"]`);
}

describe('MapCanvas (ADR 0145, row X.4)', () => {
  let provider: NullMapProvider;

  beforeEach(() => {
    provider = new NullMapProvider();
    TestBed.configureTestingModule({ providers: [provideNullMapProvider(provider)] });
    TestBed.inject(I18n).setLocale('en');
  });

  function create(inputs: Record<string, unknown> = {}): ComponentFixture<MapCanvas> {
    const fixture = TestBed.createComponent(MapCanvas);
    fixture.componentRef.setInput('center', CENTRE);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture;
  }

  it('says it is loading while the vendor is fetched, and shows the map once it exists', async () => {
    const release = provider.holdLoad();
    const fixture = create();
    await settle(fixture);

    expect(query(fixture, 'q-map-loading')).not.toBeNull();
    expect(query<HTMLElement>(fixture, 'q-map-surface')?.hidden).toBe(true);
    expect(provider.maps).toHaveLength(0);

    release();
    await settle(fixture);

    expect(query(fixture, 'q-map-loading')).toBeNull();
    expect(query<HTMLElement>(fixture, 'q-map-surface')?.hidden).toBe(false);
    expect(provider.maps).toHaveLength(1);
  });

  it('opens the map on the centre and zoom it was given, and hands the live map to whoever asked', async () => {
    const fixture = create({ zoom: 15 });
    const handed: MapHandle[] = [];
    fixture.componentInstance.ready.subscribe((map) => handed.push(map));
    await settle(fixture);

    expect(provider.map.centre).toEqual(CENTRE);
    expect(provider.map.zoom).toBe(15);
    expect(handed).toEqual([provider.map]);
  });

  it('fits a box when it is given, and again when it changes', async () => {
    const fixture = create({ fitTo: { southWest: at(41.15, 69.04), northEast: at(41.47, 69.46) } });
    await settle(fixture);
    expect(provider.map.fitted).toHaveLength(1);

    fixture.componentRef.setInput('fitTo', {
      southWest: at(41.2, 69.1),
      northEast: at(41.3, 69.2),
    });
    await settle(fixture);

    expect(provider.map.fitted).toHaveLength(2);
    expect(provider.map.fitted[1].southWest).toEqual(at(41.2, 69.1));
  });

  it('reports a click on empty map in coordinates, and can be recentred without touching its overlays', async () => {
    const fixture = create();
    const clicks: LatLng[] = [];
    fixture.componentInstance.mapClick.subscribe((p) => clicks.push(p));
    await settle(fixture);

    provider.map.simulateClick(at(41.3, 69.2));
    fixture.componentInstance.recenter(at(41.4, 69.3), 17);

    expect(clicks).toEqual([at(41.3, 69.2)]);
    expect(provider.map.centre).toEqual(at(41.4, 69.3));
    expect(provider.map.zoom).toBe(17);
  });

  describe('when there is no map to draw', () => {
    async function unavailable(reason: MapUnavailableReason | Error) {
      provider.loadError = reason instanceof Error ? reason : new MapUnavailableError(reason);
      const fixture = create();
      const reported: MapUnavailableReason[] = [];
      fixture.componentInstance.unavailable.subscribe((r) => reported.push(r));
      await settle(fixture);
      return { fixture, reported };
    }

    it('says no provider is set up, and does not offer a retry that cannot help', async () => {
      const { fixture, reported } = await unavailable('NOT_CONFIGURED');

      expect(text(fixture)).toContain('No map provider is set up in this environment');
      expect(query(fixture, 'q-map-retry')).toBeNull();
      expect(reported).toEqual(['NOT_CONFIGURED']);
      expect(provider.maps).toHaveLength(0);
    });

    it('says an environment with address search has no map, in its own words', async () => {
      const { fixture } = await unavailable('NO_TILES');

      expect(text(fixture)).toContain('can search for addresses but has no map to draw');
      expect(query(fixture, 'q-map-retry')).toBeNull();
    });

    it('treats any other failure as the vendor being unreachable, and lets the person try again', async () => {
      const { fixture, reported } = await unavailable(new Error('network'));

      expect(text(fixture)).toContain('The map could not be loaded');
      expect(reported).toEqual(['LOAD_FAILED']);

      provider.loadError = null;
      query<HTMLButtonElement>(fixture, 'q-map-retry')!.click();
      await settle(fixture);

      expect(provider.maps).toHaveLength(1);
      expect(query(fixture, 'q-map-retry')).toBeNull();
      expect(query<HTMLElement>(fixture, 'q-map-surface')?.hidden).toBe(false);
    });
  });

  describe('read-only overlays', () => {
    it('keeps a moving marker as one pin that moves, and removes the ones that go', async () => {
      const markers: MapMarker[] = [
        { id: 'courier-1', position: at(41.3, 69.2), label: 'Courier 1' },
        { id: 'courier-2', position: at(41.31, 69.21) },
      ];
      const fixture = create({ markers });
      await settle(fixture);
      const [first, second] = provider.map.livePins;
      expect(provider.map.livePins).toHaveLength(2);
      expect(first.draggable).toBe(false);
      expect(first.label).toBe('Courier 1');

      fixture.componentRef.setInput('markers', [{ id: 'courier-1', position: at(41.35, 69.25) }]);
      await settle(fixture);

      expect(provider.map.livePins).toEqual([first]);
      expect(first.position).toEqual(at(41.35, 69.25));
      expect(second.removed).toBe(true);
      expect(provider.map.pins).toHaveLength(2);
    });

    it('draws areas as outlines nobody can edit, and replaces them in place', async () => {
      const areas: MapArea[] = [
        { id: 'zone-1', ring: [at(41.3, 69.2), at(41.3, 69.3), at(41.4, 69.25)] },
      ];
      const fixture = create({ areas });
      await settle(fixture);
      const [zone] = provider.map.livePolygons;
      expect(zone.editable).toBe(false);

      fixture.componentRef.setInput('areas', [
        { id: 'zone-1', ring: [at(41.3, 69.2), at(41.35, 69.3), at(41.4, 69.25)] },
        { id: 'zone-2', ring: [at(41.1, 69.1), at(41.1, 69.2), at(41.2, 69.15)] },
      ]);
      await settle(fixture);

      expect(provider.map.livePolygons).toHaveLength(2);
      expect(zone.ring[1]).toEqual(at(41.35, 69.3));
    });
  });

  it('draws an area’s intensity and label, and restyles it in place when the numbers change (row 7.10)', async () => {
    const ring = [at(41.3, 69.2), at(41.3, 69.3), at(41.4, 69.25)];
    const fixture = create({
      areas: [{ id: 'zone-1', ring, intensity: 0.9, label: 'Центр: 42' }],
    });
    await settle(fixture);
    const [zone] = provider.map.livePolygons;
    expect(zone.intensity).toBe(0.9);
    expect(zone.label).toBe('Центр: 42');

    fixture.componentRef.setInput('areas', [
      { id: 'zone-1', ring, intensity: 0.2, label: 'Центр: 8' },
    ]);
    await settle(fixture);

    expect(provider.map.livePolygons).toEqual([zone]);
    expect(zone.intensity).toBe(0.2);
    expect(zone.label).toBe('Центр: 8');

    // An area given no intensity is a plain outline again: nothing keeps the old fill alive.
    fixture.componentRef.setInput('areas', [{ id: 'zone-1', ring }]);
    await settle(fixture);
    expect(zone.intensity).toBeNull();
    expect(zone.label).toBeNull();
  });

  it('destroys the map with the component, and never builds one for a component already gone', async () => {
    const fixture = create();
    await settle(fixture);
    const map = provider.map;
    fixture.destroy();
    expect(map.destroyed).toBe(true);

    const release = provider.holdLoad();
    const late = create();
    await settle(late);
    late.destroy();
    release();
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(provider.maps).toHaveLength(1);
  });
});
