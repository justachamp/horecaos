import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { DispatchRulesApi, DispatchScope, SourcingTimingsView } from './dispatch-rules-api';
import { SourcingTimingsCard } from './sourcing-timings-card';

const SCOPE: DispatchScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const DEFAULTS: SourcingTimingsView = {
  preparationLeadSeconds: 600,
  partnerLeadSeconds: 900,
  safetyBufferSeconds: 300,
  pickupToleranceSeconds: 900,
  offerRounds: 2,
  maxOfferSeconds: 90,
  latestAssignmentSlackSeconds: 900,
  isDefaults: true,
  winningScope: null,
  policyId: null,
  policyVersion: 0,
  versionAtScope: 0,
  levels: [],
};

interface Rendered {
  readonly fixture: ComponentFixture<SourcingTimingsCard>;
  readonly host: HTMLElement;
  readonly api: { timings: ReturnType<typeof vi.fn>; publishTimings: ReturnType<typeof vi.fn> };
}

async function render(
  view: SourcingTimingsView = DEFAULTS,
  canWrite = true,
  publish = vi
    .fn()
    .mockResolvedValue({ ...DEFAULTS, isDefaults: false, policyVersion: 1, versionAtScope: 1 }),
): Promise<Rendered> {
  const api = {
    timings: vi.fn().mockResolvedValue({ value: view, version: view.versionAtScope }),
    publishTimings: publish,
  };
  TestBed.configureTestingModule({ providers: [{ provide: DispatchRulesApi, useValue: api }] });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(SourcingTimingsCard);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('canWrite', canWrite);
  fixture.detectChanges();
  await settle(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, api };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  await fixture.whenStable();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
}

function control<T extends HTMLElement>(host: HTMLElement, testId: string): T {
  const found = host.querySelector<T>(`[data-testid="${testId}"]`);
  if (!found) {
    throw new Error(`No control ${testId}`);
  }
  return found;
}

function type(host: HTMLElement, testId: string, value: string, event: string): void {
  const input = control<HTMLInputElement>(host, testId);
  input.value = value;
  input.dispatchEvent(new Event(event));
}

describe('SourcingTimingsCard (ADR 0142: the writer fulfillment.sourcing never had)', () => {
  it('shows the provisional defaults in minutes, and says they are defaults', async () => {
    const { host } = await render();

    expect(control(host, 'timings-defaults').textContent).toContain('provisional defaults');
    expect(control<HTMLInputElement>(host, 'timings-preparationLeadSeconds').value).toBe('10');
    expect(control<HTMLInputElement>(host, 'timings-partnerLeadSeconds').value).toBe('15');
    expect(control<HTMLInputElement>(host, 'timings-offerRounds').value).toBe('2');
    expect(control<HTMLInputElement>(host, 'timings-maxOfferSeconds').value).toBe('90');
  });

  it('cannot publish until something changed and a reason is given', async () => {
    const { fixture, host } = await render();
    const publish = (): HTMLButtonElement => control<HTMLButtonElement>(host, 'timings-publish');

    expect(publish().disabled).toBe(true);

    type(host, 'timings-preparationLeadSeconds', '20', 'change');
    fixture.detectChanges();
    expect(publish().disabled).toBe(true);

    type(host, 'timings-reason', 'Longer lead at the flagship', 'input');
    fixture.detectChanges();
    expect(publish().disabled).toBe(false);
  });

  it('publishes the whole document in seconds, with the version the read returned', async () => {
    const view = { ...DEFAULTS, isDefaults: false, versionAtScope: 4, policyVersion: 4 };
    const { fixture, host, api } = await render(view);

    type(host, 'timings-preparationLeadSeconds', '20', 'change');
    type(host, 'timings-reason', 'Longer lead', 'input');
    fixture.detectChanges();
    control<HTMLButtonElement>(host, 'timings-publish').click();
    await settle(fixture);

    expect(api.publishTimings).toHaveBeenCalledWith(
      SCOPE,
      {
        preparationLeadSeconds: 1200,
        partnerLeadSeconds: 900,
        safetyBufferSeconds: 300,
        pickupToleranceSeconds: 900,
        offerRounds: 2,
        maxOfferSeconds: 90,
        latestAssignmentSlackSeconds: 900,
      },
      'Longer lead',
      4,
    );
    expect(control(host, 'timings-notice').textContent).toContain('published as version 1');
  });

  it('never rounds a number the operator did not touch: 90 seconds of buffer goes back as 90', async () => {
    const view = { ...DEFAULTS, safetyBufferSeconds: 90 };
    const { fixture, host, api } = await render(view);

    expect(control<HTMLInputElement>(host, 'timings-safetyBufferSeconds').value).toBe('1.5');
    type(host, 'timings-offerRounds', '3', 'change');
    type(host, 'timings-reason', 'More rounds', 'input');
    fixture.detectChanges();
    control<HTMLButtonElement>(host, 'timings-publish').click();
    await settle(fixture);

    expect(api.publishTimings.mock.calls[0][1]).toMatchObject({
      safetyBufferSeconds: 90,
      offerRounds: 3,
    });
  });

  it('says so when someone else published first, and does not pretend it saved', async () => {
    const stale = new ApiError('STALE_VERSION', 409, { status: 409, expected: 0, actual: 1 }, null);
    const { fixture, host } = await render(DEFAULTS, true, vi.fn().mockRejectedValue(stale));

    type(host, 'timings-offerRounds', '3', 'change');
    type(host, 'timings-reason', 'More rounds', 'input');
    fixture.detectChanges();
    control<HTMLButtonElement>(host, 'timings-publish').click();
    await settle(fixture);

    expect(control(host, 'timings-error').textContent).toBeTruthy();
    expect(host.querySelector('[data-testid="timings-notice"]')).toBeNull();
  });

  it('shows the numbers and no form to change them to someone who may only read', async () => {
    const { host } = await render(DEFAULTS, false);

    expect(control<HTMLInputElement>(host, 'timings-offerRounds').disabled).toBe(true);
    expect(host.querySelector('[data-testid="timings-publish"]')).toBeNull();
    expect(host.querySelector('[data-testid="timings-reason"]')).toBeNull();
  });

  it('reloads when the scope it is shown for changes', async () => {
    const { fixture, api } = await render();

    fixture.componentRef.setInput('scope', { tenantId: 't1', brandId: 'b1' });
    fixture.detectChanges();
    await settle(fixture);

    expect(api.timings).toHaveBeenCalledTimes(2);
    expect(api.timings).toHaveBeenLastCalledWith({ tenantId: 't1', brandId: 'b1' });
  });
});
