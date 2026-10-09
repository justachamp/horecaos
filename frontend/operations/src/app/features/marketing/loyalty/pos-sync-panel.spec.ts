import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import {
  PosSyncApi,
  PosSyncBindingView,
  PosSyncDiscrepancyView,
  PosSyncOverview,
  PosSyncRunReport,
  PosSyncRunView,
} from './pos-sync-api';
import { PosSyncPanel } from './pos-sync-panel';

const SCOPE: BrandScope = { tenantId: 't1', brandId: 'b1' };

function binding(overrides: Partial<PosSyncBindingView> = {}): PosSyncBindingView {
  return {
    bindingId: 'bind-1',
    installationId: 'inst-1',
    providerType: 'fake-pos',
    locationId: null,
    bindingStatus: 'ACTIVE',
    capabilityState: 'SUPPORTED',
    enabled: true,
    syncing: true,
    mode: 'REPORT_ONLY',
    limits: {},
    matchedAccounts: 3,
    openDiscrepancies: 2,
    lastRun: {
      runId: 'run-1',
      kind: 'RECONCILE',
      status: 'SUCCEEDED',
      startedAt: '2026-10-08T03:00:00Z',
      finishedAt: null,
    },
    ...overrides,
  };
}

function overview(overrides: Partial<PosSyncOverview> = {}): PosSyncOverview {
  return {
    tillKeepsBalance: false,
    standingNotice: false,
    openDiscrepancies: 2,
    bindings: [binding()],
    ...overrides,
  };
}

function run(overrides: Partial<PosSyncRunView> = {}): PosSyncRunView {
  return {
    runId: 'run-1',
    bindingId: 'bind-1',
    kind: 'RECONCILE',
    status: 'SUCCEEDED',
    failureCode: null,
    startedAt: '2026-10-08T03:00:00Z',
    finishedAt: '2026-10-08T03:00:02Z',
    compared: 3,
    matched: 1,
    tillHigher: 1,
    tillLower: 1,
    tillOnly: 0,
    platformOnly: 0,
    ambiguous: 0,
    imported: 0,
    held: 0,
    unverifiable: 0,
    pushed: 0,
    ...overrides,
  };
}

