import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';

import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { I18n } from '../../../core/i18n/i18n';
import { InheritedField } from './inherited-field';

const SET_AT_BRAND: ConfigurationResolutionView = {
  keyCode: 'ordering.cart_expiry_minutes',
  value: 180,
  cameFromDefault: false,
  source: 'SCOPED_VALUE',
  winningScope: 'BRAND',
  inspectedLevels: [
    { scopeType: 'BRAND', outcome: 'VALUE' },
    { scopeType: 'TENANT', outcome: 'NOT_SET' },
    { scopeType: 'PLATFORM', outcome: 'NOT_SET' },
  ],
  describe: 'ordering.cart_expiry_minutes -> SCOPED_VALUE at BRAND',
  currentVersionAtScope: 3,
};

const INHERITED_FROM_TENANT: ConfigurationResolutionView = {
  keyCode: 'ordering.cart_expiry_minutes',
  value: 240,
  cameFromDefault: false,
  source: 'SCOPED_VALUE',
  winningScope: 'TENANT',
  inspectedLevels: [
    { scopeType: 'LOCATION', outcome: 'NOT_SET' },
    { scopeType: 'BRAND', outcome: 'NOT_SET' },
    { scopeType: 'TENANT', outcome: 'VALUE' },
    { scopeType: 'PLATFORM', outcome: 'NOT_SET' },
  ],
  describe: 'ordering.cart_expiry_minutes -> SCOPED_VALUE at TENANT',
  currentVersionAtScope: null,
};

const EXPLICIT_UNSET_AT_BRAND: ConfigurationResolutionView = {
  keyCode: 'ordering.cart_expiry_minutes',
  value: 240,
  cameFromDefault: false,
  source: 'SCOPED_VALUE',
  winningScope: 'TENANT',
  inspectedLevels: [
    { scopeType: 'BRAND', outcome: 'EXPLICIT_NULL_CONTINUED' },
    { scopeType: 'TENANT', outcome: 'VALUE' },
    { scopeType: 'PLATFORM', outcome: 'NOT_SET' },
  ],
  describe: 'ordering.cart_expiry_minutes -> SCOPED_VALUE at TENANT',
  currentVersionAtScope: 1,
};

describe('InheritedField', () => {
  async function render(
    resolution: ConfigurationResolutionView | null,
    currentScopeType: 'TENANT' | 'BRAND' | 'LOCATION' = 'BRAND',
    settableScopes: readonly ('PLATFORM' | 'TENANT' | 'BRAND' | 'LOCATION')[] = [],
  ): Promise<ComponentFixture<InheritedField>> {
    await TestBed.configureTestingModule({ imports: [InheritedField] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(InheritedField);
    fixture.componentRef.setInput('label', 'Cart expiry (minutes)');
    fixture.componentRef.setInput('resolution', resolution);
    fixture.componentRef.setInput('currentScopeType', currentScopeType);
    fixture.componentRef.setInput('settableScopes', settableScopes);
    fixture.detectChanges();
    return fixture;
  }

  it('renders "set here" with the value when the current scope won resolution', async () => {
    const fixture = await render(SET_AT_BRAND, 'BRAND');
    expect(fixture.nativeElement.textContent).toContain('180');
    expect(fixture.nativeElement.textContent).toContain('Set at this level');
  });

  it('renders "inherited" muted, naming where the value came from', async () => {
    const fixture = await render(INHERITED_FROM_TENANT, 'BRAND');
    expect(fixture.nativeElement.textContent).toContain('240');
    expect(fixture.nativeElement.querySelector('.field__value--muted')).toBeTruthy();
  });

  it('renders "explicitly unset" distinctly from an ordinary inherited value', async () => {
    const fixture = await render(EXPLICIT_UNSET_AT_BRAND, 'BRAND');
    expect(fixture.nativeElement.textContent).toContain('Unset here');
  });

  it('disables editing and names the narrower settable level when this scope cannot set it', async () => {
    const fixture = await render(INHERITED_FROM_TENANT, 'LOCATION', ['TENANT', 'BRAND']);
    expect(fixture.nativeElement.textContent).toContain('Set at brand level');
    expect(fixture.nativeElement.querySelectorAll('.field__action').length).toBe(0);
  });

  it('opens the resolution trace popover on the chip click, most specific level first', async () => {
    const fixture = await render(SET_AT_BRAND, 'BRAND');
    const chip: HTMLButtonElement = fixture.nativeElement.querySelector('.field__chip');
    chip.click();
    fixture.detectChanges();

    const rows = [...fixture.nativeElement.querySelectorAll('.field__ladderRow')];
    expect(rows).toHaveLength(3);
    expect(rows[0].textContent).toContain('Brand');
    expect(rows[0].classList.contains('field__ladderRow--winner')).toBe(true);
  });

  it('closes the popover on Escape', async () => {
    const fixture = await render(SET_AT_BRAND, 'BRAND');
    const chip: HTMLButtonElement = fixture.nativeElement.querySelector('.field__chip');
    chip.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.field__popover')).toBeTruthy();

    const popover: HTMLElement = fixture.nativeElement.querySelector('.field__popover');
    popover.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.field__popover')).toBeFalsy();
  });

  it('emits override when "Override here" is clicked on an inherited value', async () => {
    const fixture = await render(INHERITED_FROM_TENANT, 'BRAND');
    let emitted = false;
    fixture.componentInstance.override.subscribe(() => (emitted = true));

    const action: HTMLButtonElement = fixture.nativeElement.querySelector('.field__action');
    action.click();

    expect(emitted).toBe(true);
  });

  it('renders a loading placeholder before the resolution arrives', async () => {
    const fixture = await render(null);
    expect(fixture.nativeElement.querySelector('.field__chip')).toBeFalsy();
  });
});
