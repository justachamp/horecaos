import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { BoundingBoxEditor } from './bbox-editor';
import { LatLng, MapBounds, MapUnavailableError } from './map-provider';
import { NullMapProvider, provideNullMapProvider } from './null-map-provider';

const CENTRE: LatLng = { latitude: 41.31, longitude: 69.24 };
const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });
const TASHKENT: MapBounds = { southWest: at(41.15, 69.04), northEast: at(41.47, 69.46) };

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function input(fixture: ComponentFixture<unknown>, testId: string): HTMLInputElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
    `[data-testid="${testId}"]`,
  )!;
}

function exists(fixture: ComponentFixture<unknown>, testId: string): boolean {
  return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`) !== null;
}

function type(field: HTMLInputElement, value: string): void {
  field.value = value;
  field.dispatchEvent(new Event('input'));
}

describe('BoundingBoxEditor (ADR 0145, ADR 0037, row X.4)', () => {
  let provider: NullMapProvider;
  let boxes: MapBounds[];

  beforeEach(() => {
    provider = new NullMapProvider();
    boxes = [];
    TestBed.configureTestingModule({ providers: [provideNullMapProvider(provider)] });
    TestBed.inject(I18n).setLocale('en');
  });

  function create(inputs: Record<string, unknown> = {}): ComponentFixture<BoundingBoxEditor> {
    const fixture = TestBed.createComponent(BoundingBoxEditor);
    fixture.componentRef.setInput('center', CENTRE);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.componentInstance.boundsChange.subscribe((b) => boxes.push(b));
    fixture.detectChanges();
    return fixture;
  }

  it('shows the four numbers and draws the box as an editable rectangle, fitted to it', async () => {
    const fixture = create({ bounds: TASHKENT });
    await settle(fixture);

    expect(input(fixture, 'q-bbox-sw-lat').value).toBe('41.15');
    expect(input(fixture, 'q-bbox-sw-lon').value).toBe('69.04');
    expect(input(fixture, 'q-bbox-ne-lat').value).toBe('41.47');
    expect(input(fixture, 'q-bbox-ne-lon').value).toBe('69.46');
    expect(provider.map.liveRectangles).toHaveLength(1);
    expect(provider.map.liveRectangles[0].bounds).toEqual(TASHKENT);
    expect(provider.map.liveRectangles[0].editable).toBe(true);
    expect(provider.map.fitted[0]).toEqual(TASHKENT);
  });

  it('reports a drag of the rectangle as the new box', async () => {
    const fixture = create({ bounds: TASHKENT });
    await settle(fixture);
    const dragged = { southWest: at(41.1, 69.0), northEast: at(41.5, 69.5) };

    provider.map.liveRectangles[0].simulateResize(dragged);

    expect(boxes).toEqual([dragged]);
  });

  it('follows the box when the host changes it, numbers and rectangle together', async () => {
    const fixture = create({ bounds: TASHKENT });
    await settle(fixture);
    const next = { southWest: at(41.2, 69.1), northEast: at(41.4, 69.4) };

    fixture.componentRef.setInput('bounds', next);
    await settle(fixture);

    expect(input(fixture, 'q-bbox-sw-lat').value).toBe('41.2');
    expect(provider.map.liveRectangles[0].bounds).toEqual(next);
  });

  describe('typing the numbers', () => {
    it('reports a box once all four make a valid one, and not before', async () => {
      const fixture = create({ bounds: TASHKENT });
      await settle(fixture);

      type(input(fixture, 'q-bbox-ne-lat'), '41.5');

      expect(boxes).toEqual([{ southWest: at(41.15, 69.04), northEast: at(41.5, 69.46) }]);
    });

    it('does not rewrite what the person is typing while the host still holds the old box', async () => {
      const fixture = create({ bounds: TASHKENT });
      await settle(fixture);

      type(input(fixture, 'q-bbox-ne-lat'), '41.5');
      type(input(fixture, 'q-bbox-ne-lat'), '41.50');
      await settle(fixture);

      expect(input(fixture, 'q-bbox-ne-lat').value).toBe('41.50');
    });

    it('says an inverted box is wrong, in the database’s own terms, and reports nothing', async () => {
      const fixture = create({ bounds: TASHKENT });
      await settle(fixture);

      type(input(fixture, 'q-bbox-ne-lat'), '41.1');
      await settle(fixture);

      expect(boxes).toEqual([]);
      const problems = (fixture.nativeElement as HTMLElement).querySelector(
        '[data-testid="q-bbox-problems"]',
      );
      expect(problems?.textContent).toContain('north and east of the south-west corner');
    });

    it('says an impossible latitude is wrong and reports nothing', async () => {
      const fixture = create({ bounds: TASHKENT });
      await settle(fixture);

      type(input(fixture, 'q-bbox-sw-lat'), '-91');
      await settle(fixture);

      expect(boxes).toEqual([]);
      expect(exists(fixture, 'q-bbox-invalid')).toBe(true);
    });

    it('stays quiet about a form that is simply not finished', async () => {
      const fixture = create({ bounds: TASHKENT });
      await settle(fixture);

      type(input(fixture, 'q-bbox-sw-lat'), '');
      await settle(fixture);

      expect(boxes).toEqual([]);
      expect(exists(fixture, 'q-bbox-invalid')).toBe(false);
      expect(exists(fixture, 'q-bbox-problems')).toBe(false);
    });
  });

  describe('with no box yet', () => {
    it('takes two clicks on the map as the two opposite corners, whichever order they come in', async () => {
      const fixture = create();
      await settle(fixture);
      expect(fixture.nativeElement.textContent).toContain('Click two opposite corners');

      provider.map.simulateClick(at(41.47, 69.04));
      expect(boxes).toEqual([]);
      provider.map.simulateClick(at(41.15, 69.46));

      expect(boxes).toEqual([{ southWest: at(41.15, 69.04), northEast: at(41.47, 69.46) }]);
    });

    it('does not read a click as a corner once there is a box to drag', async () => {
      const fixture = create({ bounds: TASHKENT });
      await settle(fixture);

      provider.map.simulateClick(at(41.2, 69.1));
      provider.map.simulateClick(at(41.3, 69.2));

      expect(boxes).toEqual([]);
    });

    it('refuses two clicks that make no area', async () => {
      const fixture = create();
      await settle(fixture);

      provider.map.simulateClick(at(41.3, 69.2));
      provider.map.simulateClick(at(41.3, 69.2));

      expect(boxes).toEqual([]);
    });
  });

  it('is read-only when it is not editable', async () => {
    const fixture = create({ bounds: TASHKENT, editable: false });
    await settle(fixture);

    expect(provider.map.liveRectangles[0].editable).toBe(false);
    expect(input(fixture, 'q-bbox-sw-lat').disabled).toBe(true);
    provider.map.liveRectangles[0].simulateResize({ southWest: at(41, 69), northEast: at(42, 70) });
    expect(boxes).toEqual([]);
  });

  it('is still the four numbers it was before there was a map, when there is none', async () => {
    provider.loadError = new MapUnavailableError('NOT_CONFIGURED');
    const fixture = create({ bounds: TASHKENT });
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('No map provider is set up');
    type(input(fixture, 'q-bbox-ne-lon'), '69.5');

    expect(boxes).toEqual([{ southWest: at(41.15, 69.04), northEast: at(41.47, 69.5) }]);
  });
});
