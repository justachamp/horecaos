import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { RingProblem } from './geometry';
import { LatLng, MapBounds, MapUnavailableError } from './map-provider';
import { NullMapProvider, provideNullMapProvider } from './null-map-provider';
import { PolygonEditor } from './polygon-editor';

const CENTRE: LatLng = { latitude: 41.31, longitude: 69.24 };
const TASHKENT: MapBounds = {
  southWest: { latitude: 41.15, longitude: 69.04 },
  northEast: { latitude: 41.47, longitude: 69.46 },
};
const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });
const SQUARE: readonly LatLng[] = [at(41.3, 69.2), at(41.3, 69.3), at(41.4, 69.3), at(41.4, 69.2)];

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function all(fixture: ComponentFixture<unknown>, testId: string): HTMLElement[] {
  return [
    ...(fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>(
      `[data-testid="${testId}"]`,
    ),
  ];
}

function one(fixture: ComponentFixture<unknown>, testId: string): HTMLElement {
  return all(fixture, testId)[0];
}

function commit(input: HTMLElement, value: string): void {
  (input as HTMLInputElement).value = value;
  input.dispatchEvent(new Event('change'));
}

describe('PolygonEditor (ADR 0145, row X.4)', () => {
  let provider: NullMapProvider;
  let rings: (readonly LatLng[])[];
  let problems: (readonly RingProblem[])[];

  beforeEach(() => {
    provider = new NullMapProvider();
    rings = [];
    problems = [];
    TestBed.configureTestingModule({ providers: [provideNullMapProvider(provider)] });
    TestBed.inject(I18n).setLocale('en');
  });

  function create(inputs: Record<string, unknown> = {}): ComponentFixture<PolygonEditor> {
    const fixture = TestBed.createComponent(PolygonEditor);
    fixture.componentRef.setInput('center', CENTRE);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.componentInstance.ringChange.subscribe((r) => rings.push(r));
    fixture.componentInstance.problemsChange.subscribe((p) => problems.push(p));
    fixture.detectChanges();
    return fixture;
  }

  it('draws the outline it is given as an editable polygon, and fits the map to it', async () => {
    const fixture = create({ ring: SQUARE, region: TASHKENT });
    await settle(fixture);

    expect(provider.map.livePolygons).toHaveLength(1);
    expect(provider.map.livePolygons[0].ring).toEqual(SQUARE);
    expect(provider.map.livePolygons[0].editable).toBe(true);
    expect(provider.map.fitted[0]).toEqual({
      southWest: at(41.3, 69.2),
      northEast: at(41.4, 69.3),
    });
  });

  it('opens on the region when there is no outline yet, because that is where the person is looking', async () => {
    const fixture = create({ region: TASHKENT });
    await settle(fixture);

    expect(provider.map.fitted[0]).toEqual(TASHKENT);
    expect(all(fixture, 'q-polygon-corner')).toHaveLength(0);
  });

  it('reports a person’s edit on the map as the whole new outline', async () => {
    const fixture = create({ ring: SQUARE });
    await settle(fixture);
    const edited = [...SQUARE, at(41.45, 69.25)];

    provider.map.livePolygons[0].simulateEdit(edited);

    expect(rings).toEqual([edited]);
  });

  it('lists every corner with its coordinates, and the host’s new outline replaces them', async () => {
    const fixture = create({ ring: SQUARE });
    await settle(fixture);

    expect(all(fixture, 'q-polygon-corner')).toHaveLength(4);
    expect((one(fixture, 'q-polygon-corner-latitude') as HTMLInputElement).value).toBe('41.3');

    fixture.componentRef.setInput('ring', [...SQUARE, at(41.45, 69.25)]);
    await settle(fixture);

    expect(all(fixture, 'q-polygon-corner')).toHaveLength(5);
    expect(provider.map.livePolygons[0].ring).toHaveLength(5);
  });

  describe('editing without a pointer', () => {
    it('retypes a corner on commit, and reports the outline with that corner changed', async () => {
      const fixture = create({ ring: SQUARE });
      await settle(fixture);

      commit(all(fixture, 'q-polygon-corner-longitude')[2], '69.35');

      expect(rings).toEqual([[SQUARE[0], SQUARE[1], at(41.4, 69.35), SQUARE[3]]]);
    });

    it('refuses a corner that is not a coordinate, says so, and reports nothing', async () => {
      const fixture = create({ ring: SQUARE });
      await settle(fixture);

      commit(all(fixture, 'q-polygon-corner-latitude')[1], '141.3');
      await settle(fixture);

      expect(rings).toEqual([]);
      expect(one(fixture, 'q-polygon-row-error')).toBeTruthy();
      expect(all(fixture, 'q-polygon-corner-latitude')[1].getAttribute('aria-invalid')).toBe(
        'true',
      );

      commit(all(fixture, 'q-polygon-corner-latitude')[1], '41.31');
      await settle(fixture);
      expect(all(fixture, 'q-polygon-row-error')).toHaveLength(0);
    });

    it('removes a corner', async () => {
      const fixture = create({ ring: SQUARE });
      await settle(fixture);

      all(fixture, 'q-polygon-remove-corner')[1].click();

      expect(rings).toEqual([[SQUARE[0], SQUARE[2], SQUARE[3]]]);
    });

    it('adds a corner a little way from the last, so a keyboard can build a zone from nothing', async () => {
      const fixture = create({ ring: SQUARE });
      await settle(fixture);
      one(fixture, 'q-polygon-add-corner').click();
      expect(rings[0]).toHaveLength(5);
      expect(rings[0][4].latitude).toBeCloseTo(41.4005, 6);
      expect(rings[0][4].longitude).toBeCloseTo(69.2005, 6);

      rings = [];
      const empty = create();
      await settle(empty);
      one(empty, 'q-polygon-add-corner').click();
      expect(rings).toEqual([[CENTRE]]);
    });

    it('clears the outline', async () => {
      const fixture = create({ ring: SQUARE });
      await settle(fixture);

      one(fixture, 'q-polygon-clear').click();

      expect(rings).toEqual([[]]);
    });
  });

  it('starts and stops drawing on the map, and says which it is doing', async () => {
    const fixture = create();
    await settle(fixture);
    const button = one(fixture, 'q-polygon-draw');
    expect(button.getAttribute('aria-pressed')).toBe('false');

    button.click();
    await settle(fixture);
    expect(provider.map.livePolygons[0].drawing).toBe(true);
    expect(button.getAttribute('aria-pressed')).toBe('true');

    button.click();
    await settle(fixture);
    expect(provider.map.livePolygons[0].drawing).toBe(false);
  });

  describe('what is wrong with the outline', () => {
    function shown(fixture: ComponentFixture<unknown>): string[] {
      return all(fixture, 'q-polygon-problems')
        .flatMap((list) => [...list.querySelectorAll('li')])
        .map((item) => item.getAttribute('data-problem') ?? '');
    }

    it('says nothing about an outline not begun, and tells the host it is not yet usable', async () => {
      const fixture = create();
      await settle(fixture);

      expect(shown(fixture)).toEqual([]);
      expect(problems.at(-1)).toEqual(['TOO_FEW']);
    });

    it('says nothing about a good outline, and reports no problem', async () => {
      const fixture = create({ ring: SQUARE, region: TASHKENT });
      await settle(fixture);

      expect(shown(fixture)).toEqual([]);
      expect(problems.at(-1)).toEqual([]);
    });

    it('names too few corners, a crossing, and a corner outside the region, in words', async () => {
      const two = create({ ring: [at(41.3, 69.2), at(41.3, 69.3)] });
      await settle(two);
      expect(shown(two)).toEqual(['TOO_FEW']);
      expect(two.nativeElement.textContent).toContain('at least three corners');

      const bowTie = create({
        ring: [at(41.3, 69.2), at(41.4, 69.3), at(41.4, 69.2), at(41.3, 69.3)],
      });
      await settle(bowTie);
      expect(shown(bowTie)).toEqual(['CROSSING']);

      const swapped = create({
        ring: SQUARE.map((c) => at(c.longitude, c.latitude)),
        region: TASHKENT,
      });
      await settle(swapped);
      expect(shown(swapped)).toEqual(['OUTSIDE_REGION']);
      expect(problems.at(-1)).toEqual(['OUTSIDE_REGION']);
    });
  });

  it('is read-only when it is not editable', async () => {
    const fixture = create({ ring: SQUARE, editable: false });
    await settle(fixture);

    expect(provider.map.livePolygons[0].editable).toBe(false);
    expect(all(fixture, 'q-polygon-draw')).toHaveLength(0);
    expect(all(fixture, 'q-polygon-remove-corner')).toHaveLength(0);
    expect((one(fixture, 'q-polygon-corner-latitude') as HTMLInputElement).disabled).toBe(true);
    provider.map.livePolygons[0].simulateEdit([]);
    expect(rings).toEqual([]);
  });

  it('keeps the corner table when there is no map, so a zone can still be authored', async () => {
    provider.loadError = new MapUnavailableError('NOT_CONFIGURED');
    const fixture = create({ ring: SQUARE });
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('No map provider is set up');
    commit(all(fixture, 'q-polygon-corner-latitude')[0], '41.31');
    expect(rings).toHaveLength(1);
  });
});
