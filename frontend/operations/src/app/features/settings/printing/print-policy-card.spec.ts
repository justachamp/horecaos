import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ConfigurationResolutionView } from '../../../core/api/configuration';
import { LocationScope } from '../../../core/api/operations-paths';
import { I18n } from '../../../core/i18n/i18n';
import { ConfigurationApi } from '../configuration-api';
import { PrintPolicyCard } from './print-policy-card';
import { all, click, el, flush, pick, type } from './printing-dom';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function resolution(
  code: string,
  value: unknown,
  overrides: Partial<ConfigurationResolutionView> = {},
): ConfigurationResolutionView {
  return {
    keyCode: code,
    value,
    cameFromDefault: true,
    source: 'CODE_DEFAULT',
    winningScope: null,
    inspectedLevels: [{ scopeType: 'LOCATION', outcome: 'NOT_SET' }],
    describe: `${code} -> CODE_DEFAULT`,
    currentVersionAtScope: null,
    ...overrides,
  };
}

const DEFAULTS: Readonly<Record<string, unknown>> = {
  'printing.document_locale': 'ru',
  'printing.kitchen_ticket.expire_minutes': 30,
  'printing.late_marker_minutes': 5,
};

function fakeConfig(overrides: Record<string, ConfigurationResolutionView> = {}) {
  return {
    resolution: vi.fn(
      async (_tenant: string, code: string) => overrides[code] ?? resolution(code, DEFAULTS[code]),
    ),
    setValue: vi.fn().mockResolvedValue({ id: 'v1' }),
  };
}

async function render(
  config: ReturnType<typeof fakeConfig>,
): Promise<ComponentFixture<PrintPolicyCard>> {
  await TestBed.configureTestingModule({
    imports: [PrintPolicyCard],
    providers: [{ provide: ConfigurationApi, useValue: config }],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(PrintPolicyCard);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

function actions(fixture: ComponentFixture<unknown>, code: string): HTMLButtonElement[] {
  return all<HTMLButtonElement>(fixture, `[data-field="${code}"] .field__action`);
}

describe('PrintPolicyCard', () => {
  it('reads the three keys at the branch and shows each value in words', async () => {
    const config = fakeConfig();
    const fixture = await render(config);

    expect(config.resolution).toHaveBeenCalledWith(
      't1',
      'printing.document_locale',
      'LOCATION',
      'b1',
      'l1',
    );
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Russian');
    expect(text).toContain('30 min');
    expect(text).toContain('5 min');
  });

  it('sets the late marker at the branch with a reason, against the version it was read at', async () => {
    const config = fakeConfig({
      'printing.late_marker_minutes': resolution('printing.late_marker_minutes', 10, {
        cameFromDefault: false,
        source: 'SCOPED_VALUE',
        winningScope: 'LOCATION',
        currentVersionAtScope: 3,
        inspectedLevels: [{ scopeType: 'LOCATION', outcome: 'VALUE', version: 3 }],
      }),
    });
    const fixture = await render(config);

    // Set here: the first action is edit, the second is revert.
    actions(fixture, 'printing.late_marker_minutes')[0].click();
    fixture.detectChanges();
    expect(el<HTMLButtonElement>(fixture, '[data-testid="print-policy-save"]').disabled).toBe(true);
    type(fixture, '[data-testid="print-policy-minutes"]', '15');
    type(fixture, '[data-testid="print-policy-reason"]', 'slow printer at the pass');
    await click(fixture, '[data-testid="print-policy-save"]');

    expect(config.setValue).toHaveBeenCalledWith('t1', 'printing.late_marker_minutes', {
      scopeType: 'LOCATION',
      brandId: 'b1',
      locationId: 'l1',
      explicitNull: false,
      expectedVersion: 3,
      reason: 'slow printer at the pass',
      integerValue: 15,
    });
    expect(el(fixture, '[data-testid="print-policy-notice"]').textContent).toContain('Saved');
  });

  it('refuses a number outside the key’s range before asking the platform', async () => {
    const config = fakeConfig();
    const fixture = await render(config);

    actions(fixture, 'printing.kitchen_ticket.expire_minutes')[0].click();
    fixture.detectChanges();
    type(fixture, '[data-testid="print-policy-reason"]', 'because');
    type(fixture, '[data-testid="print-policy-minutes"]', '3');
    expect(el<HTMLButtonElement>(fixture, '[data-testid="print-policy-save"]').disabled).toBe(true);
    type(fixture, '[data-testid="print-policy-minutes"]', '241');
    expect(el<HTMLButtonElement>(fixture, '[data-testid="print-policy-save"]').disabled).toBe(true);
    type(fixture, '[data-testid="print-policy-minutes"]', '45');
    expect(el<HTMLButtonElement>(fixture, '[data-testid="print-policy-save"]').disabled).toBe(
      false,
    );
    expect(config.setValue).not.toHaveBeenCalled();
  });

  it('sets the document language as a string among the three languages', async () => {
    const config = fakeConfig();
    const fixture = await render(config);

    actions(fixture, 'printing.document_locale')[0].click();
    fixture.detectChanges();
    expect(
      Array.from(el<HTMLSelectElement>(fixture, '[data-testid="print-policy-locale"]').options).map(
        (option) => option.value,
      ),
    ).toEqual(['ru', 'uz-Latn', 'en']);
    pick(fixture, '[data-testid="print-policy-locale"]', 'uz-Latn');
    type(fixture, '[data-testid="print-policy-reason"]', 'staff read Uzbek');
    await click(fixture, '[data-testid="print-policy-save"]');

    expect(config.setValue).toHaveBeenCalledWith(
      't1',
      'printing.document_locale',
      expect.objectContaining({
        scopeType: 'LOCATION',
        locationId: 'l1',
        stringValue: 'uz-Latn',
        explicitNull: false,
      }),
    );
  });

  it('goes back to the inherited value with an explicit "not set here"', async () => {
    const config = fakeConfig({
      'printing.document_locale': resolution('printing.document_locale', 'en', {
        cameFromDefault: false,
        source: 'SCOPED_VALUE',
        winningScope: 'LOCATION',
        currentVersionAtScope: 2,
        inspectedLevels: [{ scopeType: 'LOCATION', outcome: 'VALUE', version: 2 }],
      }),
    });
    const fixture = await render(config);

    actions(fixture, 'printing.document_locale')[1].click();
    fixture.detectChanges();
    await flush();

    expect(config.setValue).toHaveBeenCalledWith(
      't1',
      'printing.document_locale',
      expect.objectContaining({ explicitNull: true, expectedVersion: 2, scopeType: 'LOCATION' }),
    );
  });
});
