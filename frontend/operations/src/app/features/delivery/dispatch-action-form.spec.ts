import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { I18n } from '../../core/i18n/i18n';
import { DispatchActionForm } from './dispatch-action-form';
import { DISPATCH_OPTIONS } from './dispatch-fixtures.testing';
import { DispatchAction } from './dispatch-rules-api';
import { builtInAction } from './dispatch-rules-model';

interface Rendered {
  readonly fixture: ComponentFixture<DispatchActionForm>;
  readonly host: HTMLElement;
  readonly emitted: DispatchAction[];
}

function render(action: DispatchAction = builtInAction(), groupingAllowed = false): Rendered {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({});
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(DispatchActionForm);
  fixture.componentRef.setInput('action', action);
  fixture.componentRef.setInput('options', DISPATCH_OPTIONS);
  fixture.componentRef.setInput('groupingAllowed', groupingAllowed);
  fixture.componentRef.setInput('idPrefix', 'a');
  const emitted: DispatchAction[] = [];
  fixture.componentInstance.actionChange.subscribe((next) => emitted.push(next));
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement, emitted };
}

function find<T extends HTMLElement>(host: HTMLElement, testId: string): T | null {
  return host.querySelector<T>(`[data-testid="${testId}"]`);
}

function control<T extends HTMLElement>(host: HTMLElement, testId: string): T {
  const found = find<T>(host, testId);
  if (!found) {
    throw new Error(`No control ${testId}`);
  }
  return found;
}

function choose(host: HTMLElement, testId: string, value: string): void {
  const select = control<HTMLSelectElement>(host, testId);
  select.value = value;
  select.dispatchEvent(new Event('change'));
}

function withPartners(order: string[], exclude: string[] = []): DispatchAction {
  return {
    ...builtInAction(),
    mode: 'PARTNER_FIRST',
    partners: { order, exclude, selection: 'LADDER' },
  };
}

