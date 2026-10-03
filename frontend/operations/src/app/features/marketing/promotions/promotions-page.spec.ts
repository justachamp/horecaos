import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { Mock, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { NO_LOOKUPS } from './promotion-draft';
import { PromotionReferences } from './promotion-references';
import { PromotionsPage } from './promotions-page';
import {
  PromotionBody,
  PromotionRedemption,
  PromotionRule,
  PromotionView,
  PromotionsApi,
} from './promotions-api';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

function rule(type: string, operands: Record<string, unknown>, sequence = 1): PromotionRule {
  return { sequence, type, operands };
}

function promotion(patch: Partial<PromotionView> = {}): PromotionView {
  return {
    promotionId: 'p1',
    version: 4,
    definitionVersion: 2,
    code: 'CLICK5',
    name: '5% off with Click',
    kind: 'DISCOUNT',
    scope: 'ORDER',
    stackingGroup: 'order',
    exclusive: false,
    priority: 2,
    status: 'DRAFT',
    maximumDiscountMinor: null,
    currency: 'UZS',
    validFrom: null,
    validUntil: null,
    maximumRedemptions: null,
    maximumPerCustomer: null,
    consumedCount: 0,
    loyaltyAccrual: 'ACCRUE',
    loyaltyRedemption: 'ALLOW',
    conditions: [rule('PAYMENT_METHOD', { paymentMethodCodes: ['CLICK'] })],
    actions: [rule('ORDER_PERCENTAGE_DISCOUNT', { basisPoints: 500 })],
    validatedAt: null,
    activatedAt: null,
    activatedBy: null,
    approvalId: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    versions: [],
    ...patch,
  };
}

const DRAFT = promotion();
const VALIDATED = promotion({
  promotionId: 'p2',
  code: 'LUNCH10',
  name: 'Lunch 10%',
  status: 'VALIDATED',
  priority: 1,
  conditions: [rule('TIME_OF_DAY', { fromMinuteOfDay: 720, toMinuteOfDay: 900 })],
});
const ACTIVE = promotion({
  promotionId: 'p3',
  code: 'FREEDEL',
  name: 'Free delivery over 90 000',
  scope: 'DELIVERY',
  stackingGroup: 'delivery',
  status: 'ACTIVE',
  conditions: [rule('SUBTOTAL_AT_LEAST', { amountMinor: 90_000 })],
  actions: [rule('FREE_DELIVERY', {})],
  activatedAt: '2026-10-01T05:00:00Z',
  activatedBy: 'owner@example.test',
  versions: [
    {
      definitionVersion: 2,
      reason: 'ACTIVATED',
      recordedBy: 'owner@example.test',
      recordedAt: '2026-10-01T05:00:00Z',
      definition: {},
    },
    {
      definitionVersion: 2,
      reason: 'VALIDATED',
      recordedBy: 'owner@example.test',
      recordedAt: '2026-10-01T04:00:00Z',
      definition: {},
    },
  ],
});
const SUSPENDED = promotion({
  promotionId: 'p4',
  code: 'OLD',
  name: 'Old offer',
  status: 'SUSPENDED',
});
const ARCHIVED = promotion({
  promotionId: 'p5',
  code: 'GONE',
  name: 'Gone offer',
  status: 'ARCHIVED',
});

async function flush(): Promise<void> {
  for (let i = 0; i < 4; i++) {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  }
}

type ApiMethod =
  | 'list'
  | 'detail'
  | 'create'
  | 'update'
  | 'validate'
  | 'activate'
  | 'suspend'
  | 'resume'
  | 'archive'
  | 'reorder'
  | 'redemptions'
  | 'simulate';

function fakeApi(promotions: readonly PromotionView[], overrides: Partial<PromotionsApi> = {}) {
  const store = new Map(promotions.map((p) => [p.promotionId, p]));
  const api: Record<ApiMethod, Mock> = {
    list: vi.fn(async () => [...store.values()]),
    detail: vi.fn(async (_scope: BrandScope, id: string) => store.get(id) as PromotionView),
    create: vi.fn(async (_scope: BrandScope, body: PromotionBody) => {
      const created = promotion({
        promotionId: 'new1',
        code: body.code,
        name: body.name,
        version: 1,
        definitionVersion: 1,
        stackingGroup: body.stackingGroup,
        conditions: body.conditions as PromotionRule[],
        actions: body.actions as PromotionRule[],
      });
      store.set(created.promotionId, created);
      return created;
    }),
    update: vi.fn(async (_scope: BrandScope, id: string, body: PromotionBody) => {
      const updated = promotion({
        ...store.get(id),
        name: body.name,
        version: (store.get(id)?.version ?? 0) + 1,
        status: 'DRAFT',
      });
      store.set(id, updated);
      return updated;
    }),
    validate: vi.fn(),
    activate: vi.fn(),
    suspend: vi.fn(),
    resume: vi.fn(),
    archive: vi.fn(),
    reorder: vi.fn(async () => []),
    redemptions: vi.fn(async () => [] as readonly PromotionRedemption[]),
    simulate: vi.fn(),
    ...(overrides as Partial<Record<ApiMethod, Mock>>),
  };
  return { api, store };
}

function fakeReferences() {
  return {
    lookups: signal(NO_LOOKUPS),
    locations: signal([]),
    menuLocationId: signal<string | null>(null),
    variantsLoading: signal(false),
    load: vi.fn().mockResolvedValue(undefined),
    loadVariants: vi.fn().mockResolvedValue(undefined),
    variantLabel: (id: string) => id,
  };
}

describe('PromotionsPage', () => {
  let fixture: ComponentFixture<PromotionsPage>;
  let host: HTMLElement;

  async function render(
    promotions: readonly PromotionView[],
    overrides: Partial<PromotionsApi> = {},
    scope: BrandScope | null = SCOPE,
  ) {
    const fake = fakeApi(promotions, overrides);
    const refs = fakeReferences();
    await TestBed.configureTestingModule({
      imports: [PromotionsPage],
      providers: [
        provideRouter([]),
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(scope),
            denied: signal(scope === null),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PromotionsApi, useValue: fake.api },
      ],
    })
      .overrideComponent(PromotionsPage, {
        set: { providers: [{ provide: PromotionReferences, useValue: refs }] },
      })
      .compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PromotionsPage);
    host = fixture.nativeElement;
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    return { ...fake, refs };
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function testId(id: string): HTMLElement | null {
    return host.querySelector(`[data-testid="${id}"]`);
  }

  function click(id: string): void {
    (testId(id) as HTMLElement).click();
  }

  function type(id: string, value: string): void {
    const input = testId(id) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  function rowOf(name: string): HTMLElement {
    const row = [...host.querySelectorAll<HTMLElement>('.rule-list__row')].find((el) =>
      el.textContent?.includes(name),
    );
    if (!row) {
      throw new Error(`no row for ${name}`);
    }
    return row;
  }

  async function open(name: string): Promise<void> {
    rowOf(name).querySelector<HTMLElement>('.rule-list__body')!.click();
    await settle();
  }

  it('lists promotions by stacking group, highest priority first, with the switch lit only for a live one', async () => {
    await render([VALIDATED, ACTIVE, DRAFT, ARCHIVED]);
    const groups = [...host.querySelectorAll('[data-testid="promotion-group"]')];
    expect(groups.map((g) => g.querySelector('h2')?.textContent?.trim())).toEqual([
      'Group: delivery',
      'Group: order',
    ]);
    const orderRows = [...groups[1].querySelectorAll('.rule-list__row')];
    // CLICK5 has priority 2, LUNCH10 priority 1.
    expect(orderRows.map((r) => r.querySelector('.rule-list__label')?.textContent?.trim())).toEqual(
      ['5% off with Click', 'Lunch 10%'],
    );
    const lit = [...host.querySelectorAll<HTMLInputElement>('.rule-list__toggle input')].map(
      (box) => box.checked,
    );
    expect(lit).toEqual([true, false, false]);
    // The archived one is history: out of the lists, behind the disclosure.
    expect(host.textContent).toContain('Archived (1)');
    expect(host.querySelectorAll('.rule-list__row')).toHaveLength(3);
  });

  it('says so when no promotion has been authored', async () => {
    await render([]);
    expect(testId('promotions-empty')).not.toBeNull();
  });

  it('shows the denied state when the principal may not read this brand’s promotions', async () => {
    await render([], {
      list: vi
        .fn()
        .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
    });
    expect(testId('promotions-denied')).not.toBeNull();
    expect(testId('promotions-create')).toBeNull();
  });

  it('opens a promotion into the editor with its stored values', async () => {
    await render([DRAFT]);
    await open('5% off with Click');
    expect((testId('promotion-name') as HTMLInputElement).value).toBe('5% off with Click');
    expect((testId('promotion-code') as HTMLInputElement).value).toBe('CLICK5');
    expect((testId('promotion-status') as HTMLElement).textContent).toContain('Draft');
    // The payment-method condition came back as one builder row.
    expect(host.querySelectorAll('.predicate-row')).toHaveLength(1);
  });

  it('locks a live promotion: suspend it first', async () => {
    await render([ACTIVE]);
    await open('Free delivery over 90 000');
    expect(testId('promotion-locked')).not.toBeNull();
    expect(testId('promotion-save')).toBeNull();
    expect(testId('promotion-suspend')).not.toBeNull();
    expect(testId('promotion-activate')).toBeNull();
    expect(host.querySelector<HTMLFieldSetElement>('fieldset')!.disabled).toBe(true);
  });

  it('drafts a new promotion: the body carries the editor’s values and the new draft opens', async () => {
    const { api } = await render([]);
    click('promotions-create');
    await settle();
    expect(testId('promotion-save')).not.toBeNull();
    expect((testId('promotion-save') as HTMLButtonElement).disabled).toBe(true);

    type('promotion-name', 'Lunch 10%');
    type('promotion-code', 'LUNCH10');
    await settle();
    expect((testId('promotion-save') as HTMLButtonElement).disabled).toBe(false);
    click('promotion-save');
    await settle();

    expect(api.create).toHaveBeenCalledTimes(1);
    const body = (api.create.mock.calls[0] as unknown as [BrandScope, PromotionBody])[1];
    expect(body).toMatchObject({
      code: 'LUNCH10',
      name: 'Lunch 10%',
      kind: 'DISCOUNT',
      scope: 'ORDER',
      stackingGroup: 'order',
      exclusive: false,
      maximumDiscountMinor: null,
      maximumRedemptions: null,
      maximumPerCustomer: null,
      conditions: [],
      actions: [
        { sequence: 1, type: 'ORDER_PERCENTAGE_DISCOUNT', operands: { basisPoints: 1_000 } },
      ],
    });
    expect(testId('promotions-notice')?.textContent).toContain('Saved as a draft');
    expect((testId('promotion-status') as HTMLElement).textContent).toContain('Draft');
  });

  it('saves an edit with the version it read, and the promotion goes back to a draft', async () => {
    const { api } = await render([VALIDATED]);
    await open('Lunch 10%');
    type('promotion-name', 'Lunch 15%');
    await settle();
    expect(testId('promotion-dirty')).not.toBeNull();
    click('promotion-save');
    await settle();
    expect(api.update).toHaveBeenCalledTimes(1);
    const call = api.update.mock.calls[0] as unknown as [BrandScope, string, PromotionBody, number];
    expect(call[1]).toBe('p2');
    expect(call[2].name).toBe('Lunch 15%');
    expect(call[3]).toBe(VALIDATED.version);
  });

  it('shows the rule checker’s refusals as codes with a sentence, and leaves the draft a draft', async () => {
    const { api } = await render([DRAFT], {
      validate: vi.fn().mockResolvedValue({
        valid: false,
        refusals: [
          { code: 'SEQUENCE_NEEDS_CUSTOMER_LIMIT', message: 'server english', sequence: 2 },
          { code: 'SOMETHING_NEWER', message: 'A sentence only the server knows', sequence: null },
        ],
        warnings: [{ code: 'UNCAPPED_PERCENTAGE', message: 'x', sequence: null }],
        promotion: DRAFT,
      }),
    });
    await open('5% off with Click');
    click('promotion-validate');
    await settle();
    expect(api.validate).toHaveBeenCalledWith(SCOPE, 'p1', DRAFT.version);
    const refusals = [...host.querySelectorAll('[data-testid="promotion-refusal"]')].map(
      (el) => el.textContent ?? '',
    );
    expect(refusals).toHaveLength(2);
    expect(refusals[0]).toContain('SEQUENCE_NEEDS_CUSTOMER_LIMIT');
    expect(refusals[0]).toContain('item 2');
    expect(refusals[0]).toContain('limit each customer to one redemption');
    expect(refusals[0]).not.toContain('server english');
    // A code this build does not know shows the server's own sentence, not a raw key.
    expect(refusals[1]).toContain('A sentence only the server knows');
    expect(host.querySelectorAll('[data-testid="promotion-warning"]')).toHaveLength(1);
    expect(testId('promotions-notice')?.textContent).toContain('refused');
  });

  it('activates a validated promotion with a note for the approver', async () => {
    const activated = promotion({ ...VALIDATED, status: 'ACTIVE', version: 5 });
    const { api, store } = await render([VALIDATED], {
      activate: vi.fn().mockImplementation(async () => {
        store.set('p2', activated);
        return { outcome: 'ACTIVATED', approvalRequestId: null, promotion: activated };
      }),
    });
    await open('Lunch 10%');
    click('promotion-activate');
    await settle();
    expect(testId('promotion-activation-dialog')).not.toBeNull();
    type('promotion-activation-reason', 'Lunch push, ends in a month');
    click('promotion-activation-confirm');
    await settle();
    expect(api.activate).toHaveBeenCalledWith(
      SCOPE,
      'p2',
      VALIDATED.version,
      'Lunch push, ends in a month',
    );
    expect(testId('promotions-notice')?.textContent).toContain('The promotion is live');
    expect((testId('promotion-status') as HTMLElement).textContent).toContain('Active');
  });

  it('says a second person has been asked when activation answers 202, and stays validated', async () => {
    const { api } = await render([VALIDATED], {
      activate: vi.fn().mockResolvedValue({
        outcome: 'PENDING_APPROVAL',
        approvalRequestId: 'req-77',
        promotion: null,
      }),
    });
    await open('Lunch 10%');
    click('promotion-activate');
    await settle();
    click('promotion-activation-confirm');
    await settle();
    expect(api.activate).toHaveBeenCalledWith(SCOPE, 'p2', VALIDATED.version, null);
    const pending = testId('promotions-pending') as HTMLElement;
    expect(pending.textContent).toContain('req-77');
    expect(pending.querySelector('a')?.getAttribute('href')).toBe('/staff/approvals');
    expect((testId('promotion-status') as HTMLElement).textContent).toContain('Validated');
    // Still activatable: the call is repeated once somebody else has decided.
    expect(testId('promotion-activate')).not.toBeNull();
  });

  it('suspends a live promotion and resumes a suspended one', async () => {
    const suspended = promotion({ ...ACTIVE, status: 'SUSPENDED', version: 5 });
    const { api, store } = await render([ACTIVE, SUSPENDED], {
      suspend: vi.fn().mockImplementation(async () => {
        store.set('p3', suspended);
        return suspended;
      }),
      resume: vi.fn().mockImplementation(async () => {
        const resumed = promotion({ ...SUSPENDED, status: 'ACTIVE', version: 5 });
        store.set('p4', resumed);
        return resumed;
      }),
    });
    await open('Free delivery over 90 000');
    click('promotion-suspend');
    await settle();
    expect(api.suspend).toHaveBeenCalledWith(SCOPE, 'p3', ACTIVE.version);
    expect(testId('promotions-notice')?.textContent).toContain('no longer applies');

    await open('Old offer');
    click('promotion-resume');
    await settle();
    expect(api.resume).toHaveBeenCalledWith(SCOPE, 'p4', SUSPENDED.version);
  });

  it('archives only after a confirmation', async () => {
    const archived = promotion({ ...VALIDATED, status: 'ARCHIVED', version: 6 });
    const { api, store } = await render([VALIDATED], {
      archive: vi.fn().mockImplementation(async () => {
        store.set('p2', archived);
        return archived;
      }),
    });
    await open('Lunch 10%');
    click('promotion-archive');
    await settle();
    expect(api.archive).not.toHaveBeenCalled();
    host.querySelector<HTMLButtonElement>('.q-confirm__cancel')!.click();
    await settle();
    expect(api.archive).not.toHaveBeenCalled();

    click('promotion-archive');
    await settle();
    host
      .querySelector<HTMLButtonElement>(
        '[data-testid="q-confirm-dialog"] button:not(.q-confirm__cancel)',
      )!
      .click();
    await settle();
    expect(api.archive).toHaveBeenCalledWith(SCOPE, 'p2', VALIDATED.version);
    expect(testId('promotion-locked')?.textContent).toContain('archived promotion is history');
  });

  it('persists a drag or a move button as that group’s whole priority order', async () => {
    const other = promotion({ promotionId: 'p6', code: 'B', name: 'Second', priority: 1 });
    const first = promotion({ promotionId: 'p7', code: 'A', name: 'First', priority: 2 });
    const { api } = await render([first, other]);
    // Move "First" down: the order now reads Second, First.
    rowOf('First').querySelectorAll<HTMLButtonElement>('.rule-list__move')[1].click();
    await settle();
    expect(api.reorder).toHaveBeenCalledWith(SCOPE, 'order', ['p6', 'p7']);
    expect(api.list.mock.calls.length).toBeGreaterThan(1);
  });

  it('refuses to switch on a promotion that has not been validated, and calls nothing', async () => {
    const { api } = await render([DRAFT]);
    const box = rowOf('5% off with Click').querySelector<HTMLInputElement>(
      '.rule-list__toggle input',
    )!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await settle();
    expect(testId('promotions-error')?.textContent).toContain('Only a validated promotion');
    expect(api.activate).not.toHaveBeenCalled();
    // The list was re-created, so the switch does not stay lit on a promotion that is not live.
    expect(
      rowOf('5% off with Click').querySelector<HTMLInputElement>('.rule-list__toggle input')!
        .checked,
    ).toBe(false);
  });

  it('switching a validated promotion on opens the activation dialog', async () => {
    await render([VALIDATED]);
    const box = rowOf('Lunch 10%').querySelector<HTMLInputElement>('.rule-list__toggle input')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await settle();
    expect(testId('promotion-activation-dialog')).not.toBeNull();
  });

  it('switching a live promotion off suspends it', async () => {
    const suspended = promotion({ ...ACTIVE, status: 'SUSPENDED', version: 5 });
    const { api, store } = await render([ACTIVE], {
      suspend: vi.fn().mockImplementation(async () => {
        store.set('p3', suspended);
        return suspended;
      }),
    });
    const box = rowOf('Free delivery over 90 000').querySelector<HTMLInputElement>(
      '.rule-list__toggle input',
    )!;
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    await settle();
    expect(api.suspend).toHaveBeenCalledWith(SCOPE, 'p3', ACTIVE.version);
  });

  it('opens a promotion it cannot draw read-only and never offers to save over it', async () => {
    const odd = promotion({
      promotionId: 'p9',
      name: 'Service charge',
      code: 'SVC',
      actions: [rule('SERVICE_CHARGE', { basisPoints: 1_000 })],
    });
    await render([odd]);
    await open('Service charge');
    expect(testId('promotion-unsupported')?.textContent).toContain('SERVICE_CHARGE');
    expect(testId('promotion-save')).toBeNull();
  });

  it('asks before throwing away unsaved edits, and keeps them when told to', async () => {
    const { api } = await render([DRAFT, VALIDATED]);
    await open('5% off with Click');
    type('promotion-name', 'Changed my mind');
    await settle();
    rowOf('Lunch 10%').querySelector<HTMLElement>('.rule-list__body')!.click();
    await settle();
    expect(host.querySelector('[data-testid="q-confirm-dialog"]')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('.q-confirm__cancel')!.click();
    await settle();
    expect((testId('promotion-name') as HTMLInputElement).value).toBe('Changed my mind');
    expect(api.detail).toHaveBeenCalledTimes(1);

    rowOf('Lunch 10%').querySelector<HTMLElement>('.rule-list__body')!.click();
    await settle();
    host
      .querySelector<HTMLButtonElement>(
        '[data-testid="q-confirm-dialog"] button:not(.q-confirm__cancel)',
      )!
      .click();
    await settle();
    expect((testId('promotion-name') as HTMLInputElement).value).toBe('Lunch 10%');
  });

  it('does not offer to validate or activate while edits are unsaved', async () => {
    await render([VALIDATED]);
    await open('Lunch 10%');
    expect(testId('promotion-activate')).not.toBeNull();
    type('promotion-name', 'Lunch 12%');
    await settle();
    expect(testId('promotion-activate')).toBeNull();
    expect(testId('promotion-validate')).toBeNull();
  });

  it('lists the redemptions of the open promotion, amounts only', async () => {
    await render([ACTIVE], {
      redemptions: vi.fn().mockResolvedValue([
        {
          redemptionId: 'r1',
          orderId: 'order-9',
          customerAccountId: 'acct-1',
          definitionVersion: 2,
          discountMinor: 15_000,
          markupMinor: 0,
          currency: 'UZS',
          status: 'REDEEMED',
        },
      ]),
    });
    await open('Free delivery over 90 000');
    click('promotion-pane-redemptions');
    await settle();
    const table = testId('promotion-redemptions') as HTMLElement;
    expect(table.textContent).toContain('order-9');
    expect(table.textContent).toContain('REDEEMED');
    // The account id is deliberately not shown on this screen.
    expect(table.textContent).not.toContain('acct-1');
  });

  it('shows the recorded definition versions, newest first', async () => {
    await render([ACTIVE]);
    await open('Free delivery over 90 000');
    click('promotion-pane-history');
    await settle();
    const rows = [...(testId('promotion-history') as HTMLElement).querySelectorAll('tbody tr')];
    expect(rows.map((r) => r.querySelectorAll('td')[1].textContent?.trim())).toEqual([
      'ACTIVATED',
      'VALIDATED',
    ]);
  });

  it('re-reads the promotion when the server says it changed underneath the operator', async () => {
    const fresh = promotion({ ...VALIDATED, status: 'ACTIVE', version: 9 });
    const { api, store } = await render([VALIDATED], {
      activate: vi.fn().mockImplementation(async () => {
        store.set('p2', fresh);
        throw new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null);
      }),
    });
    await open('Lunch 10%');
    click('promotion-activate');
    await settle();
    click('promotion-activation-confirm');
    await settle();
    expect(testId('promotions-error')).not.toBeNull();
    expect((testId('promotion-status') as HTMLElement).textContent).toContain('Active');
    expect(api.detail.mock.calls.length).toBeGreaterThan(1);
  });
});
