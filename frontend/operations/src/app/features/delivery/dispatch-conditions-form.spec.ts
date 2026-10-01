import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { DISPATCH_OPTIONS } from './dispatch-fixtures.testing';
import { DispatchConditionsForm } from './dispatch-conditions-form';
import { DispatchConditions, ScopeLevel } from './dispatch-rules-api';
import { emptyConditions } from './dispatch-rules-model';

interface Rendered {
  readonly fixture: ComponentFixture<DispatchConditionsForm>;
  readonly host: HTMLElement;
  readonly emitted: DispatchConditions[];
}

function render(
  conditions: DispatchConditions = emptyConditions(),
  scopeLevel: ScopeLevel = 'LOCATION',
  withOptions = true,
): Rendered {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(DispatchConditionsForm);
  fixture.componentRef.setInput('conditions', conditions);
  fixture.componentRef.setInput('scopeLevel', scopeLevel);
  fixture.componentRef.setInput('options', withOptions ? DISPATCH_OPTIONS : null);
  const emitted: DispatchConditions[] = [];
  fixture.componentInstance.conditionsChange.subscribe((next) => emitted.push(next));
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement, emitted };
}

function control<T extends HTMLElement>(host: HTMLElement, testId: string): T {
  const found = host.querySelector<T>(`[data-testid="${testId}"]`);
  if (!found) {
    throw new Error(`No control ${testId}`);
  }
  return found;
}

function type(host: HTMLElement, testId: string, value: string, event = 'input'): void {
  const input = control<HTMLInputElement>(host, testId);
  input.value = value;
  input.dispatchEvent(new Event(event));
}

describe('DispatchConditionsForm (ADR 0142: the "when an order…" half of a rule)', () => {
  it('ticking a source emits the conditions with it, and unticking takes it out again', () => {
    const { host, emitted } = render();

    control<HTMLInputElement>(host, 'rule-source-WEB').click();
    expect(emitted.at(-1)?.sources).toEqual(['WEB']);

    const again = render({ ...emptyConditions(), sources: ['WEB', 'TELEGRAM'] });
    control<HTMLInputElement>(again.host, 'rule-source-WEB').click();
    expect(again.emitted.at(-1)?.sources).toEqual(['TELEGRAM']);
  });

  it('offers the tenant’s own zones and channels, by name, and emits the one ticked', () => {
    const { host, emitted } = render();

    expect(host.textContent).toContain('Far zone');
    expect(host.textContent).toContain('Storefront');

    control<HTMLInputElement>(host, 'rule-zone-zone-far').click();
    expect(emitted.at(-1)?.zoneIds).toEqual(['zone-far']);
    control<HTMLInputElement>(host, 'rule-channel-ch-web').click();
    expect(emitted.at(-1)?.channelIds).toEqual(['ch-web']);
  });

  it('says there is nothing to choose from, rather than showing an empty picker, before the options load', () => {
    const { host } = render(emptyConditions(), 'LOCATION', false);

    expect(host.textContent).toContain('Nothing to choose from yet.');
  });

  it('says an order with no zone never matches a rule that names a zone', () => {
    expect(render().host.textContent).toContain('never matches a zone condition');
  });

  it('offers a branch picker above branch level only: at LOCATION scope the branch is implied', () => {
    expect(
      render(emptyConditions(), 'LOCATION').host.querySelector('[data-testid="rule-location-l1"]'),
    ).toBeNull();

    const tenant = render(emptyConditions(), 'TENANT');
    control<HTMLInputElement>(tenant.host, 'rule-location-l1').click();
    expect(tenant.emitted.at(-1)?.locationIds).toEqual(['l1']);
  });

  it('keeps an open end open: typing only a minimum emits a range with no maximum', () => {
    const { host, emitted } = render();

    type(host, 'rule-prep-min', '5');
    expect(emitted.at(-1)?.prepMinutes).toEqual({ min: 5, max: null });

    type(host, 'rule-distance-max', '6000');
    expect(emitted.at(-1)?.distanceMeters).toEqual({ min: null, max: 6000 });
  });

  it('turns two empty boxes back into no condition at all', () => {
    const { host, emitted } = render({
      ...emptyConditions(),
      prepMinutes: { min: 5, max: null },
    });

    type(host, 'rule-prep-min', '');

    expect(emitted.at(-1)?.prepMinutes).toBeNull();
  });

  it('shows what the stored range says', () => {
    const { host } = render({ ...emptyConditions(), prepMinutes: { min: 0, max: 45 } });

    expect(control<HTMLInputElement>(host, 'rule-prep-min').value).toBe('0');
    expect(control<HTMLInputElement>(host, 'rule-prep-max').value).toBe('45');
  });

  describe('the day and hours of confirmation', () => {
    it('starts as any time, and limiting it offers an evening window with no day chosen', () => {
      const { host, emitted } = render();

      expect(host.querySelector('[data-testid="rule-time-from"]')).toBeNull();
      control<HTMLButtonElement>(host, 'rule-time-add').click();

      expect(emitted.at(-1)?.localTime).toEqual({ days: [], from: '18:00', to: '23:00' });
    });

    it('toggles weekdays, and clearing the window returns it to any time', () => {
      const { host, emitted } = render({
        ...emptyConditions(),
        localTime: { days: ['MON'], from: '18:00', to: '23:00' },
      });

      control<HTMLButtonElement>(host, 'rule-day-FRI').click();
      expect(emitted.at(-1)?.localTime?.days).toEqual(['MON', 'FRI']);
      expect(control<HTMLButtonElement>(host, 'rule-day-MON').getAttribute('aria-pressed')).toBe(
        'true',
      );
      expect(control<HTMLButtonElement>(host, 'rule-day-TUE').getAttribute('aria-pressed')).toBe(
        'false',
      );

      control<HTMLButtonElement>(host, 'rule-time-clear').click();
      expect(emitted.at(-1)?.localTime).toBeNull();
    });

    it('stores a window that ends at midnight as 24:00, and shows it as 00:00', () => {
      const { host, emitted } = render({
        ...emptyConditions(),
        localTime: { days: [], from: '20:00', to: '24:00' },
      });

      expect(control<HTMLInputElement>(host, 'rule-time-to').value).toBe('00:00');

      type(host, 'rule-time-to', '00:00', 'change');
      expect(emitted.at(-1)?.localTime?.to).toBe('24:00');
      type(host, 'rule-time-to', '02:00', 'change');
      expect(emitted.at(-1)?.localTime?.to).toBe('02:00');
    });

    it('explains that a window that starts later than it ends crosses midnight', () => {
      const { host } = render({
        ...emptyConditions(),
        localTime: { days: ['FRI'], from: '22:00', to: '02:00' },
      });

      expect(host.textContent).toContain('crosses midnight');
    });
  });

  it('maps the payment box onto true, false and no condition', () => {
    const { host, emitted } = render();

    const select = control<HTMLSelectElement>(host, 'rule-prepaid');
    select.value = 'true';
    select.dispatchEvent(new Event('change'));
    expect(emitted.at(-1)?.prepaid).toBe(true);

    select.value = 'false';
    select.dispatchEvent(new Event('change'));
    expect(emitted.at(-1)?.prepaid).toBe(false);

    select.value = 'any';
    select.dispatchEvent(new Event('change'));
    expect(emitted.at(-1)?.prepaid).toBeNull();
  });
});
