import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { I18n } from '../../../core/i18n/i18n';
import { ScopeBar, ScopeBarOption } from './scope-bar';

const BRANDS: readonly ScopeBarOption[] = [
  { id: 'brand-1', displayName: 'Rayhon' },
  { id: 'brand-2', displayName: 'Rayhon Юнусабад' },
];

const LOCATIONS: readonly ScopeBarOption[] = [
  { id: 'loc-1', displayName: 'Chilonzor', status: 'ACTIVE' },
  { id: 'loc-2', displayName: 'Yunusabad', status: 'SUSPENDED' },
];

describe('ScopeBar', () => {
  async function render(
    inputs: Partial<{
      showBrandPicker: boolean;
      brands: readonly ScopeBarOption[];
      selectedBrandId: string | null;
      locations: readonly ScopeBarOption[];
      selectedLocationId: string | null;
    }> = {},
  ): Promise<ComponentFixture<ScopeBar>> {
    await TestBed.configureTestingModule({ imports: [ScopeBar] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(ScopeBar);
    fixture.componentRef.setInput('brands', inputs.brands ?? BRANDS);
    fixture.componentRef.setInput('locations', inputs.locations ?? LOCATIONS);
    if (inputs.showBrandPicker !== undefined) {
      fixture.componentRef.setInput('showBrandPicker', inputs.showBrandPicker);
    }
    // Renders the <option>s first, on their own pass: a native <select>'s
    // [value] binding only selects an option that already exists in the DOM,
    // and Angular skips re-applying a property binding whose computed value
    // has not changed since the previous pass — so setting selectedBrandId
    // in this same first pass, before its own <option> exists, would leave
    // the select unselected forever.
    fixture.detectChanges();
    if (inputs.selectedBrandId !== undefined) {
      fixture.componentRef.setInput('selectedBrandId', inputs.selectedBrandId);
    }
    if (inputs.selectedLocationId !== undefined) {
      fixture.componentRef.setInput('selectedLocationId', inputs.selectedLocationId);
    }
    fixture.detectChanges();
    return fixture;
  }

  it('renders the brand and location pickers with the resolved option selected', async () => {
    const fixture = await render({ selectedBrandId: 'brand-2', selectedLocationId: 'loc-2' });
    const selects: HTMLSelectElement[] = [...fixture.nativeElement.querySelectorAll('select')];

    expect(selects[0].value).toBe('brand-2');
    expect(selects[1].value).toBe('loc-2');
  });

  it('hides the brand picker entirely when there is nothing to switch between', async () => {
    const fixture = await render({ showBrandPicker: false });

    expect(fixture.nativeElement.querySelectorAll('select').length).toBe(1);
  });

  it('reads "Все филиалы" as no location, editing at BRAND level', async () => {
    const fixture = await render({ selectedLocationId: null });

    expect(fixture.nativeElement.textContent).toContain('BRAND');
  });

  it('reads a selected location as editing at LOCATION level', async () => {
    const fixture = await render({ selectedLocationId: 'loc-1' });

    expect(fixture.nativeElement.textContent).toContain('LOCATION');
  });

  it('emits brandChange when a brand is picked', async () => {
    const fixture = await render();
    let emitted: string | null = null;
    fixture.componentInstance.brandChange.subscribe((id) => (emitted = id));

    const select: HTMLSelectElement = fixture.nativeElement.querySelectorAll('select')[0];
    select.value = 'brand-2';
    select.dispatchEvent(new Event('change'));

    expect(emitted).toBe('brand-2');
  });

  it('emits locationChange with null for "Все филиалы"', async () => {
    const fixture = await render({ selectedLocationId: 'loc-1' });
    let emitted: string | null | undefined;
    fixture.componentInstance.locationChange.subscribe((id) => (emitted = id));

    const select: HTMLSelectElement = fixture.nativeElement.querySelectorAll('select')[1];
    select.value = '';
    select.dispatchEvent(new Event('change'));

    expect(emitted).toBeNull();
  });

  it('disables the location picker and shows the chip when the current key is not settable at LOCATION', async () => {
    await TestBed.configureTestingModule({ imports: [ScopeBar] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(ScopeBar);
    fixture.componentRef.setInput('brands', BRANDS);
    fixture.componentRef.setInput('locations', LOCATIONS);
    fixture.componentRef.setInput('locationDisabledReason', 'Задаётся на уровне бренда');
    fixture.detectChanges();

    const select: HTMLSelectElement = fixture.nativeElement.querySelectorAll('select')[1];
    expect(select.disabled).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('Задаётся на уровне бренда');
  });
});
