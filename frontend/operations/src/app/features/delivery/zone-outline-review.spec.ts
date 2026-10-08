import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { MapBounds, MapUnavailableError } from '../../shared/ui/map/map-provider';
import { NullMapProvider, provideNullMapProvider } from '../../shared/ui/map/null-map-provider';
import { DeliveryZonesApi, ZoneOutlineResponse } from './delivery-zones-api';
import { ZoneOutlineReview } from './zone-outline-review';

const SCOPE = { tenantId: 't1', brandId: 'b1' };
const TASHKENT: MapBounds = {
  southWest: { latitude: 41.15, longitude: 69.04 },
  northEast: { latitude: 41.47, longitude: 69.46 },
};

function outline(ring: { latitude: number; longitude: number }[], holes = 0): ZoneOutlineResponse {
  return {
    zoneId: 'z1',
    code: 'CITY',
    role: 'DELIVERY',
    version: 2,
    status: 'DRAFT',
    shapeKind: 'POLYGON',
    polygons: [{ ring, holes: Array.from({ length: holes }, () => ring) }],
  };
}

const INSIDE = outline([
  { latitude: 41.3, longitude: 69.2 },
  { latitude: 41.3, longitude: 69.3 },
  { latitude: 41.4, longitude: 69.25 },
]);

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
  await new Promise((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

describe('ZoneOutlineReview (rows 3.6, 3.6c; ADR 0037, ADR 0145)', () => {
  let provider: NullMapProvider;
  let read: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    provider = new NullMapProvider();
    read = vi.fn().mockResolvedValue(INSIDE);
    TestBed.configureTestingModule({
      providers: [
        provideNullMapProvider(provider),
        { provide: DeliveryZonesApi, useValue: { outline: read } },
      ],
    });
    TestBed.inject(I18n).setLocale('en');
  });

  function create(inputs: Record<string, unknown> = {}): ComponentFixture<ZoneOutlineReview> {
    const fixture = TestBed.createComponent(ZoneOutlineReview);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('zoneId', 'z1');
    fixture.componentRef.setInput('version', 2);
    fixture.componentRef.setInput('zoneCode', 'CITY');
    fixture.componentRef.setInput('region', TASHKENT);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    return fixture;
  }

  function el(fixture: ComponentFixture<unknown>, id: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);
  }

  it('reads the stored version and draws it beside the region it must sit in', async () => {
    const fixture = create();
    await settle(fixture);

    expect(read).toHaveBeenCalledWith(SCOPE, 'z1', 2);
    expect(provider.map.livePolygons).toHaveLength(1);
    expect(provider.map.livePolygons[0].editable).toBe(false);
    expect(provider.map.liveRectangles).toHaveLength(1);
    expect(provider.map.liveRectangles[0].bounds).toEqual(TASHKENT);
    expect(el(fixture, 'zone-review-verdict')?.getAttribute('data-verdict')).toBe('INSIDE');
    expect(provider.map.fitted.at(-1)?.southWest.latitude).toBeLessThanOrEqual(41.15);
  });

  it('names the likely mistake when an outline would be inside the region with its coordinates swapped', async () => {
    read.mockResolvedValue(
      outline([
        { latitude: 69.2, longitude: 41.3 },
        { latitude: 69.3, longitude: 41.3 },
        { latitude: 69.25, longitude: 41.4 },
      ]),
    );
    const fixture = create();
    await settle(fixture);

    expect(el(fixture, 'zone-review-verdict')?.getAttribute('data-verdict')).toBe('LIKELY_SWAPPED');
    expect(el(fixture, 'zone-review-verdict')?.textContent).toContain('swapped');
  });

  it('says outside for an outline that is simply elsewhere, and says nothing it cannot know without a region', async () => {
    read.mockResolvedValue(
      outline([
        { latitude: 39.6, longitude: 66.9 },
        { latitude: 39.7, longitude: 66.9 },
        { latitude: 39.65, longitude: 67 },
      ]),
    );
    const outside = create();
    await settle(outside);
    expect(el(outside, 'zone-review-verdict')?.getAttribute('data-verdict')).toBe('OUTSIDE');

    const unbounded = create({ region: null });
    await settle(unbounded);
    expect(el(unbounded, 'zone-review-verdict')?.getAttribute('data-verdict')).toBe('NO_REGION');
  });

  it('lists the corners as numbers beside the map, and says how many more there are', async () => {
    const many = Array.from({ length: 15 }, (_, i) => ({
      latitude: 41.3 + i * 0.001,
      longitude: 69.2 + (i % 2) * 0.01,
    }));
    read.mockResolvedValue(outline(many));
    const fixture = create();
    await settle(fixture);

    expect(
      (fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="zone-review-corner"]'),
    ).toHaveLength(12);
    expect(fixture.nativeElement.textContent).toContain('15 corners');
    expect(fixture.nativeElement.textContent).toContain('3 more corners');
  });

  it('names the tariff the zone prices with, or says it falls through to the branch’s', async () => {
    const withTariff = create({ tariff: 'CITY — City tariff' });
    await settle(withTariff);
    expect(el(withTariff, 'zone-review-tariff')?.textContent).toContain('City tariff');

    const without = create({ tariff: null });
    await settle(without);
    expect(el(without, 'zone-review-tariff')?.textContent).toContain("branch's tariff");
  });

  describe('in activate mode', () => {
    it('keeps Activate disabled until the reviewer has confirmed, then asks the host to activate once', async () => {
      const fixture = create({ mode: 'activate' });
      const activations: number[] = [];
      fixture.componentInstance.activate.subscribe(() => activations.push(1));
      await settle(fixture);
      const button = el(fixture, 'zone-review-activate') as HTMLButtonElement;

      expect(button.disabled).toBe(true);
      button.click();
      expect(activations).toHaveLength(0);

      const confirm = el(fixture, 'zone-review-confirm') as HTMLInputElement;
      confirm.checked = true;
      confirm.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      expect(button.disabled).toBe(false);
      button.click();

      expect(activations).toHaveLength(1);
    });

    it('does not let a busy host be asked twice', async () => {
      const fixture = create({ mode: 'activate' });
      await settle(fixture);
      const confirm = el(fixture, 'zone-review-confirm') as HTMLInputElement;
      confirm.checked = true;
      confirm.dispatchEvent(new Event('change'));
      fixture.componentRef.setInput('busy', true);
      fixture.detectChanges();

      expect((el(fixture, 'zone-review-activate') as HTMLButtonElement).disabled).toBe(true);
    });

    it('forgets a confirmation when it is asked to review a different version', async () => {
      const fixture = create({ mode: 'activate' });
      await settle(fixture);
      const confirm = el(fixture, 'zone-review-confirm') as HTMLInputElement;
      confirm.checked = true;
      confirm.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      fixture.componentRef.setInput('version', 3);
      await settle(fixture);

      expect(read).toHaveBeenLastCalledWith(SCOPE, 'z1', 3);
      expect((el(fixture, 'zone-review-activate') as HTMLButtonElement).disabled).toBe(true);
    });

    it('says what was checked when there is no map: coordinates, not a map', async () => {
      provider.loadError = new MapUnavailableError('NOT_CONFIGURED');
      const fixture = create({ mode: 'activate' });
      await settle(fixture);

      expect(fixture.nativeElement.textContent).toContain('No map provider is set up');
      expect(el(fixture, 'zone-review-confirm')?.parentElement?.textContent).toContain(
        'checked the corner coordinates',
      );
      expect(el(fixture, 'zone-review-corner')).not.toBeNull();
    });

    it('cannot activate what it could not read', async () => {
      read.mockRejectedValue(new Error('boom'));
      const fixture = create({ mode: 'activate' });
      await settle(fixture);

      expect(el(fixture, 'zone-review-failed')).not.toBeNull();
      expect(el(fixture, 'zone-review-confirm')).toBeNull();
      expect((el(fixture, 'zone-review-activate') as HTMLButtonElement).disabled).toBe(true);
    });
  });

  it('only shows in view mode: no confirmation and no Activate', async () => {
    const fixture = create({ mode: 'view' });
    await settle(fixture);

    expect(el(fixture, 'zone-review-confirm')).toBeNull();
    expect(el(fixture, 'zone-review-activate')).toBeNull();
  });

  it('says when the map shows only the outer outline of a shape with holes', async () => {
    read.mockResolvedValue(outline(INSIDE.polygons[0].ring as never, 1));
    const fixture = create();
    await settle(fixture);

    expect(el(fixture, 'zone-review-simplified')).not.toBeNull();
  });

  it('closes when asked', async () => {
    const fixture = create();
    const closed: number[] = [];
    fixture.componentInstance.closed.subscribe(() => closed.push(1));
    await settle(fixture);

    (el(fixture, 'zone-review-close') as HTMLButtonElement).click();

    expect(closed).toHaveLength(1);
  });
});
