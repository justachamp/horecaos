import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { DISPATCH_OPTIONS } from './dispatch-fixtures.testing';
import {
  DispatchRule,
  DispatchRulesApi,
  DispatchRulesView,
  DispatchUsage,
  PaymentWindowView,
  SourcingTimingsView,
} from './dispatch-rules-api';
import { builtInAction, emptyConditions } from './dispatch-rules-model';
import { DispatchRulesPage } from './dispatch-rules-page';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

const BUILT_IN: DispatchRulesView = {
  schema: 1,
  rules: [],
  default: builtInAction(),
  isBuiltIn: true,
  winningScope: null,
  policyId: null,
  policyVersion: 0,
  versionAtScope: 0,
  levels: [],
  groupingAllowed: false,
};

function rule(id: string, name: string, patch: Partial<DispatchRule> = {}): DispatchRule {
  return { id, name, enabled: true, when: emptyConditions(), then: builtInAction(), ...patch };
}

const PUBLISHED: DispatchRulesView = {
  ...BUILT_IN,
  rules: [
    rule('far-zone', 'Far zone: Yandex first', {
      then: {
        ...builtInAction(),
        mode: 'PARTNER_FIRST',
        partners: { order: ['inst-yandex'], exclude: [], selection: 'LADDER' },
      },
    }),
    rule('evenings', 'Evenings'),
  ],
  isBuiltIn: false,
  winningScope: 'LOCATION',
  policyId: 'policy-1',
  policyVersion: 3,
  versionAtScope: 3,
};

const USAGE: DispatchUsage = {
  days: 30,
  since: '2026-09-01T00:00:00Z',
  totalPlans: 14,
  perRule: [
    { ruleId: 'far-zone', plans: 12 },
    { ruleId: 'DEFAULT', plans: 2 },
  ],
};

