import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { CurrentLocation } from '../../core/auth/current-location';
import { SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import {
  MaterialisationLine,
  MaterialisationRun,
  StopMaterialisationApi,
} from './stop-materialisation-api';
import { StopMaterialisationPanel } from './stop-materialisation-panel';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function run(overrides: Partial<MaterialisationRun> = {}): MaterialisationRun {
  return {
    id: 'run-1',
    status: 'COMPLETED',
    startedAt: '2026-10-14T09:00:00Z',
    completedAt: '2026-10-14T09:00:05Z',
    stopsSeen: 6,
    positionsWritten: 3,
    positionsAlreadyUnavailable: 1,
    notCarried: 2,
    failedStops: 0,
    acknowledged: false,
    acknowledgedAt: null,
    version: 4,
    ...overrides,
  };
}

function line(id: string, overrides: Partial<MaterialisationLine> = {}): MaterialisationLine {
  return {
    id,
    stopId: `stop-${id}`,
    variantId: `variant-${id}`,
    variantName: `Dish ${id}`,
    scopeType: 'BRAND',
    source: 'OPERATOR',
    locationId: 'l1',
    locationName: 'Centre',
    reasonCode: 'UNTRACKED_ITEM',
    ...overrides,
  };
}

async function settle(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('StopMaterialisationPanel (ADR 0141, rollback switch three)', () => {
  let fixture: ComponentFixture<StopMaterialisationPanel>;
  let api: {
    latest: ReturnType<typeof vi.fn>;
    start: ReturnType<typeof vi.fn>;
    report: ReturnType<typeof vi.fn>;
    acknowledge: ReturnType<typeof vi.fn>;
  };

  async function render(
    options: {
      canManage?: boolean;
      latest?: MaterialisationRun | null;
      lines?: readonly MaterialisationLine[];
      nextCursor?: string | null;
    } = {},
  ): Promise<HTMLElement> {
    api = {
      latest: vi.fn().mockResolvedValue(options.latest === undefined ? run() : options.latest),
      start: vi.fn().mockResolvedValue(run({ id: 'run-2', version: 1 })),
      report: vi
        .fn()
        .mockResolvedValue({ items: options.lines ?? [], nextCursor: options.nextCursor ?? null }),
      acknowledge: vi
        .fn()
        .mockResolvedValue(
          run({ acknowledged: true, acknowledgedAt: '2026-10-14T09:30:00Z', version: 5 }),
        ),
    };
    await TestBed.configureTestingModule({
      imports: [StopMaterialisationPanel],
      providers: [
        {
          provide: CurrentLocation,
          useValue: {
            scope: signal<LocationScope | null>(SCOPE),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        {
          provide: SessionCapabilities,
          useValue: {
            has: (capability: string) =>
              capability === 'INVENTORY_STOP_MANAGE' && options.canManage !== false,
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: StopMaterialisationApi, useValue: api },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(StopMaterialisationPanel);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function byId(host: HTMLElement, testId: string): HTMLElement | null {
    return host.querySelector(`[data-testid="${testId}"]`);
  }

  function text(host: HTMLElement, testId: string): string {
    return (byId(host, testId)?.textContent ?? '').replace(/\s+/g, ' ').trim();
  }

  async function click(host: HTMLElement, testId: string): Promise<void> {
    (byId(host, testId) as HTMLElement).click();
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
  }

  it('shows nothing, and asks the platform nothing, to someone who cannot manage brand-wide stops', async () => {
    const host = await render({ canManage: false });

    expect(byId(host, 'stop-decommission')).toBeNull();
    expect(api.latest).not.toHaveBeenCalled();
  });

  it('says there is no run yet and offers to start one', async () => {
    const host = await render({ latest: null });

    expect(text(host, 'decommission-none')).toContain('No run yet');
    expect(byId(host, 'decommission-start')).not.toBeNull();
    expect(api.report).not.toHaveBeenCalled();
  });

  it('reads the newest run with its counts and the first page of its report, dish and branch named', async () => {
    const host = await render({
      lines: [
        line('a'),
        line('b', {
          reasonCode: 'CHANNEL_SCOPE',
          scopeType: 'CHANNEL',
          locationId: null,
          locationName: null,
        }),
        line('c', { variantName: null, reasonCode: 'WRITE_FAILED' }),
      ],
    });

    expect(api.latest).toHaveBeenCalledWith(SCOPE);
    expect(api.report).toHaveBeenCalledWith(SCOPE, 'run-1', null);
    expect(text(host, 'decommission-status')).toContain('Finished, not yet accepted');
    expect(text(host, 'decommission-counts')).toContain('6 stops looked at');
    expect(text(host, 'decommission-counts')).toContain('3 written onto a position');
    expect(text(host, 'decommission-not-carried')).toContain('2 not carried');
    const rows = [...host.querySelectorAll('[data-testid="decommission-line"]')].map((row) =>
      (row.textContent ?? '').replace(/\s+/g, ' ').trim(),
    );
    expect(rows[0]).toContain('Dish a');
    expect(rows[0]).toContain('Centre');
    expect(rows[0]).toContain('does not track stock');
    expect(rows[1]).toContain('Every branch');
    expect(rows[1]).toContain('One channel');
    expect(rows[2]).toContain('variant-c');
    expect(rows[2]).toContain('the switch stays blocked');
  });

  it('pages the report: more lines are appended under the cursor the platform gave', async () => {
    const host = await render({ lines: [line('a')], nextCursor: 'cursor-1' });
    api.report.mockResolvedValue({ items: [line('b')], nextCursor: null });

    await click(host, 'decommission-more');

    expect(api.report).toHaveBeenLastCalledWith(SCOPE, 'run-1', 'cursor-1');
    expect(host.querySelectorAll('[data-testid="decommission-line"]').length).toBe(2);
    expect(byId(host, 'decommission-more')).toBeNull();
  });

  it('starts a run, shows it and its report, and clears a previous acceptance tick', async () => {
    const host = await render({ lines: [line('a')] });
    api.report.mockResolvedValue({ items: [line('x')], nextCursor: null });

    await click(host, 'decommission-start');

    expect(api.start).toHaveBeenCalledWith(SCOPE);
    expect(api.report).toHaveBeenLastCalledWith(SCOPE, 'run-2', null);
    expect(byId(host, 'decommission-run')?.getAttribute('data-status')).toBe('COMPLETED');
    expect((byId(host, 'decommission-accept') as HTMLInputElement).checked).toBe(false);
  });

  it('says plainly when a run left nothing out', async () => {
    const host = await render({ latest: run({ notCarried: 0 }), lines: [] });

    expect(text(host, 'decommission-nothing-lost')).toContain('Nothing was left out');
  });

  it('warns that failed writes keep the switch blocked', async () => {
    const host = await render({ latest: run({ failedStops: 2, notCarried: 2 }) });

    expect(text(host, 'decommission-failed')).toContain('2 stops could not be written');
    expect(text(host, 'decommission-failed')).toContain('keep the switch blocked');
  });

  it('accepts the report only after it has been ticked as read, quoting the run’s version', async () => {
    const host = await render({ lines: [line('a')] });
    const acknowledge = byId(host, 'decommission-acknowledge') as HTMLButtonElement;
    expect(acknowledge.disabled).toBe(true);

    const tick = byId(host, 'decommission-accept') as HTMLInputElement;
    tick.checked = true;
    tick.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(acknowledge.disabled).toBe(false);
    await click(host, 'decommission-acknowledge');

    expect(api.acknowledge).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ id: 'run-1', version: 4 }),
    );
    expect(text(host, 'decommission-acknowledged')).toContain('Accepted');
    expect(text(host, 'decommission-acknowledged')).toContain('platform administrator');
    expect(byId(host, 'decommission-acknowledge')).toBeNull();
  });

  it('offers no acceptance for a run already accepted, or one still running', async () => {
    const accepted = await render({
      latest: run({ acknowledged: true, acknowledgedAt: '2026-10-14T09:30:00Z' }),
    });
    expect(byId(accepted, 'decommission-acknowledge')).toBeNull();
    expect(byId(accepted, 'decommission-acknowledged')).not.toBeNull();
    TestBed.resetTestingModule();

    const running = await render({ latest: run({ status: 'RUNNING', completedAt: null }) });
    expect(byId(running, 'decommission-acknowledge')).toBeNull();
  });

  it('shows the platform’s refusal when the run changed under the operator', async () => {
    const host = await render({ lines: [line('a')] });
    api.acknowledge.mockRejectedValue(new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null));
    const tick = byId(host, 'decommission-accept') as HTMLInputElement;
    tick.checked = true;
    tick.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    await click(host, 'decommission-acknowledge');

    expect(byId(host, 'decommission-error')).not.toBeNull();
    expect(byId(host, 'decommission-acknowledged')).toBeNull();
  });
});