function difference(overrides: Partial<PosSyncDiscrepancyView> = {}): PosSyncDiscrepancyView {
  return {
    discrepancyId: 'd-1',
    bindingId: 'bind-1',
    accountId: 'acct-1',
    externalCustomerId: 'T-DILYA',
    kind: 'TILL_HIGHER',
    platformBalanceMinor: 10_000,
    tillBalanceMinor: 25_000,
    deltaMinor: 15_000,
    status: 'OPEN',
    resolutionCode: null,
    resolutionReason: null,
    resolvedBy: null,
    resolvedAt: null,
    observedAt: '2026-10-08T03:00:01Z',
    version: 1,
    ...overrides,
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('PosSyncPanel', () => {
  let fixture: ComponentFixture<PosSyncPanel>;
  let api: {
    overview: ReturnType<typeof vi.fn>;
    runs: ReturnType<typeof vi.fn>;
    run: ReturnType<typeof vi.fn>;
    discrepancies: ReturnType<typeof vi.fn>;
    reconcile: ReturnType<typeof vi.fn>;
    resolve: ReturnType<typeof vi.fn>;
  };

  async function render(overrides: Partial<typeof api> = {}, brandAllowed = true): Promise<void> {
    api = {
      overview: vi.fn().mockResolvedValue(overview()),
      runs: vi.fn().mockResolvedValue([run()]),
      run: vi
        .fn()
        .mockResolvedValue({
          run: run(),
          discrepancies: [difference()],
        } satisfies PosSyncRunReport),
      discrepancies: vi.fn().mockResolvedValue([
        difference(),
        difference({
          discrepancyId: 'd-2',
          kind: 'TILL_LOWER',
          platformBalanceMinor: 30_000,
          tillBalanceMinor: 5_000,
          deltaMinor: -25_000,
        }),
      ]),
      reconcile: vi.fn().mockResolvedValue(run()),
      resolve: vi.fn().mockResolvedValue({
        discrepancy: difference({ status: 'RESOLVED' }),
        applied: true,
        approval: 'NOT_REQUIRED',
        approvalRequestId: null,
      }),
      ...overrides,
    };

    TestBed.resetTestingModule();
    await TestBed.configureTestingModule({
      imports: [PosSyncPanel],
      providers: [
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(brandAllowed ? SCOPE : null),
            denied: signal(!brandAllowed),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: PosSyncApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PosSyncPanel);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;

  it('states the five-minute window on every view', async () => {
    await render();
    expect(host().querySelector('[data-testid="pos-sync-lag"]')?.textContent).toContain(
      'up to five minutes',
    );
  });

  it('shows the standing notice only when the till keeps a balance and nothing mirrors it', async () => {
    await render({ overview: vi.fn().mockResolvedValue(overview({ standingNotice: true })) });
    const notice = host().querySelector('[data-testid="pos-sync-standing-notice"]');
    expect(notice?.textContent).toContain('keeps its own bonus balance');
    expect(notice?.textContent).toContain('can differ');

    await render({ overview: vi.fn().mockResolvedValue(overview({ standingNotice: false })) });
    expect(host().querySelector('[data-testid="pos-sync-standing-notice"]')).toBeNull();
  });

  it('reports a vendor nobody has assessed as unable, with the reason, and offers no reconcile for it', async () => {
    await render({
      overview: vi.fn().mockResolvedValue(
        overview({
          bindings: [
            binding({
              providerType: 'clopos',
              capabilityState: 'UNASSESSED',
              enabled: false,
              syncing: false,
            }),
          ],
        }),
      ),
    });
    const card = host().querySelector('[data-testid="pos-sync-binding"]') as HTMLElement;
    expect(card.querySelector('[data-testid="pos-sync-capability"]')?.textContent).toContain(
      'Not assessed',
    );
    expect(card.querySelector('[data-testid="pos-sync-capability-note"]')?.textContent).toContain(
      'Nobody has assessed',
    );
    expect(card.querySelector('[data-testid="pos-sync-reconcile"]')).toBeNull();
  });

  it('shows a capable binding as able, with its mode and counts, and reconciles it on request', async () => {
    await render();
    const card = host().querySelector('[data-testid="pos-sync-binding"]') as HTMLElement;
    expect(card.querySelector('[data-testid="pos-sync-capability"]')?.textContent).toContain(
      'Can sync',
    );
    expect(card.querySelector('[data-testid="pos-sync-mode"]')?.textContent).toContain(
      'Report only',
    );

    (card.querySelector('[data-testid="pos-sync-reconcile"]') as HTMLButtonElement).click();
    await flush();

    expect(api.reconcile).toHaveBeenCalledWith(SCOPE, 'bind-1');
    expect(api.overview).toHaveBeenCalledTimes(2);
  });

  it('lists every difference with both balances and the signed delta, and no name or phone', async () => {
    await render();
    const rows = host().querySelectorAll('[data-testid="pos-sync-discrepancy"]');
    expect(rows.length).toBe(2);
    const first = rows[0] as HTMLElement;
    expect(first.querySelector('[data-testid="pos-sync-discrepancy-kind"]')?.textContent).toContain(
      'Till higher',
    );
    expect(first.querySelector('[data-testid="pos-sync-balances"]')?.textContent).toContain('10');
    expect(first.querySelector('[data-testid="pos-sync-delta"]')?.textContent).toContain('+');
    const second = rows[1] as HTMLElement;
    expect(second.querySelector('[data-testid="pos-sync-delta"]')?.textContent).toContain('−');
    expect(host().textContent).not.toMatch(/\+998/);
  });

  it('offers only the resolutions that fit the kind of difference', async () => {
    await render({
      discrepancies: vi
        .fn()
        .mockResolvedValue([
          difference({ discrepancyId: 'held', kind: 'CREDIT_HELD' }),
          difference({ discrepancyId: 'seen', kind: 'TILL_ONLY', accountId: null }),
        ]),
    });
    const rows = host().querySelectorAll('[data-testid="pos-sync-discrepancy"]');
    const held = rows[0] as HTMLElement;
    expect(held.querySelector('[data-testid="pos-sync-resolve-ACCEPT_TILL"]')).not.toBeNull();
    expect(held.querySelector('[data-testid="pos-sync-resolve-ABSORB"]')).toBeNull();
    const seen = rows[1] as HTMLElement;
    expect(seen.querySelector('[data-testid="pos-sync-resolve-ABSORB"]')).not.toBeNull();
    expect(seen.querySelector('[data-testid="pos-sync-resolve-ACCEPT_TILL"]')).toBeNull();
  });

  it('needs a reason before a difference can be settled, and sends the version the operator read', async () => {
    await render();
    const row = host().querySelector('[data-testid="pos-sync-discrepancy"]') as HTMLElement;
    const accept = row.querySelector(
      '[data-testid="pos-sync-resolve-ACCEPT_PLATFORM"]',
    ) as HTMLButtonElement;
    expect(accept.disabled).toBe(true);

    const reason = row.querySelector('[data-testid="pos-sync-reason"]') as HTMLInputElement;
    reason.value = 'The till was wrong';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(accept.disabled).toBe(false);

    accept.click();
    await flush();

    expect(api.resolve).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ discrepancyId: 'd-1', version: 1 }),
      'ACCEPT_PLATFORM',
      'The till was wrong',
    );
  });

  it('says a large credit is waiting for a second signature and leaves the difference where it is', async () => {
    await render({
      resolve: vi.fn().mockResolvedValue({
        discrepancy: difference(),
        applied: false,
        approval: 'PENDING',
        approvalRequestId: 'req-1',
      }),
    });
    const row = host().querySelector('[data-testid="pos-sync-discrepancy"]') as HTMLElement;
    const reason = row.querySelector('[data-testid="pos-sync-reason"]') as HTMLInputElement;
    reason.value = 'Wedding';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      row.querySelector('[data-testid="pos-sync-resolve-ACCEPT_TILL"]') as HTMLButtonElement
    ).click();
    await flush();
    fixture.detectChanges();

    expect(host().querySelector('[data-testid="pos-sync-pending"]')?.textContent).toContain(
      'second person',
    );
    expect(host().querySelectorAll('[data-testid="pos-sync-discrepancy"]').length).toBe(2);
    expect(api.overview).toHaveBeenCalledTimes(1);
  });

  it('shows a refusal in words and keeps the page', async () => {
    await render({
      resolve: vi
        .fn()
        .mockRejectedValue(new ApiError('UNPROCESSABLE_STATE', 422, { status: 422 }, null)),
    });
    const row = host().querySelector('[data-testid="pos-sync-discrepancy"]') as HTMLElement;
    const reason = row.querySelector('[data-testid="pos-sync-reason"]') as HTMLInputElement;
    reason.value = 'x';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (row.querySelector('[data-testid="pos-sync-resolve-ABSORB"]') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(host().querySelector('[data-testid="pos-sync-action-error"]')).not.toBeNull();
    expect(host().querySelectorAll('[data-testid="pos-sync-discrepancy"]').length).toBe(2);
  });

  it('opens a reconciliation report to the differences that pass found', async () => {
    await render();
    (host().querySelector('[data-testid="pos-sync-run-row"] button') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();

    expect(api.run).toHaveBeenCalledWith(SCOPE, 'run-1');
    expect(host().querySelector('[data-testid="pos-sync-opened-run"]')).not.toBeNull();
    expect(host().querySelectorAll('[data-testid="pos-sync-opened-run-row"]').length).toBe(1);
  });

  it('shows empty states for no terminals, no passes and no differences', async () => {
    await render({
      overview: vi.fn().mockResolvedValue(overview({ bindings: [], openDiscrepancies: 0 })),
      runs: vi.fn().mockResolvedValue([]),
      discrepancies: vi.fn().mockResolvedValue([]),
    });
    expect(host().querySelector('[data-testid="pos-sync-no-bindings"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="pos-sync-runs-empty"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="pos-sync-discrepancies-empty"]')).not.toBeNull();
  });

  it('shows the denied state without a brand grant', async () => {
    await render({}, false);
    expect(host().querySelector('[data-testid="pos-sync-denied"]')).not.toBeNull();
    expect(api.overview).not.toHaveBeenCalled();
  });
});
