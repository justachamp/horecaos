import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { MapPin } from './map-pin';
import { LatLng, MapBounds, MapUnavailableError } from './map-provider';
import { NullMapProvider, provideNullMapProvider } from './null-map-provider';

const CENTRE: LatLng = { latitude: 41.31, longitude: 69.24 };
const TASHKENT: MapBounds = {
  southWest: { latitude: 41.15, longitude: 69.04 },
  northEast: { latitude: 41.47, longitude: 69.46 },
};
const at = (latitude: number, longitude: number): LatLng => ({ latitude, longitude });

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function field(fixture: ComponentFixture<unknown>, testId: string): HTMLInputElement {
  return (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
    `[data-testid="${testId}"]`,
  )!;
}

function exists(fixture: ComponentFixture<unknown>, testId: string): boolean {
  return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testId}"]`) !== null;
}

function type(input: HTMLInputElement, value: string): void {
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

describe('MapPin (ADR 0145, row X.4)', () => {
  let provider: NullMapProvider;
  let emitted: (LatLng | null)[];

  beforeEach(() => {
    provider = new NullMapProvider();
    emitted = [];
    TestBed.configureTestingModule({ providers: [provideNullMapProvider(provider)] });
    TestBed.inject(I18n).setLocale('en');
  });

  function create(inputs: Record<string, unknown> = {}): ComponentFixture<MapPin> {
    const fixture = TestBed.createComponent(MapPin);
    fixture.componentRef.setInput('center', CENTRE);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.componentInstance.positionChange.subscribe((p) => emitted.push(p));
    fixture.detectChanges();
    return fixture;
  }

  it('places a draggable pin where it is told and shows the coordinates as fields', async () => {
    const fixture = create({ position: at(41.3111, 69.2797) });
    await settle(fixture);

    expect(provider.map.livePins).toHaveLength(1);
    expect(provider.map.livePins[0].position).toEqual(at(41.3111, 69.2797));
    expect(provider.map.livePins[0].draggable).toBe(true);
    expect(field(fixture, 'q-map-pin-latitude').value).toBe('41.3111');
    expect(field(fixture, 'q-map-pin-longitude').value).toBe('69.2797');
    expect(exists(fixture, 'q-map-pin-remove')).toBe(true);
  });

  it('draws no pin until there is a point, and a click on the map then places one', async () => {
    const fixture = create();
    await settle(fixture);
    expect(provider.map.livePins).toHaveLength(0);
    expect(exists(fixture, 'q-map-pin-remove')).toBe(false);

    provider.map.simulateClick(at(41.32, 69.25));

    expect(emitted).toEqual([at(41.32, 69.25)]);
  });

  it('reports where a drag ended', async () => {
    const fixture = create({ position: at(41.3, 69.2) });
    await settle(fixture);

    provider.map.livePins[0].simulateDrag(at(41.33, 69.23));

    expect(emitted).toEqual([at(41.33, 69.23)]);
  });

  it('moves the pin and the fields when the host changes the point, and brings the new place into view', async () => {
    const fixture = create({ position: at(41.3, 69.2) });
    await settle(fixture);

    fixture.componentRef.setInput('position', at(41.4, 69.3));
    await settle(fixture);

    expect(provider.map.livePins[0].position).toEqual(at(41.4, 69.3));
    expect(field(fixture, 'q-map-pin-latitude').value).toBe('41.4');
    expect(provider.map.centre).toEqual(at(41.4, 69.3));
  });

  it('does not yank the map around when the host merely echoes the pin it was just given', async () => {
    const fixture = create({ position: at(41.3, 69.2) });
    await settle(fixture);
    provider.map.setCenter(at(41.0, 69.0));

    provider.map.livePins[0].simulateDrag(at(41.33, 69.23));
    fixture.componentRef.setInput('position', at(41.33, 69.23));
    await settle(fixture);

    expect(provider.map.centre).toEqual(at(41.0, 69.0));
  });

  describe('typing the coordinates, which is the keyboard equivalent of a drag', () => {
    it('reports a complete, valid point', async () => {
      const fixture = create({ position: at(41.3, 69.2) });
      await settle(fixture);

      type(field(fixture, 'q-map-pin-latitude'), '41.35');

      expect(emitted).toEqual([at(41.35, 69.2)]);
      expect(exists(fixture, 'q-map-pin-invalid')).toBe(false);
    });

    it('does not rewrite what the person is typing, even while the host still holds the old point', async () => {
      const fixture = create({ position: at(41.3, 69.2) });
      await settle(fixture);

      type(field(fixture, 'q-map-pin-latitude'), '41.35');
      type(field(fixture, 'q-map-pin-latitude'), '41.35');
      await settle(fixture);

      expect(field(fixture, 'q-map-pin-latitude').value).toBe('41.35');
    });

    it('says a half-typed or impossible coordinate is wrong, and reports nothing', async () => {
      const fixture = create({ position: at(41.3, 69.2) });
      await settle(fixture);

      type(field(fixture, 'q-map-pin-latitude'), '141.3');
      fixture.detectChanges();
      expect(emitted).toEqual([]);
      expect(exists(fixture, 'q-map-pin-invalid')).toBe(true);
      expect(field(fixture, 'q-map-pin-latitude').getAttribute('aria-invalid')).toBe('true');

      type(field(fixture, 'q-map-pin-latitude'), '41.4x');
      expect(emitted).toEqual([]);

      type(field(fixture, 'q-map-pin-longitude'), '');
      expect(emitted).toEqual([]);
    });

    it('reports the pin removed when both fields are cleared, or the remove button is pressed', async () => {
      const fixture = create({ position: at(41.3, 69.2) });
      await settle(fixture);

      type(field(fixture, 'q-map-pin-latitude'), '');
      type(field(fixture, 'q-map-pin-longitude'), '');
      expect(emitted).toEqual([null]);

      field(fixture, 'q-map-pin-remove').click();
      expect(emitted).toEqual([null, null]);
    });
  });

  it('warns about a point outside the region, and reports it all the same', async () => {
    const fixture = create({ position: at(41.3, 69.2), region: TASHKENT });
    await settle(fixture);
    expect(fixture.nativeElement.textContent).not.toContain('outside the region');

    // A latitude and a longitude swapped: a valid point, simply somewhere else.
    fixture.componentRef.setInput('position', at(69.2, 41.3));
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('outside the region');
    type(field(fixture, 'q-map-pin-latitude'), '69.3');
    expect(emitted).toEqual([at(69.3, 41.3)]);
  });

  it('is read-only when it is not editable: no drag, no click, no typing, no remove', async () => {
    const fixture = create({ position: at(41.3, 69.2), editable: false });
    await settle(fixture);

    expect(provider.map.livePins[0].draggable).toBe(false);
    provider.map.livePins[0].simulateDrag(at(41.4, 69.3));
    provider.map.simulateClick(at(41.4, 69.3));

    expect(emitted).toEqual([]);
    expect(field(fixture, 'q-map-pin-latitude').disabled).toBe(true);
    expect(exists(fixture, 'q-map-pin-remove')).toBe(false);
    expect(exists(fixture, 'q-map-pin-hint')).toBe(false);
  });

  it('still lets the coordinates be typed when there is no map, which is every environment until a key exists', async () => {
    provider.loadError = new MapUnavailableError('NOT_CONFIGURED');
    const fixture = create();
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('No map provider is set up');
    type(field(fixture, 'q-map-pin-latitude'), '41.3');
    type(field(fixture, 'q-map-pin-longitude'), '69.2');

    expect(emitted).toEqual([at(41.3, 69.2)]);
  });

  it('moves a pin that may not be taken away, but never removes it (a branch needs a point)', async () => {
    const fixture = create({ position: at(41.3111, 69.2797), removable: false });
    await settle(fixture);

    expect(exists(fixture, 'q-map-pin-remove')).toBe(false);

    // Dragging and typing still move it. (The host here never feeds a drag back in, so the
    // longitude field still shows where the pin was first placed.)
    provider.map.livePins[0].simulateDrag(at(41.32, 69.28));
    type(field(fixture, 'q-map-pin-latitude'), '41.33');
    await settle(fixture);
    expect(emitted).toEqual([at(41.32, 69.28), at(41.33, 69.2797)]);

    // Emptying both fields is a mistake to be told about, not a removal to be reported.
    type(field(fixture, 'q-map-pin-latitude'), '');
    type(field(fixture, 'q-map-pin-longitude'), '');
    await settle(fixture);
    expect(emitted).toHaveLength(2);
    expect(exists(fixture, 'q-map-pin-invalid')).toBe(true);
  });
});
