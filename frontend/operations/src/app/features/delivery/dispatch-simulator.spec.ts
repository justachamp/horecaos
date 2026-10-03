import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { DISPATCH_OPTIONS } from './dispatch-fixtures.testing';
import {
  DispatchOptions,
  DispatchRulesApi,
  DispatchRulesDocument,
  RecentPlan,
  ScopeLevel,
  SimulationRequest,
  SimulationResult,
} from './dispatch-rules-api';
import { builtInAction, emptyConditions } from './dispatch-rules-model';
import { DispatchSimulator } from './dispatch-simulator';

const RESULT: SimulationResult = {
  documentSource: 'PUBLISHED',
  policyId: 'policy-1',
  policyVersion: 3,
  facts: {
    sourceSystemType: 'WEB',
    channelId: 'ch-web',
    zoneId: 'zone-far',
    preparationMinutes: 40,
    distanceMeters: 7000,
    confirmedAt: '2026-09-01T13:00:00Z',
    branchTimezone: 'Asia/Tashkent',
    prepaid: true,
  },
  decision: {
    ruleId: 'far-zone',
    mode: 'PARTNER_FIRST',
    partners: { order: ['inst-yandex'], exclude: [], selection: 'LADDER' },
    dispatchAt: { basis: 'LEAD', offsetSeconds: 0 },
    grouping: null,
    skips: [],
  },
  trace: [
    { ruleId: 'evenings', name: 'Evenings', state: 'NOT_MATCHED', failedCondition: 'LOCAL_TIME' },
    { ruleId: 'far-zone', name: 'Far zone', state: 'MATCHED', failedCondition: null },
    { ruleId: 'tail', name: 'Tail', state: 'NOT_EVALUATED', failedCondition: null },
  ],
  lanes: ['PARTNERS', 'FLEET'],
  ladder: [
    {
      position: 1,
      installationId: 'inst-yandex',
      providerType: 'yandex-delivery',
      displayName: 'Yandex Delivery',
    },
  ],
  skips: [{ installationId: 'inst-noor', reason: 'NO_ACTIVE_BINDING' }],
  pickup: {
    confirmedAt: '2026-09-01T13:00:00Z',
    sourceAt: '2026-09-01T13:25:00Z',
    pickupWindowStart: '2026-09-01T13:40:00Z',
    pickupWindowEnd: '2026-09-01T13:55:00Z',
    latestAssignmentAt: '2026-09-01T14:10:00Z',
    calculationVersion: 2,
  },
  notes: ['NO_ZONE_EVIDENCE'],
  violations: [],
  providerCalled: false,
};

const DRAFT: DispatchRulesDocument = {
  rules: [{ id: 'r1', name: 'R1', enabled: true, when: emptyConditions(), then: builtInAction() }],
  default: builtInAction(),
};

interface Rendered {
  readonly fixture: ComponentFixture<DispatchSimulator>;
  readonly host: HTMLElement;
  readonly simulate: ReturnType<typeof vi.fn>;
}