const TIMINGS: SourcingTimingsView = {
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

const WINDOW: PaymentWindowView = {
  windowMinutes: 30,
  action: 'FLAG_ONLY',
  isDefault: true,
  winningScope: null,
  policyId: null,
  policyVersion: 0,
  versionAtScope: 0,
  levels: [],
};

type Api = Record<string, ReturnType<typeof vi.fn>>;

function apiWith(view: DispatchRulesView, overrides: Partial<Api> = {}): Api {
  return {
    rules: vi.fn().mockResolvedValue({ value: view, version: view.versionAtScope }),
    options: vi.fn().mockResolvedValue(DISPATCH_OPTIONS),
    usage: vi.fn().mockResolvedValue(USAGE),
    publishRules: vi.fn().mockResolvedValue({
      ...view,
      versionAtScope: view.versionAtScope + 1,
      policyVersion: view.policyVersion + 1,
    }),
    simulate: vi.fn(),
    timings: vi.fn().mockResolvedValue({ value: TIMINGS, version: 0 }),
    publishTimings: vi.fn(),
    paymentWindow: vi.fn().mockResolvedValue({ value: WINDOW, version: 0 }),
    publishPaymentWindow: vi.fn(),
    ...overrides,
  };
}

interface Rendered {
  readonly fixture: ComponentFixture<DispatchRulesPage>;
  readonly host: HTMLElement;
  readonly api: Api;
}

async function render(
  api: Api,
  options: { scope?: LocationScope | null; denied?: boolean; held?: readonly Capability[] } = {},
): Promise<Rendered> {
  const held = options.held ?? [
    'DELIVERY_DISPATCH_RULES_READ',
    'DELIVERY_DISPATCH_RULES_WRITE',
    'ORDER_PAYMENT_WINDOW_MANAGE',
  ];
  TestBed.configureTestingModule({
    providers: [
      { provide: DispatchRulesApi, useValue: api },
      {
        provide: CurrentLocation,
        useValue: {
          scope: signal<LocationScope | null>(options.scope === undefined ? SCOPE : options.scope),
          denied: signal(options.denied ?? false),
          ensureLoaded: () => Promise.resolve(),
        },
      },
      { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.includes(c) } },
    ],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(DispatchRulesPage);
  fixture.detectChanges();
  await settle(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, api };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  await fixture.whenStable();
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  fixture.detectChanges();
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

function type(host: HTMLElement, testId: string, value: string): void {
  const input = control<HTMLInputElement>(host, testId);
  input.value = value;
  input.dispatchEvent(new Event('input'));
}

function ruleRows(host: HTMLElement): HTMLElement[] {
  return [...host.querySelectorAll<HTMLElement>('.rule-list__row')];
}

describe('DispatchRulesPage (IA 3.8, ADR 0142)', () => {
  describe('what is in force', () => {
    it('says so when nothing is published, and shows today’s behaviour as the default to edit', async () => {
      const { host } = await render(apiWith(BUILT_IN));

      expect(control(host, 'rules-built-in').textContent).toContain('Nothing is published here');
      expect(find(host, 'rules-empty')?.textContent).toContain('No rules yet');
      expect(
        host.querySelector('[data-testid="rules-default"] q-dispatch-action-form'),
      ).not.toBeNull();
      expect(control<HTMLSelectElement>(host, 'default-mode').value).toBe('FLEET_FIRST');
    });

    it('lists the published rules in order, each with its way of sending and how many orders it took', async () => {
      const { host } = await render(apiWith(PUBLISHED));

      const rows = ruleRows(host);
      expect(rows).toHaveLength(2);
      expect(rows[0].textContent).toContain('Far zone: Yandex first');
      expect(rows[0].textContent).toContain('Partners first, own couriers if they all refuse');
      expect(rows[0].textContent).toContain('12 orders in 30 days');
      expect(rows[1].textContent).toContain('no orders in 30 days');
      expect(control(host, 'rules-default-usage').textContent).toContain('2 orders in 30 days');
      expect(control(host, 'rules-scope').textContent).toContain('This branch');
    });

    it('says a level with no rules of its own inherits, and that publishing replaces rather than merges', async () => {
      const inherited = { ...PUBLISHED, winningScope: 'TENANT' as const, versionAtScope: 0 };
      const { host } = await render(apiWith(inherited));

      expect(control(host, 'rules-inherited').textContent).toContain('never merges');
    });
  });

  describe('the level being edited', () => {
    it('re-reads at the brand and at the company when the selector moves, never sending a branch it is not for', async () => {
      const { fixture, host, api } = await render(apiWith(PUBLISHED));
      expect(api['rules']).toHaveBeenLastCalledWith({
        tenantId: 't1',
        brandId: 'b1',
        locationId: 'l1',
      });

      control<HTMLButtonElement>(host, 'rules-scope-BRAND').click();
      await settle(fixture);
      expect(api['rules']).toHaveBeenLastCalledWith({ tenantId: 't1', brandId: 'b1' });

      control<HTMLButtonElement>(host, 'rules-scope-TENANT').click();
      await settle(fixture);
      expect(api['rules']).toHaveBeenLastCalledWith({ tenantId: 't1' });
      expect(api['usage']).toHaveBeenLastCalledWith({ tenantId: 't1' }, 30);
    });
  });

  describe('editing a draft', () => {
    it('adds a rule that matches everything and does what the default does, selected for editing, unpublished until asked', async () => {
      const { fixture, host } = await render(apiWith(BUILT_IN));

      control<HTMLButtonElement>(host, 'rules-add').click();
      fixture.detectChanges();

      expect(ruleRows(host)).toHaveLength(1);
      expect(control<HTMLInputElement>(host, 'rule-id').value).toBe('rule-1');
      expect(control<HTMLInputElement>(host, 'rule-id').disabled).toBe(false);
      expect(control(host, 'rules-dirty').textContent).toContain('not published');
      expect(control<HTMLButtonElement>(host, 'rules-publish').disabled).toBe(true);
      expect(control(host, 'rules-reason-required').textContent).toContain('Say why');
    });

    it('lets a new rule’s identifier be typed, and refuses a malformed one before it is sent', async () => {
      const { fixture, host } = await render(apiWith(BUILT_IN));
      control<HTMLButtonElement>(host, 'rules-add').click();
      fixture.detectChanges();
      type(host, 'rules-reason', 'Add one');
      fixture.detectChanges();
      expect(control<HTMLButtonElement>(host, 'rules-publish').disabled).toBe(false);

      type(host, 'rule-id', 'Far Zone!');
      fixture.detectChanges();

      expect(control<HTMLButtonElement>(host, 'rules-publish').disabled).toBe(true);
      expect(control<HTMLInputElement>(host, 'rule-id').getAttribute('aria-invalid')).toBe('true');
    });

    it('fixes a published rule’s identifier, because plans have recorded it', async () => {
      const { fixture, host } = await render(apiWith(PUBLISHED));

      ruleRows(fixture.nativeElement as HTMLElement)[0]
        .querySelector<HTMLElement>('.rule-list__body')!
        .click();
      fixture.detectChanges();

      expect(control<HTMLInputElement>(host, 'rule-id').disabled).toBe(true);
      expect(control<HTMLInputElement>(host, 'rule-name').value).toBe('Far zone: Yandex first');
    });

    it('renames the selected rule in the list as it is typed', async () => {
      const { fixture, host } = await render(apiWith(PUBLISHED));
      host.querySelector<HTMLElement>('.rule-list__body')!.click();
      fixture.detectChanges();

      type(host, 'rule-name', 'Yandex for the far zone');
      fixture.detectChanges();

      expect(ruleRows(host)[0].textContent).toContain('Yandex for the far zone');
    });

    it('switches a rule off and on from the list', async () => {
      const { fixture, host } = await render(apiWith(PUBLISHED));

      const toggle = ruleRows(host)[1].querySelector<HTMLInputElement>('input[type="checkbox"]')!;
      toggle.checked = false;
      toggle.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      expect(control(host, 'rules-dirty')).toBeTruthy();
      expect(ruleRows(host)[1].textContent).toContain('Disabled');
      expect(ruleRows(host)[0].textContent).not.toContain('Disabled');
    });

    it('deletes the selected rule', async () => {
      const { fixture, host } = await render(apiWith(PUBLISHED));
      host.querySelector<HTMLElement>('.rule-list__body')!.click();
      fixture.detectChanges();

      control<HTMLButtonElement>(host, 'rule-delete').click();
      fixture.detectChanges();

      expect(ruleRows(host)).toHaveLength(1);
      expect(ruleRows(host)[0].textContent).toContain('Evenings');
      expect(find(host, 'rules-edit-none')).not.toBeNull();
    });

    it('is not dirty again when an edit is put back, and discards everything on request', async () => {
      const { fixture, host } = await render(apiWith(PUBLISHED));
      control<HTMLButtonElement>(host, 'rules-add').click();
      fixture.detectChanges();
      expect(find(host, 'rules-dirty')).not.toBeNull();

      control<HTMLButtonElement>(host, 'rules-discard').click();
      fixture.detectChanges();

      expect(ruleRows(host)).toHaveLength(2);
      expect(find(host, 'rules-dirty')).toBeNull();
    });
  });

  describe('publishing', () => {
    it('sends the whole document with the reason and the version the read returned, and shows the new version', async () => {
      const api = apiWith(PUBLISHED);
      const { fixture, host } = await render(api);

      control<HTMLButtonElement>(host, 'rules-add').click();
      fixture.detectChanges();
      type(host, 'rules-reason', 'Add a catch-all');
      fixture.detectChanges();
      control<HTMLButtonElement>(host, 'rules-publish').click();
      await settle(fixture);

      expect(api['publishRules']).toHaveBeenCalledTimes(1);
      const [scope, document, reason, expectedVersion] = api['publishRules'].mock.calls[0];
      expect(scope).toEqual({ tenantId: 't1', brandId: 'b1', locationId: 'l1' });
      expect(document.rules.map((r: DispatchRule) => r.id)).toEqual([
        'far-zone',
        'evenings',
        'rule-3',
      ]);
      expect(document.default).toEqual(builtInAction());
      expect(reason).toBe('Add a catch-all');
      expect(expectedVersion).toBe(3);
      expect(control(host, 'rules-notice').textContent).toContain('Published as version 4');
      expect(find(host, 'rules-dirty')).toBeNull();
    });

    it('publishes the order the list was dragged into', async () => {
      const api = apiWith(PUBLISHED);
      const { fixture, host } = await render(api);

      ruleRows(host)[1].querySelectorAll<HTMLButtonElement>('.rule-list__move')[0].click();
      fixture.detectChanges();
      type(host, 'rules-reason', 'Evenings first');
      fixture.detectChanges();
      control<HTMLButtonElement>(host, 'rules-publish').click();
      await settle(fixture);

      const document = api['publishRules'].mock.calls[0][1];
      expect(document.rules.map((r: DispatchRule) => r.id)).toEqual(['evenings', 'far-zone']);
    });

    it('lists every reason the server refused the document for', async () => {
      const refused = new ApiError(
        'VALIDATION_FAILED',
        400,
        {
          status: 400,
          detail:
            'Rule "far-zone" can never match: "everything" above it already takes every order it would; Rule "r" : x is not an active delivery installation of this company',
        },
        null,
      );
      const { fixture, host } = await render(
        apiWith(PUBLISHED, { publishRules: vi.fn().mockRejectedValue(refused) }),
      );
      control<HTMLButtonElement>(host, 'rules-add').click();
      fixture.detectChanges();
      type(host, 'rules-reason', 'Try');
      fixture.detectChanges();
      control<HTMLButtonElement>(host, 'rules-publish').click();
      await settle(fixture);

      const problems = control(host, 'rules-problems');
      expect(problems.querySelectorAll('li')).toHaveLength(2);
      expect(problems.textContent).toContain('can never match');
      expect(problems.textContent).toContain('not an active delivery installation');
      expect(find(host, 'rules-notice')).toBeNull();
    });

    it('says when someone else published first, keeps the operator’s draft on screen, and offers a reload', async () => {
      const stale = new ApiError(
        'STALE_VERSION',
        409,
        { status: 409, expected: 3, actual: 4 },
        null,
      );
      const api = apiWith(PUBLISHED, { publishRules: vi.fn().mockRejectedValue(stale) });
      const { fixture, host } = await render(api);
      control<HTMLButtonElement>(host, 'rules-add').click();
      fixture.detectChanges();
      type(host, 'rules-reason', 'Try');
      fixture.detectChanges();
      control<HTMLButtonElement>(host, 'rules-publish').click();
      await settle(fixture);

      expect(control(host, 'rules-stale').textContent).toContain('Someone published newer rules');
      expect(control<HTMLButtonElement>(host, 'rules-publish').disabled).toBe(true);
      expect(ruleRows(host)).toHaveLength(3);

      control<HTMLElement>(host, 'rules-stale').querySelector<HTMLButtonElement>('button')!.click();
      await settle(fixture);
      expect(find(host, 'rules-stale')).toBeNull();
      expect(ruleRows(host)).toHaveLength(2);
    });
  });

  describe('who may do what', () => {
    it('shows the rules and offers no way to change them to someone who may only read', async () => {
      const { host } = await render(apiWith(PUBLISHED), { held: ['DELIVERY_DISPATCH_RULES_READ'] });

      expect(ruleRows(host)).toHaveLength(2);
      expect(find(host, 'rules-add')).toBeNull();
      expect(find(host, 'rules-publish-bar')).toBeNull();
      expect(find(host, 'rule-delete')).toBeNull();
    });

    it('is the denied state when the operator covers no branch', async () => {
      const { host } = await render(apiWith(PUBLISHED), { scope: null, denied: true });

      expect(find(host, 'rules-denied')).not.toBeNull();
      expect(ruleRows(host)).toHaveLength(0);
    });
  });

  it('puts the simulator, the timings and the unpaid-order window on the same screen', async () => {
    const { host } = await render(apiWith(PUBLISHED));

    expect(find(host, 'dispatch-simulator')).not.toBeNull();
    expect(find(host, 'timings-card')).not.toBeNull();
    expect(find(host, 'payment-window-card')).not.toBeNull();
  });

  it('shows a load failure with a way to retry, not a blank screen', async () => {
    const down = new ApiError('INTERNAL_ERROR', 500, { status: 500 }, 'corr-1');
    const { fixture, host, api } = await render(
      apiWith(PUBLISHED, { rules: vi.fn().mockRejectedValue(down) }),
    );

    expect(find(host, 'rules-load-error')).not.toBeNull();
    expect(find(host, 'rules-denied')).toBeNull();

    api['rules'].mockResolvedValue({ value: PUBLISHED, version: 3 });
    control<HTMLElement>(host, 'rules-load-error')
      .querySelector<HTMLButtonElement>('button')!
      .click();
    await settle(fixture);
    expect(find(host, 'rules-load-error')).toBeNull();
    expect(ruleRows(host)).toHaveLength(2);
  });

  it('is the denied state, not an error to retry, when the server says this operator may not read the rules', async () => {
    const refused = new ApiError('INSUFFICIENT_CAPABILITY', 403, { status: 403 }, null);
    const { host } = await render(
      apiWith(PUBLISHED, { rules: vi.fn().mockRejectedValue(refused) }),
    );

    expect(find(host, 'rules-denied')).not.toBeNull();
    expect(find(host, 'rules-load-error')).toBeNull();
  });
});
