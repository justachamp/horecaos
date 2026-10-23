import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ConfigurationResolutionView, LockedByPlanView } from '../../../core/api/configuration';
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

/** A ladder whose brand and company rows both store something, one by a named person and one by nobody the tenant knows. */
const WITH_PROVENANCE: ConfigurationResolutionView = {
  keyCode: 'ordering.cart_expiry_minutes',
  value: 180,
  cameFromDefault: false,
  source: 'SCOPED_VALUE',
  winningScope: 'BRAND',
  inspectedLevels: [
    { scopeType: 'LOCATION', outcome: 'NOT_SET' },
    {
      scopeType: 'BRAND',
      outcome: 'VALUE',
      version: 3,
      changedByName: 'A. Karimov',
      // 09:15 UTC is 14:15 in Tashkent.
      changedAt: '2026-09-30T09:15:00Z',
    },
    {
      scopeType: 'TENANT',
      outcome: 'VALUE',
      version: 1,
      changedByName: null,
      changedAt: '2026-08-12T05:20:00Z',
    },
    { scopeType: 'PLATFORM', outcome: 'NOT_SET' },
  ],
  describe: 'ordering.cart_expiry_minutes -> SCOPED_VALUE at BRAND',
  currentVersionAtScope: 3,
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

  it('names, for every level that stores a row, its version, who changed it and when', async () => {
    const fixture = await render(WITH_PROVENANCE, 'BRAND');
    (fixture.nativeElement.querySelector('.field__chip') as HTMLButtonElement).click();
    fixture.detectChanges();

    const who = [...fixture.nativeElement.querySelectorAll('[data-testid="trace-who"]')].map(
      (el: HTMLElement) => el.textContent?.trim(),
    );
    expect(who).toEqual([
      'Version 3 · A. Karimov · 30.09 14:15',
      'Version 1 · a person with no staff record here · 12.08 10:20',
    ]);
  });

  it('prints no who-line for a level where nothing is stored, and never an id', async () => {
    const fixture = await render(SET_AT_BRAND, 'BRAND');
    (fixture.nativeElement.querySelector('.field__chip') as HTMLButtonElement).click();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('[data-testid="trace-who"]')).toHaveLength(0);
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

  it('offers "revert to inherit" on a value set here by default', async () => {
    const fixture = await render(SET_AT_BRAND, 'BRAND');
    const labels = [...fixture.nativeElement.querySelectorAll('.field__action')].map(
      (action: HTMLElement) => action.textContent?.trim(),
    );
    expect(labels).toHaveLength(2);
  });

  it('drops "revert to inherit" for a versioned document that cannot be withdrawn, keeping edit', async () => {
    await TestBed.configureTestingModule({ imports: [InheritedField] }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(InheritedField);
    fixture.componentRef.setInput('label', 'Delivery');
    fixture.componentRef.setInput('resolution', SET_AT_BRAND);
    fixture.componentRef.setInput('currentScopeType', 'BRAND');
    fixture.componentRef.setInput('revertable', false);
    fixture.detectChanges();

    const actions = fixture.nativeElement.querySelectorAll('.field__action');
    expect(actions).toHaveLength(1);
    expect(actions[0].textContent).toContain('Edit');
  });

  it('renders a loading placeholder before the resolution arrives', async () => {
    const fixture = await render(null);
    expect(fixture.nativeElement.querySelector('.field__chip')).toBeFalsy();
  });

  describe('Locked by plan (settings.md §1.2, the fifth state)', () => {
    const LOCK: LockedByPlanView = {
      entitlementKey: 'assistant.answering.enabled',
      description: 'Whether the grounded assistant answers customers',
    };
    const LOCKED: ConfigurationResolutionView = { ...INHERITED_FROM_TENANT, lockedByPlan: LOCK };

    const lockedBlock = (fixture: ComponentFixture<InheritedField>): HTMLElement | null =>
      fixture.nativeElement.querySelector('[data-testid="field-locked"]');

    it('greys the field and names the module inline, in place of any control', async () => {
      const fixture = await render(LOCKED, 'BRAND');

      expect(fixture.nativeElement.querySelector('.field--locked')).toBeTruthy();
      expect(
        fixture.nativeElement.querySelector('[data-testid="field-locked-chip"]').textContent,
      ).toContain('Locked by plan');
      expect(lockedBlock(fixture)?.querySelector('[data-testid="q-locked-state"]')).toBeTruthy();
      expect(
        lockedBlock(fixture)?.querySelector('[data-testid="q-locked-state-module"]')?.textContent,
      ).toBe('assistant.answering.enabled');
      expect(
        fixture.nativeElement.querySelector('[data-testid="field-locked-what"]').textContent,
      ).toContain('grounded assistant');
    });

    it('offers nothing to do with the value: no edit, override, revert, or trace', async () => {
      const fixture = await render(LOCKED, 'BRAND');

      expect(fixture.nativeElement.querySelectorAll('.field__action')).toHaveLength(0);
      const buttons = [...fixture.nativeElement.querySelectorAll('button')];
      expect(buttons).toHaveLength(0);
    });

    it('links «Подробнее» to the console’s own subscription screen, and a plain click navigates there', async () => {
      const fixture = await render(LOCKED, 'BRAND');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      const link: HTMLAnchorElement = fixture.nativeElement.querySelector(
        '[data-testid="field-locked-more"]',
      );

      expect(link.getAttribute('href')).toBe('/finance/subscription');
      const click = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 });
      link.dispatchEvent(click);

      expect(navigate).toHaveBeenCalledWith('/finance/subscription');
      expect(click.defaultPrevented).toBe(true);
    });

    it('leaves a modified click to the browser, so it opens in a new tab', async () => {
      const fixture = await render(LOCKED, 'BRAND');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
      const link: HTMLAnchorElement = fixture.nativeElement.querySelector(
        '[data-testid="field-locked-more"]',
      );

      const click = new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true });
      link.dispatchEvent(click);

      expect(navigate).not.toHaveBeenCalled();
      expect(click.defaultPrevented).toBe(false);
    });

    it('never links to the platform’s own plan path: that is an API path, not a screen', async () => {
      const fixture = await render(
        { ...LOCKED, lockedByPlan: { ...LOCK, description: 'See /api/v1/control-plane/plans' } },
        'BRAND',
      );

      const link: HTMLAnchorElement = fixture.nativeElement.querySelector(
        '[data-testid="field-locked-more"]',
      );
      expect(link.getAttribute('href')).toBe('/finance/subscription');
      expect(fixture.nativeElement.querySelectorAll('a')).toHaveLength(1);
    });

    it('locks from the row’s own input before any resolution has arrived', async () => {
      await TestBed.configureTestingModule({ imports: [InheritedField] }).compileComponents();
      TestBed.inject(I18n).setLocale('en');
      const fixture = TestBed.createComponent(InheritedField);
      fixture.componentRef.setInput('label', 'Assistant');
      fixture.componentRef.setInput('currentScopeType', 'BRAND');
      fixture.componentRef.setInput('loading', true);
      fixture.componentRef.setInput('lockedByPlan', LOCK);
      fixture.detectChanges();

      expect(lockedBlock(fixture)).toBeTruthy();
      expect(fixture.nativeElement.textContent).not.toContain('Loading');
    });

    it('is the lock that decides when the level is also one this key cannot be set at', async () => {
      const fixture = await render(LOCKED, 'LOCATION', ['TENANT', 'BRAND']);

      expect(lockedBlock(fixture)).toBeTruthy();
      expect(fixture.nativeElement.textContent).not.toContain('Set at brand level');
    });

    it('goes back to the ordinary state when the plan gains the feature', async () => {
      const fixture = await render(LOCKED, 'BRAND');

      fixture.componentRef.setInput('resolution', INHERITED_FROM_TENANT);
      fixture.detectChanges();

      expect(lockedBlock(fixture)).toBeNull();
      expect(fixture.nativeElement.querySelector('.field--locked')).toBeNull();
      expect(fixture.nativeElement.textContent).toContain('Override here');
    });

    it('says it in the operator’s language', async () => {
      const fixture = await render(LOCKED, 'BRAND');
      TestBed.inject(I18n).setLocale('ru');
      fixture.detectChanges();

      expect(
        fixture.nativeElement.querySelector('[data-testid="field-locked-chip"]').textContent,
      ).toContain('Закрыто тарифом');
      expect(
        fixture.nativeElement.querySelector('[data-testid="field-locked-more"]').textContent,
      ).toContain('Подробнее');
    });
  });
});