describe('DispatchActionForm (ADR 0142: the "then" half of a rule)', () => {
  describe('how the order is sent', () => {
    it('offers all five ways, partners-first among them', () => {
      const { host } = render();

      const options = [...control<HTMLSelectElement>(host, 'a-mode').options].map((o) => o.value);
      expect(options).toEqual(
        expect.arrayContaining([
          'FLEET_FIRST',
          'PARTNER_FIRST',
          'FLEET_ONLY',
          'PARTNER_ONLY',
          'MANUAL',
        ]),
      );
      expect(options).toHaveLength(5);
      expect(host.textContent).toContain('Partners first, own couriers if they all refuse');
    });

    it('drops the partner lists when the new mode never asks a partner, because the server refuses the pair', () => {
      const { host, emitted } = render(withPartners(['inst-yandex'], ['inst-noor']));

      choose(host, 'a-mode', 'FLEET_ONLY');

      expect(emitted.at(-1)?.mode).toBe('FLEET_ONLY');
      expect(emitted.at(-1)?.partners).toEqual({ order: [], exclude: [], selection: 'LADDER' });
    });

    it('keeps the partner lists when the mode still asks partners', () => {
      const { host, emitted } = render(withPartners(['inst-yandex']));

      choose(host, 'a-mode', 'PARTNER_ONLY');

      expect(emitted.at(-1)?.partners.order).toEqual(['inst-yandex']);
    });

    it('hides the partner block and the start for a mode that never asks a partner or is dispatched by hand', () => {
      const fleetOnly = render({ ...builtInAction(), mode: 'FLEET_ONLY' });
      expect(find(fleetOnly.host, 'a-selection')).toBeNull();
      expect(find(fleetOnly.host, 'a-basis')).not.toBeNull();

      const manual = render({ ...builtInAction(), mode: 'MANUAL' });
      expect(find(manual.host, 'a-basis')).toBeNull();
      expect(find(manual.host, 'a-selection')).toBeNull();
    });
  });

  describe('the partner list', () => {
    it('adds a partner at the end, naming it, and offers only those not already chosen', () => {
      const { host, emitted } = render(withPartners(['inst-yandex']));

      const add = control<HTMLSelectElement>(host, 'a-partner-add');
      expect([...add.options].map((o) => o.value)).toEqual(['', 'inst-noor']);

      choose(host, 'a-partner-add', 'inst-noor');

      expect(emitted.at(-1)?.partners.order).toEqual(['inst-yandex', 'inst-noor']);
      expect(host.textContent).toContain('Yandex Delivery');
    });

    it('moves a partner up and down, and refuses to move one off either end', () => {
      const { host, emitted } = render(withPartners(['inst-yandex', 'inst-noor']));

      expect(control<HTMLButtonElement>(host, 'a-partner-up-inst-yandex').disabled).toBe(true);
      expect(control<HTMLButtonElement>(host, 'a-partner-down-inst-noor').disabled).toBe(true);

      control<HTMLButtonElement>(host, 'a-partner-up-inst-noor').click();
      expect(emitted.at(-1)?.partners.order).toEqual(['inst-noor', 'inst-yandex']);

      control<HTMLButtonElement>(host, 'a-partner-down-inst-yandex').click();
      expect(emitted.at(-1)?.partners.order).toEqual(['inst-noor', 'inst-yandex']);
    });

    it('removes a partner from the list', () => {
      const { host, emitted } = render(withPartners(['inst-yandex', 'inst-noor']));

      control<HTMLButtonElement>(host, 'a-partner-remove-inst-yandex').click();

      expect(emitted.at(-1)?.partners.order).toEqual(['inst-noor']);
    });

    it('excludes a partner, and does not let one be both preferred and excluded', () => {
      const { host, emitted } = render(withPartners(['inst-yandex']));

      expect(control<HTMLInputElement>(host, 'a-exclude-inst-yandex').disabled).toBe(true);
      control<HTMLInputElement>(host, 'a-exclude-inst-noor').click();
      expect(emitted.at(-1)?.partners.exclude).toEqual(['inst-noor']);
    });

    it('shows an id for a listed partner that is no longer among the tenant’s installations, rather than hiding it', () => {
      const { host } = render(withPartners(['inst-retired']));

      expect(host.textContent).toContain('inst-retired');
    });

    it('chooses between a ladder that asks no price and a quote race', () => {
      const { host, emitted } = render(withPartners(['inst-yandex']));

      choose(host, 'a-selection', 'CHEAPEST');

      expect(emitted.at(-1)?.partners.selection).toBe('CHEAPEST');
      expect(host.textContent).toContain('Ask each for a price and book the cheapest');
      expect(host.textContent).not.toContain('cancel the losers');
    });
  });

  describe('when the search for a courier starts', () => {
    it('changes the basis and keeps the offset', () => {
      const { host, emitted } = render({
        ...builtInAction(),
        dispatchAt: { basis: 'LEAD', offsetSeconds: 300 },
      });

      choose(host, 'a-basis', 'READY');

      expect(emitted.at(-1)?.dispatchAt).toEqual({ basis: 'READY', offsetSeconds: 300 });
    });

    it('shows the offset in minutes and stores it in seconds', () => {
      const { host, emitted } = render({
        ...builtInAction(),
        dispatchAt: { basis: 'LEAD', offsetSeconds: -600 },
      });

      const offset = control<HTMLInputElement>(host, 'a-offset');
      expect(offset.value).toBe('-10');

      offset.value = '15';
      offset.dispatchEvent(new Event('input'));
      expect(emitted.at(-1)?.dispatchAt.offsetSeconds).toBe(900);

      offset.value = '';
      offset.dispatchEvent(new Event('input'));
      expect(emitted.at(-1)?.dispatchAt.offsetSeconds).toBe(0);
    });
  });

  describe('grouping', () => {
    it('is shown locked, with the reason, until the pay treatment for a run is decided', () => {
      const { host } = render();

      expect(find(host, 'a-grouping-locked')?.textContent).toContain('is still to be decided');
      expect(find(host, 'a-grouping')).toBeNull();
    });

    it('can be switched on once allowed, starting from a sensible run, and off again', () => {
      const { host, emitted } = render(builtInAction(), true);

      control<HTMLInputElement>(host, 'a-grouping').click();
      expect(emitted.at(-1)?.grouping).toEqual({
        mergeRadiusMeters: 700,
        maxOrdersPerRun: 3,
        maxWaitSeconds: 120,
      });
      expect(find(host, 'a-grouping-locked')).toBeNull();
    });

    it('is cleared when the mode no longer asks the fleet, because the server refuses grouping without one', () => {
      const { host, emitted } = render(
        {
          ...builtInAction(),
          grouping: { mergeRadiusMeters: 500, maxOrdersPerRun: 2, maxWaitSeconds: 60 },
        },
        true,
      );

      choose(host, 'a-mode', 'PARTNER_ONLY');

      expect(emitted.at(-1)?.grouping).toBeNull();
    });

    it('is not offered at all under a mode that never asks the fleet', () => {
      const { host } = render({ ...builtInAction(), mode: 'PARTNER_ONLY' }, true);

      expect(find(host, 'a-grouping')).toBeNull();
    });
  });
});
