import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import {
  AutomationPreviewCandidate,
  AutomationRuleView,
  AutomationRunView,
  AutomationsApi,
} from './automations-api';
import { AutomationsPage } from './automations-page';

const BRAND_SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

function rule(overrides: Partial<AutomationRuleView> = {}): AutomationRuleView {
  return {
    id: 'rule-1',
    name: 'Birthday treat',
    triggerType: 'BIRTHDAY',
    channel: 'MESSAGING_APP',
    consentPurpose: 'MARKETING_PROMOTIONS',
    templateKey: 'AUTOMATION_BIRTHDAY',
    triggerConfig: { birthdayWindowDays: 0 },
    cooldownDays: 365,
    priority: 0,
    active: false,
    activatedBy: null,
    activatedAt: null,
    version: 1,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

function fakeApi(overrides: Partial<AutomationsApi> = {}): Partial<AutomationsApi> {
  return {
    list: vi.fn().mockResolvedValue([]),
    create: vi.fn(),
    update: vi.fn(),
    activate: vi.fn(),
    deactivate: vi.fn(),
    reorder: vi.fn(),
    runs: vi.fn().mockResolvedValue([]),
    preview: vi.fn().mockResolvedValue([]),
    ...overrides,
  };
}

describe('AutomationsPage', () => {
  let fixture: ComponentFixture<AutomationsPage>;

  async function render(
    api: Partial<AutomationsApi>,
    scope: BrandScope | null = BRAND_SCOPE,
  ): Promise<void> {
    await TestBed.configureTestingModule({
      imports: [AutomationsPage],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(scope),
            denied: signal(scope === null),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: AutomationsApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(AutomationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('lists an authored rule through q-rule-list, with its trigger, channel and cooldown in the description', async () => {
    await render(fakeApi({ list: vi.fn().mockResolvedValue([rule()]) }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.textContent).toContain('Birthday treat');
    expect(host.textContent).toContain('Birthday');
    expect(host.textContent).toContain('Telegram');
    expect(host.textContent).toContain('365');
  });

  it('shows an empty state when the brand has authored no automation', async () => {
    await render(fakeApi());
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="automations-empty"]')).not.toBeNull();
  });

  it('shows the denied state when the brand grant is missing', async () => {
    await render(fakeApi(), null);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="automations-denied"]'),
    ).not.toBeNull();
  });

  it('arms an inactive rule through the enabled toggle, calling activate with its current version', async () => {
    const activate = vi.fn().mockResolvedValue(rule({ active: true, version: 2 }));
    const api = fakeApi({ list: vi.fn().mockResolvedValue([rule()]), activate });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    const toggle = host.querySelector('input[type="checkbox"]') as HTMLInputElement;
    expect(toggle).not.toBeNull();
    toggle.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(activate).toHaveBeenCalledWith(BRAND_SCOPE, 'rule-1', 1);
  });

  it('disarms an active rule through the enabled toggle, calling deactivate', async () => {
    const deactivate = vi.fn().mockResolvedValue(rule({ active: false, version: 3 }));
    const api = fakeApi({
      list: vi.fn().mockResolvedValue([rule({ active: true, version: 2 })]),
      deactivate,
    });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    const toggle = host.querySelector('input[type="checkbox"]') as HTMLInputElement;
    toggle.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(deactivate).toHaveBeenCalledWith(BRAND_SCOPE, 'rule-1', 2);
  });

  it('shows the refusal rather than a silently-still-off toggle when arming is refused (e.g. an unwired channel)', async () => {
    const activate = vi.fn().mockRejectedValue(new Error('boom'));
    const api = fakeApi({ list: vi.fn().mockResolvedValue([rule()]), activate });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('input[type="checkbox"]') as HTMLInputElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="automations-action-error"]')).not.toBeNull();
  });

  it('persists a drag/keyboard reorder as the new whole-set priority', async () => {
    const reordered = [
      rule({ id: 'rule-2', name: 'Second', priority: 0 }),
      rule({ id: 'rule-1', name: 'First', priority: 1 }),
    ];
    const reorder = vi.fn().mockResolvedValue(reordered);
    const api = fakeApi({
      list: vi
        .fn()
        .mockResolvedValue([
          rule({ id: 'rule-1', name: 'First' }),
          rule({ id: 'rule-2', name: 'Second', priority: 1 }),
        ]),
      reorder,
    });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    // q-rule-list's own keyboard reorder: the first row's second `.rule-list__move`
    // button is "move down" (see rule-list.html — up first, down second).
    const firstRow = host.querySelectorAll('.rule-list__row')[0];
    const moveDown = firstRow.querySelectorAll('.rule-list__move')[1] as HTMLButtonElement;
    moveDown.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(reorder).toHaveBeenCalledWith(BRAND_SCOPE, ['rule-2', 'rule-1']);
  });

  it('creates a rule inert — the form never arms it', async () => {
    const create = vi.fn().mockResolvedValue(rule());
    const api = fakeApi({ create, list: vi.fn().mockResolvedValue([]) });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="automations-create"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const nameInput = host.querySelector(
      '[data-testid="automation-form-name"]',
    ) as HTMLInputElement;
    nameInput.value = 'Birthday treat';
    nameInput.dispatchEvent(new Event('input'));
    const templateInput = host.querySelector(
      '[data-testid="automation-form-template-key"]',
    ) as HTMLInputElement;
    templateInput.value = 'AUTOMATION_BIRTHDAY';
    templateInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="automation-form-submit"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(create).toHaveBeenCalledWith(
      BRAND_SCOPE,
      expect.objectContaining({
        name: 'Birthday treat',
        triggerType: 'BIRTHDAY',
        triggerConfig: { birthdayWindowDays: 0 },
      }),
    );
    // No activate call anywhere near creation — arming is a separate act.
    expect(api.activate).not.toHaveBeenCalled();
  });

  it('offers the four built trigger kinds — never LATE_ORDER_APOLOGY', async () => {
    await render(fakeApi());
    const host = fixture.nativeElement as HTMLElement;
    (host.querySelector('[data-testid="automations-create"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const options = [
      ...host.querySelectorAll('[data-testid="automation-form-trigger"] option'),
    ].map((option) => (option as HTMLOptionElement).value);
    expect(options).toEqual(['BIRTHDAY', 'INACTIVITY', 'CART_ABANDONMENT', 'CASHBACK_CHANGE']);
  });

  it("shows a rule's recent firing history, including a refusal reason", async () => {
    const run: AutomationRunView = {
      id: 'run-1',
      customerAccountId: 'acct-1',
      status: 'REFUSED',
      refusalReason: 'CONSENT_WITHHELD',
      cancelledReason: null,
      firedAt: '2026-09-25T09:00:00Z',
    };
    const api = fakeApi({
      list: vi.fn().mockResolvedValue([rule()]),
      runs: vi.fn().mockResolvedValue([run]),
    });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('.automations__run-link') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="automation-runs-dialog"]')?.textContent).toContain(
      'CONSENT_WITHHELD',
    );
  });

  // ------------------------------------------------------------- preview (row X.25)

  it('previews which customers a rule would match today, PII masked, without arming or sending anything', async () => {
    const candidates: AutomationPreviewCandidate[] = [
      { customerAccountId: 'acct-1', maskedDisplayName: 'A***** B****' },
    ];
    const preview = vi.fn().mockResolvedValue(candidates);
    const api = fakeApi({ list: vi.fn().mockResolvedValue([rule()]), preview });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="automation-preview-link"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(preview).toHaveBeenCalledWith(BRAND_SCOPE, 'rule-1');
    const dialog = host.querySelector('[data-testid="automation-preview-dialog"]');
    expect(dialog?.textContent).toContain('A***** B****');
    // A preview is a read: nothing here should arm the rule or record a firing.
    expect(api.activate).not.toHaveBeenCalled();
    expect(api.runs).not.toHaveBeenCalled();
  });

  // --------------------------------------- preview through q-rule-simulator (row X.25)

  const INACTIVITY_RULE = (): AutomationRuleView =>
    rule({
      id: 'rule-2',
      name: 'Win-back',
      triggerType: 'INACTIVITY',
      templateKey: 'AUTOMATION_INACTIVITY',
      triggerConfig: { inactivityDays: 90 },
      cooldownDays: 30,
    });

  async function openPreviewOf(
    target: AutomationRuleView,
    api: Partial<AutomationsApi>,
  ): Promise<HTMLElement> {
    await render(api);
    const host = fixture.nativeElement as HTMLElement;
    const link = [...host.querySelectorAll('[data-testid="automation-preview-link"]')][
      (await api.list!(BRAND_SCOPE)).findIndex((candidate) => candidate.id === target.id)
    ] as HTMLButtonElement;
    link.click();
    await flushMicrotasks();
    fixture.detectChanges();
    return host;
  }

  function typeCandidate(host: HTMLElement, value: string): void {
    const input = host.querySelector(
      '[data-testid="automation-simulator"] .candidate-input',
    ) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function simulatorRow(host: HTMLElement): HTMLElement {
    return host.querySelector('[data-testid="automation-simulator"] .result-row') as HTMLElement;
  }

  it('previews through q-rule-simulator: the rule fires for a customer past its threshold, and says what it would do', async () => {
    const api = fakeApi({ list: vi.fn().mockResolvedValue([INACTIVITY_RULE()]) });
    const host = await openPreviewOf(INACTIVITY_RULE(), api);

    expect(
      host.querySelector('[data-testid="automation-simulator"] q-rule-simulator'),
    ).not.toBeNull();
    // The field the operator fills in is the one thing this rule's trigger reads.
    expect(host.querySelector('[data-testid="automation-simulator"]')?.textContent).toContain(
      'Days since the last order',
    );

    typeCandidate(host, '100');
    const row = simulatorRow(host);
    expect(row.classList.contains('result-row--matched')).toBe(true);
    expect(row.textContent).toContain('Win-back');
    expect(row.textContent).toContain('AUTOMATION_INACTIVITY');
    expect(row.textContent).toContain('Telegram');
    expect(row.textContent).toContain('30');
  });

  it('says the rule would not fire for a customer short of its threshold', async () => {
    const api = fakeApi({ list: vi.fn().mockResolvedValue([INACTIVITY_RULE()]) });
    const host = await openPreviewOf(INACTIVITY_RULE(), api);

    typeCandidate(host, '89');
    expect(simulatorRow(host).classList.contains('result-row--matched')).toBe(false);
    expect(simulatorRow(host).textContent).toContain('Not matched');

    typeCandidate(host, '90');
    expect(simulatorRow(host).classList.contains('result-row--matched')).toBe(true);
  });

  it('evaluates a rule that is not armed yet: a preview asks whether it would fire once armed', async () => {
    const inert = INACTIVITY_RULE();
    expect(inert.active).toBe(false);
    const api = fakeApi({ list: vi.fn().mockResolvedValue([inert]) });
    const host = await openPreviewOf(inert, api);

    typeCandidate(host, '365');

    expect(simulatorRow(host).textContent).not.toContain('never evaluated');
    expect(simulatorRow(host).classList.contains('result-row--matched')).toBe(true);
  });

  it('simulates only the rule being previewed, not the other rules on the page', async () => {
    const birthday = rule({ id: 'rule-1', name: 'Birthday treat' });
    const winBack = INACTIVITY_RULE();
    const api = fakeApi({ list: vi.fn().mockResolvedValue([birthday, winBack]) });
    const host = await openPreviewOf(winBack, api);

    const rows = host.querySelectorAll('[data-testid="automation-simulator"] .result-row');
    expect(rows.length).toBe(1);
    expect(rows[0].textContent).toContain('Win-back');
    expect(api.preview).toHaveBeenCalledWith(BRAND_SCOPE, 'rule-2');
  });

  it('reads nothing and arms nothing while a customer is typed in: the simulator makes no request', async () => {
    const api = fakeApi({ list: vi.fn().mockResolvedValue([INACTIVITY_RULE()]) });
    const host = await openPreviewOf(INACTIVITY_RULE(), api);
    const listed = (api.list as ReturnType<typeof vi.fn>).mock.calls.length;
    const previewed = (api.preview as ReturnType<typeof vi.fn>).mock.calls.length;

    typeCandidate(host, '120');
    typeCandidate(host, '5');
    await flushMicrotasks();

    expect((api.list as ReturnType<typeof vi.fn>).mock.calls.length).toBe(listed);
    expect((api.preview as ReturnType<typeof vi.fn>).mock.calls.length).toBe(previewed);
    expect(api.activate).not.toHaveBeenCalled();
    expect(api.runs).not.toHaveBeenCalled();
  });

  it('keeps the server sample beside the simulator: real, masked customers who match today', async () => {
    const api = fakeApi({
      list: vi.fn().mockResolvedValue([INACTIVITY_RULE()]),
      preview: vi
        .fn()
        .mockResolvedValue([{ customerAccountId: 'acct-9', maskedDisplayName: 'K***** L****' }]),
    });
    const host = await openPreviewOf(INACTIVITY_RULE(), api);

    const dialog = host.querySelector('[data-testid="automation-preview-dialog"]')!;
    expect(dialog.querySelector('[data-testid="automation-simulator"]')).not.toBeNull();
    expect(dialog.textContent).toContain('Customers who match today');
    expect(dialog.querySelector('[data-testid="automation-preview-row"]')?.textContent).toContain(
      'K***** L****',
    );
  });

  it('shows the sample alone for a rule whose trigger it cannot express', async () => {
    const unknown = rule({ id: 'rule-3', triggerType: 'LATE_ORDER_APOLOGY', triggerConfig: {} });
    const api = fakeApi({ list: vi.fn().mockResolvedValue([unknown]) });
    const host = await openPreviewOf(unknown, api);

    expect(host.querySelector('[data-testid="automation-simulator"]')).toBeNull();
    expect(host.querySelector('[data-testid="automation-preview-empty"]')).not.toBeNull();
  });

  it('shows the empty state when no customer matches the rule today', async () => {
    const api = fakeApi({
      list: vi.fn().mockResolvedValue([rule()]),
      preview: vi.fn().mockResolvedValue([]),
    });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="automation-preview-link"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('[data-testid="automation-preview-empty"]')).not.toBeNull();
  });

  it('shows the refusal rather than a stale or blank list when the preview call fails', async () => {
    const preview = vi.fn().mockRejectedValue(new Error('boom'));
    const api = fakeApi({ list: vi.fn().mockResolvedValue([rule()]), preview });
    await render(api);
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="automation-preview-link"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('.dialog__error')).not.toBeNull();
    expect(host.querySelector('[data-testid="automation-preview-list"]')).toBeNull();
  });
});