async function render(
  inputs: {
    dirty?: boolean;
    draft?: DispatchRulesDocument | null;
    options?: DispatchOptions;
    scopeLevel?: ScopeLevel;
  } = {},
  simulate = vi.fn().mockResolvedValue(RESULT),
): Promise<Rendered> {
  TestBed.configureTestingModule({
    providers: [{ provide: DispatchRulesApi, useValue: { simulate } }],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(DispatchSimulator);
  fixture.componentRef.setInput('tenantId', 't1');
  fixture.componentRef.setInput('brandId', 'b1');
  fixture.componentRef.setInput('locationId', 'l1');
  fixture.componentRef.setInput('scopeLevel', inputs.scopeLevel ?? 'BRAND');
  fixture.componentRef.setInput('options', inputs.options ?? DISPATCH_OPTIONS);
  fixture.componentRef.setInput('draft', inputs.draft ?? DRAFT);
  fixture.componentRef.setInput('dirty', inputs.dirty ?? false);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement, simulate };
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

function set(host: HTMLElement, testId: string, value: string, event = 'change'): void {
  const element = control<HTMLInputElement | HTMLSelectElement>(host, testId);
  element.value = value;
  element.dispatchEvent(new Event(event));
}

describe('DispatchSimulator (ADR 0142 Decision 4: the simulator is the evaluator)', () => {
  it('asks the server with typed facts, the branch and the scope, and sends no draft when nothing is unsaved', async () => {
    const { fixture, host, simulate } = await render();

    set(host, 'sim-source', 'TELEGRAM');
    set(host, 'sim-zone', 'zone-far');
    set(host, 'sim-channel', 'ch-web');
    set(host, 'sim-prep', '40', 'input');
    set(host, 'sim-distance', '7000', 'input');
    set(host, 'sim-confirmed-at', '2026-09-01T18:00');
    fixture.detectChanges();
    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    expect(simulate).toHaveBeenCalledTimes(1);
    const [tenantId, request] = simulate.mock.calls[0] as [string, SimulationRequest];
    expect(tenantId).toBe('t1');
    expect(request.brandId).toBe('b1');
    expect(request.locationId).toBe('l1');
    expect(request.scopeType).toBe('BRAND');
    expect(request.draft).toBeUndefined();
    expect(request.planId).toBeUndefined();
    expect(request.scenario).toMatchObject({
      sourceSystemType: 'TELEGRAM',
      zoneId: 'zone-far',
      channelId: 'ch-web',
      preparationMinutes: 40,
      distanceMeters: 7000,
      prepaid: true,
    });
    // 18:00 on the branch's clock (Tashkent, UTC+5) is 13:00 UTC, whatever zone this machine is in.
    expect(request.scenario?.confirmedAt).toBe('2026-09-01T13:00:00.000Z');
  });

  it('shows the rule that matched, the lanes in order, the partners and the start', async () => {
    const { fixture, host } = await render();

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    expect(control(host, 'sim-matched').textContent).toContain('far-zone');
    expect(control(host, 'sim-mode').textContent).toContain('Partners first');
    expect(control(host, 'sim-lanes').textContent?.replace(/\s+/g, ' ')).toContain(
      'partners → own couriers',
    );
    expect(control(host, 'sim-ladder').textContent).toContain('Yandex Delivery');
    // 13:25 UTC is 18:25 in Tashkent.
    expect(control(host, 'sim-source-at').textContent).toContain('18:25');
    expect(control(host, 'sim-window').textContent).toContain('18:40');
  });

  it('says why the earlier rules did not match, naming the first condition that failed', async () => {
    const { fixture, host } = await render();

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    const trace = control(host, 'sim-trace').textContent?.replace(/\s+/g, ' ') ?? '';
    expect(trace).toContain('Evenings');
    expect(trace).toContain('not matched — outside the day and hours');
    expect(trace).toContain('matched');
    expect(trace).toContain('not reached');
  });

  it('names a partner the rule asked for that could not be used, and why', async () => {
    const { fixture, host } = await render();

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    expect(control(host, 'sim-skips').textContent).toContain('Noor');
    expect(control(host, 'sim-skips').textContent).toContain('is not connected to this branch');
  });

  it('says every time that no partner was called and no price was asked', async () => {
    const { fixture, host } = await render();

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    expect(control(host, 'sim-no-provider').textContent).toContain('No partner was called');
  });

  it('shows the note the engine raised, in words', async () => {
    const { fixture, host } = await render();

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    expect(control(host, 'sim-note-NO_ZONE_EVIDENCE').textContent).toContain('no zone');
  });

  it('reports the default when no rule matched', async () => {
    const simulate = vi.fn().mockResolvedValue({
      ...RESULT,
      decision: { ...RESULT.decision, ruleId: 'DEFAULT' },
      ladder: [],
      skips: [],
    });
    const { fixture, host } = await render({}, simulate);

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    expect(control(host, 'sim-matched').textContent).toContain('Matches no rule');
    expect(control(host, 'sim-ladder-empty').textContent).toContain('No partner is available');
  });

  it('lists what would stop a draft being published', async () => {
    const simulate = vi.fn().mockResolvedValue({
      ...RESULT,
      documentSource: 'DRAFT',
      violations: [
        'Rule "far-zone" can never match: "everything" above it already takes every order it would',
      ],
    });
    const { fixture, host } = await render({ dirty: true }, simulate);

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    expect(control(host, 'sim-violations').textContent).toContain('can never match');
  });

  describe('the unsaved draft', () => {
    it('is tested when there are unsaved changes, with the scope it is being written for', async () => {
      const { fixture, host, simulate } = await render({ dirty: true });

      control<HTMLButtonElement>(host, 'sim-run').click();
      await settle(fixture);

      const [, request] = simulate.mock.calls[0] as [string, SimulationRequest];
      expect(request.draft).toEqual(DRAFT);
      expect(request.scopeType).toBe('BRAND');
    });

    it('is left out when the operator unticks it, and the published rules are tested', async () => {
      const { fixture, host, simulate } = await render({ dirty: true });

      control<HTMLInputElement>(host, 'sim-use-draft').click();
      fixture.detectChanges();
      control<HTMLButtonElement>(host, 'sim-run').click();
      await settle(fixture);

      const [, request] = simulate.mock.calls[0] as [string, SimulationRequest];
      expect(request.draft).toBeUndefined();
    });

    it('offers no choice when there is nothing unsaved', async () => {
      const { host } = await render({ dirty: false });

      expect(host.querySelector('[data-testid="sim-use-draft"]')).toBeNull();
    });
  });

  describe('a recent order', () => {
    it('re-reads the facts of a plan instead of typing them, and cannot run until one is chosen', async () => {
      const { fixture, host, simulate } = await render();

      control<HTMLButtonElement>(host, 'sim-mode-plan').click();
      fixture.detectChanges();
      expect(control<HTMLButtonElement>(host, 'sim-run').disabled).toBe(true);
      expect(host.querySelector('[data-testid="sim-source"]')).toBeNull();

      set(host, 'sim-plan', 'plan-1');
      fixture.detectChanges();
      control<HTMLButtonElement>(host, 'sim-run').click();
      await settle(fixture);

      const [, request] = simulate.mock.calls[0] as [string, SimulationRequest];
      expect(request.planId).toBe('plan-1');
      expect(request.scenario).toBeUndefined();
    });
  });

  describe('a recent order of another branch', () => {
    function plan(planId: string, locationId: string, orderReference: string): RecentPlan {
      return {
        planId,
        locationId,
        orderReference,
        status: 'ASSIGNED',
        sourcingMode: 'FLEET_FIRST',
        ruleId: null,
        createdAt: '2026-09-30T10:00:00Z',
      };
    }

    const OPTIONS: DispatchOptions = {
      ...DISPATCH_OPTIONS,
      locations: [
        { id: 'l1', brandId: 'b1', displayName: 'Centre' },
        { id: 'l2', brandId: 'b1', displayName: 'Chilonzor' },
        { id: 'l3', brandId: 'b2', displayName: 'Other brand' },
      ],
      recentPlans: [
        plan('plan-own', 'l1', 'D-1'),
        plan('plan-sibling', 'l2', 'D-204'),
        plan('plan-other-brand', 'l3', 'D-300'),
        plan('plan-unknown', 'l9', 'D-400'),
      ],
    };

    function offered(host: HTMLElement): string[] {
      return [...control<HTMLSelectElement>(host, 'sim-plan').options]
        .map((option) => option.value)
        .filter((value) => value !== '');
    }

    it('re-reads it at its own branch, because the server only finds a plan at the branch it belongs to', async () => {
      const { fixture, host, simulate } = await render({ options: OPTIONS });
      control<HTMLButtonElement>(host, 'sim-mode-plan').click();
      fixture.detectChanges();

      set(host, 'sim-plan', 'plan-sibling');
      fixture.detectChanges();
      control<HTMLButtonElement>(host, 'sim-run').click();
      await settle(fixture);

      const [, request] = simulate.mock.calls[0] as [string, SimulationRequest];
      expect(request.planId).toBe('plan-sibling');
      expect(request.locationId).toBe('l2');
      expect(request.brandId).toBe('b1');
    });

    it('still uses the operator’s own branch for a plan of that branch', async () => {
      const { fixture, host, simulate } = await render({ options: OPTIONS });
      control<HTMLButtonElement>(host, 'sim-mode-plan').click();
      fixture.detectChanges();

      set(host, 'sim-plan', 'plan-own');
      fixture.detectChanges();
      control<HTMLButtonElement>(host, 'sim-run').click();
      await settle(fixture);

      const [, request] = simulate.mock.calls[0] as [string, SimulationRequest];
      expect(request.locationId).toBe('l1');
      expect(request.brandId).toBe('b1');
    });

    it('names the branch of a plan that is not the operator’s own, and offers none it cannot place', async () => {
      const { fixture, host } = await render({ options: OPTIONS });
      control<HTMLButtonElement>(host, 'sim-mode-plan').click();
      fixture.detectChanges();

      expect(offered(host)).toEqual(['plan-own', 'plan-sibling']);
      const labels = [...control<HTMLSelectElement>(host, 'sim-plan').options].map(
        (option) => option.textContent ?? '',
      );
      expect(labels.find((label) => label.includes('D-204'))).toContain('Chilonzor');
      expect(labels.find((label) => label.includes('D-1'))).not.toContain('Centre');
    });

    it('cannot run a plan that the scope now shown no longer offers', async () => {
      const { fixture, host } = await render({ options: OPTIONS, scopeLevel: 'TENANT' });
      control<HTMLButtonElement>(host, 'sim-mode-plan').click();
      fixture.detectChanges();
      set(host, 'sim-plan', 'plan-sibling');
      fixture.detectChanges();
      expect(control<HTMLButtonElement>(host, 'sim-run').disabled).toBe(false);

      fixture.componentRef.setInput('scopeLevel', 'LOCATION');
      fixture.detectChanges();

      expect(control<HTMLButtonElement>(host, 'sim-run').disabled).toBe(true);
    });

    it('offers only the branch’s own plans when the rules being written are the branch’s own', async () => {
      const { fixture, host } = await render({ options: OPTIONS, scopeLevel: 'LOCATION' });
      control<HTMLButtonElement>(host, 'sim-mode-plan').click();
      fixture.detectChanges();

      expect(offered(host)).toEqual(['plan-own']);
    });

    it('offers every branch of the company when the rules are the company’s', async () => {
      const { fixture, host } = await render({ options: OPTIONS, scopeLevel: 'TENANT' });
      control<HTMLButtonElement>(host, 'sim-mode-plan').click();
      fixture.detectChanges();

      expect(offered(host)).toEqual(['plan-own', 'plan-sibling', 'plan-other-brand']);
    });
  });

  it('shows the server’s refusal in words and clears the earlier result', async () => {
    const simulate = vi
      .fn()
      .mockResolvedValueOnce(RESULT)
      .mockRejectedValueOnce(
        new ApiError(
          'VALIDATION_FAILED',
          400,
          { status: 400, detail: 'preparationMinutes must be between 0 and 1440' },
          null,
        ),
      );
    const { fixture, host } = await render({}, simulate);

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);
    expect(host.querySelector('[data-testid="sim-result"]')).not.toBeNull();

    control<HTMLButtonElement>(host, 'sim-run').click();
    await settle(fixture);

    expect(control(host, 'sim-error').textContent).toContain('preparationMinutes must be between');
    expect(host.querySelector('[data-testid="sim-result"]')).toBeNull();
  });
});
