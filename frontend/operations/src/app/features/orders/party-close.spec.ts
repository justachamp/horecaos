import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { ApiError, ApiErrorCode } from '../../core/api/problem-details';
import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { PartyClose } from './party-close';
import { SessionDetailView, SessionView, TableSessionsApi } from './table-sessions-api';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function party(overrides: Partial<SessionView> = {}): SessionView {
  return {
    sessionId: 'ses-1',
    reservationId: null,
    partySize: 3,
    businessDate: '2026-10-05',
    openedAt: '2026-10-05T14:00:00Z',
    status: 'OPEN',
    serviceChargeRateBp: null,
    currency: 'UZS',
    settledTotalMinor: null,
    closedAt: null,
    closeReasonCode: null,
    version: 4,
    tables: [
      { tableId: 'tb-7', code: 'T7', displayName: 'Table 7' },
      { tableId: 'tb-8', code: 'T8', displayName: 'Table 8' },
    ],
    origin: 'STAFF',
    claimExpiresAt: null,
    confirmedAt: null,
    ...overrides,
  };
}

function bill(totalMinor: number, sessionOverrides: Partial<SessionView> = {}): SessionDetailView {
  return {
    session: party({ version: 9, ...sessionOverrides }),
    orderIds: totalMinor > 0 ? ['o1'] : [],
    currency: 'UZS',
    totalMinor,
    roundCount: totalMinor > 0 ? 1 : 0,
    openRoundCount: 0,
  };
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('PartyClose', () => {
  let fixture: ComponentFixture<PartyClose>;
  let api: {
    detail: ReturnType<typeof vi.fn>;
    startSettling: ReturnType<typeof vi.fn>;
    close: ReturnType<typeof vi.fn>;
    forceClose: ReturnType<typeof vi.fn>;
  };
  const closed: string[] = [];
  let staleCount = 0;

  afterEach(() => {
    closed.length = 0;
    staleCount = 0;
  });

  async function render(
    capabilities: readonly Capability[],
    answers: Partial<typeof api> = {},
  ): Promise<HTMLElement> {
    api = {
      detail: vi.fn().mockReturnValue(of(bill(0))),
      startSettling: vi.fn().mockReturnValue(of(party({ status: 'SETTLING', version: 10 }))),
      close: vi.fn().mockReturnValue(of(party({ status: 'CLOSED' }))),
      forceClose: vi.fn().mockReturnValue(of(party({ status: 'FORCE_CLOSED' }))),
      ...answers,
    };
    await TestBed.configureTestingModule({
      imports: [PartyClose],
      providers: [
        { provide: TableSessionsApi, useValue: api },
        {
          provide: SessionCapabilities,
          useValue: { has: (capability: Capability) => capabilities.includes(capability) },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(PartyClose);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('session', party());
    fixture.componentInstance.closed.subscribe((id: string) => closed.push(id));
    fixture.componentInstance.stale.subscribe(() => (staleCount += 1));
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function byId(host: HTMLElement, id: string): HTMLElement | null {
    return host.querySelector<HTMLElement>(`[data-testid="${id}"]`);
  }

  async function click(host: HTMLElement, id: string): Promise<void> {
    byId(host, id)!.click();
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  it('offers nothing to a principal who cannot manage sessions', async () => {
    const host = await render([]);

    expect(byId(host, 'party-close')).toBeNull();
  });

  it('reads the party’s bill before it offers anything, from the version it will then close against', async () => {
    const host = await render(['DINEIN_SESSION_MANAGE']);

    await click(host, 'party-close-open');

    expect(api.detail).toHaveBeenCalledWith(SCOPE, 'ses-1');
  });

  describe('a party with nothing on its bill', () => {
    it('asks once, names the tables, and closes through the state action on the version just read', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE']);

      await click(host, 'party-close-open');
      const dialog = byId(host, 'q-confirm-dialog')!;
      expect(dialog.textContent).toContain('Close table T7 + T8?');
      expect(
        byId(host, 'party-close-choose'),
        'nothing is owed, so there is nothing to choose',
      ).toBeNull();

      await click(host, 'q-confirm-confirm');

      expect(api.close).toHaveBeenCalledTimes(1);
      const [scope, sessionId, reason, version] = api.close.mock.calls[0];
      expect(scope).toEqual(SCOPE);
      expect(sessionId).toBe('ses-1');
      expect(reason).toBeTruthy();
      expect(version, 'the version read with the bill, not the one the list carried').toBe(9);
      expect(api.forceClose).not.toHaveBeenCalled();
      expect(closed).toEqual(['ses-1']);
    });

    it('closes nothing when the operator keeps the table', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE']);

      await click(host, 'party-close-open');
      await click(host, 'q-confirm-cancel');

      expect(api.close).not.toHaveBeenCalled();
      expect(closed).toEqual([]);
      expect(byId(host, 'q-confirm-dialog')).toBeNull();
    });
  });

  describe('a party that owes something', () => {
    it('shows the amount and asks which kind of close this is, without calling anything yet', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE', 'DINEIN_SESSION_FORCE_CLOSE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000))),
      });

      await click(host, 'party-close-open');

      expect(byId(host, 'party-close-bill')!.textContent).toContain('87');
      expect(byId(host, 'party-close-paid')).not.toBeNull();
      expect(byId(host, 'party-close-walkout')).not.toBeNull();
      expect(byId(host, 'q-confirm-dialog')).toBeNull();
      expect(api.close).not.toHaveBeenCalled();
      expect(api.forceClose).not.toHaveBeenCalled();
    });

    it('closes as paid only after the operator confirms an amount they can read', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000))),
      });

      await click(host, 'party-close-open');
      await click(host, 'party-close-paid');
      const dialog = byId(host, 'q-confirm-dialog')!;
      expect(dialog.textContent).toContain('as paid');
      expect(dialog.textContent).toContain('87');
      expect(api.close).not.toHaveBeenCalled();

      await click(host, 'q-confirm-confirm');

      expect(api.close).toHaveBeenCalledTimes(1);
      expect(api.close.mock.calls[0][2]).toContain('guests paid');
      expect(api.forceClose).not.toHaveBeenCalled();
      expect(closed).toEqual(['ses-1']);
    });

    it('offers the walkout only to a principal holding the force-close capability', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000))),
      });

      await click(host, 'party-close-open');

      expect(byId(host, 'party-close-paid')).not.toBeNull();
      expect(
        byId(host, 'party-close-walkout'),
        'a different capability, not a button for everyone',
      ).toBeNull();
    });

    it('closes a walkout through force-closures under the walkout code, after a destructive confirmation', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE', 'DINEIN_SESSION_FORCE_CLOSE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000))),
      });

      await click(host, 'party-close-open');
      await click(host, 'party-close-walkout');
      const dialog = byId(host, 'q-confirm-dialog')!;
      expect(dialog.textContent).toContain('unpaid');
      expect(dialog.textContent).toContain('audit');
      expect(byId(host, 'q-confirm-confirm')!.className).toContain('destructive');

      await click(host, 'q-confirm-confirm');

      expect(api.forceClose).toHaveBeenCalledTimes(1);
      const [scope, sessionId, reasonCode, reason, version] = api.forceClose.mock.calls[0];
      expect(scope).toEqual(SCOPE);
      expect(sessionId).toBe('ses-1');
      expect(reasonCode).toBe('WALKOUT');
      expect(reason).toContain('left without paying');
      expect(version).toBe(9);
      expect(api.close, 'a walkout is not an ordinary close').not.toHaveBeenCalled();
      expect(closed).toEqual(['ses-1']);
    });

    it('goes back from the choice without calling anything', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000))),
      });

      await click(host, 'party-close-open');
      await click(host, 'party-close-back');

      expect(byId(host, 'party-close-open')).not.toBeNull();
      expect(api.close).not.toHaveBeenCalled();
    });
  });

  describe('a party whose guests asked for the bill', () => {
    // ADR 0047's machine has no BILL_REQUESTED -> CLOSED edge: the server answers 400 to it. The
    // way out is through SETTLING, so the console takes both steps, each on the version the one
    // before left.
    it('settles first and then closes, each step on the version the step before left, when the guests paid', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000, { status: 'BILL_REQUESTED' }))),
      });

      await click(host, 'party-close-open');
      await click(host, 'party-close-paid');
      await click(host, 'q-confirm-confirm');

      expect(api.startSettling).toHaveBeenCalledTimes(1);
      const [scope, sessionId, reason, settlingVersion] = api.startSettling.mock.calls[0];
      expect(scope).toEqual(SCOPE);
      expect(sessionId).toBe('ses-1');
      expect(reason).toContain('guests paid');
      expect(settlingVersion, 'the version read with the bill').toBe(9);
      expect(api.close).toHaveBeenCalledTimes(1);
      expect(api.close.mock.calls[0][3], 'the version settling left').toBe(10);
      expect(api.startSettling.mock.invocationCallOrder[0], 'settling comes first').toBeLessThan(
        api.close.mock.invocationCallOrder[0],
      );
      expect(api.forceClose).not.toHaveBeenCalled();
      expect(closed).toEqual(['ses-1']);
    });

    it('takes the same way out for a bill of nothing', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(of(bill(0, { status: 'BILL_REQUESTED' }))),
      });

      await click(host, 'party-close-open');
      await click(host, 'q-confirm-confirm');

      expect(api.startSettling).toHaveBeenCalledTimes(1);
      expect(api.close.mock.calls[0][3]).toBe(10);
      expect(closed).toEqual(['ses-1']);
    });

    it('does not settle a walkout: the force-closure leaves the bill-requested state itself', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE', 'DINEIN_SESSION_FORCE_CLOSE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000, { status: 'BILL_REQUESTED' }))),
      });

      await click(host, 'party-close-open');
      await click(host, 'party-close-walkout');
      await click(host, 'q-confirm-confirm');

      expect(api.startSettling).not.toHaveBeenCalled();
      expect(api.forceClose).toHaveBeenCalledTimes(1);
      expect(api.close).not.toHaveBeenCalled();
      expect(closed).toEqual(['ses-1']);
    });

    it('closes an open party and a settling one in the one step they always had', async () => {
      for (const status of ['OPEN', 'SETTLING']) {
        TestBed.resetTestingModule();
        closed.length = 0;
        const host = await render(['DINEIN_SESSION_MANAGE'], {
          detail: vi.fn().mockReturnValue(of(bill(87_000, { status }))),
        });

        await click(host, 'party-close-open');
        await click(host, 'party-close-paid');
        await click(host, 'q-confirm-confirm');

        expect(api.startSettling, status).not.toHaveBeenCalled();
        expect(api.close, status).toHaveBeenCalledTimes(1);
        expect(closed, status).toEqual(['ses-1']);
      }
    });

    it('closes nothing and tells the screen its list is behind when settling is refused', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000, { status: 'BILL_REQUESTED' }))),
        startSettling: vi
          .fn()
          .mockReturnValue(
            throwError(() => new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null)),
          ),
      });

      await click(host, 'party-close-open');
      await click(host, 'party-close-paid');
      await click(host, 'q-confirm-confirm');

      expect(api.close).not.toHaveBeenCalled();
      expect(closed).toEqual([]);
      expect(staleCount).toBe(1);
      expect(byId(host, 'party-close-error')!.textContent).toContain('Somebody else changed this');
    });

    it('reports a close that failed after settling as not done, and asks the screen to read the room again so it shows a settling party', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(of(bill(87_000, { status: 'BILL_REQUESTED' }))),
        close: vi.fn().mockReturnValue(throwError(() => new Error('offline'))),
      });

      await click(host, 'party-close-open');
      await click(host, 'party-close-paid');
      await click(host, 'q-confirm-confirm');

      expect(api.startSettling).toHaveBeenCalledTimes(1);
      expect(closed).toEqual([]);
      expect(staleCount, 'the party is no longer the one the list shows').toBe(1);
      expect(byId(host, 'party-close-error')).not.toBeNull();
    });
  });

  describe('a bill that changes while the operator is deciding', () => {
    /** The same party with a second round on it: the figure a phone operator's order makes. */
    function withSecondRound(totalMinor: number): SessionDetailView {
      return { ...bill(totalMinor), orderIds: ['o1', 'o2'], roundCount: 2 };
    }

    it('settles nothing the operator has not seen: a round that arrived since is shown, and the close waits for a second yes', async () => {
      const detail = vi
        .fn()
        .mockReturnValueOnce(of(bill(120_000)))
        .mockReturnValue(of(withSecondRound(150_000)));
      const host = await render(['DINEIN_SESSION_MANAGE'], { detail });

      await click(host, 'party-close-open');
      await click(host, 'party-close-paid');
      expect(byId(host, 'q-confirm-dialog')!.textContent).toContain('120');

      await click(host, 'q-confirm-confirm');

      expect(
        api.close,
        'the bill the operator confirmed is not the bill that stands',
      ).not.toHaveBeenCalled();
      expect(api.forceClose).not.toHaveBeenCalled();
      expect(closed).toEqual([]);
      expect(byId(host, 'q-confirm-dialog'), 'the stale confirmation is withdrawn').toBeNull();
      expect(byId(host, 'party-close-bill')!.textContent, 'the new figure is on offer').toContain(
        '150',
      );
      expect(byId(host, 'party-close-error')!.textContent).toContain('150');

      await click(host, 'party-close-paid');
      expect(byId(host, 'q-confirm-dialog')!.textContent).toContain('150');
      await click(host, 'q-confirm-confirm');

      expect(api.close).toHaveBeenCalledTimes(1);
      expect(closed).toEqual(['ses-1']);
    });

    it('does not free a table the operator read as empty once an order has arrived on it', async () => {
      const detail = vi
        .fn()
        .mockReturnValueOnce(of(bill(0)))
        .mockReturnValue(of(withSecondRound(30_000)));
      const host = await render(['DINEIN_SESSION_MANAGE'], { detail });

      await click(host, 'party-close-open');
      expect(byId(host, 'q-confirm-dialog')).not.toBeNull();

      await click(host, 'q-confirm-confirm');

      expect(api.close, 'the nothing-owed close would settle a live order').not.toHaveBeenCalled();
      expect(closed).toEqual([]);
      expect(byId(host, 'party-close-choose'), 'it is a party that owes now').not.toBeNull();
      expect(byId(host, 'party-close-bill')!.textContent).toContain('30');
    });

    it('closes against the version of the bill it just checked, not the one the dialog opened on', async () => {
      const detail = vi
        .fn()
        .mockReturnValueOnce(of(bill(87_000)))
        .mockReturnValue(of({ ...bill(87_000), session: party({ version: 11 }) }));
      const host = await render(['DINEIN_SESSION_MANAGE'], { detail });

      await click(host, 'party-close-open');
      await click(host, 'party-close-paid');
      await click(host, 'q-confirm-confirm');

      expect(
        api.close.mock.calls[0][3],
        'the same figure at a newer version is still that figure',
      ).toBe(11);
    });

    it('closes nothing when the bill cannot be read again at the moment of confirming', async () => {
      const detail = vi
        .fn()
        .mockReturnValueOnce(of(bill(87_000)))
        .mockReturnValue(throwError(() => new Error('offline')));
      const host = await render(['DINEIN_SESSION_MANAGE'], { detail });

      await click(host, 'party-close-open');
      await click(host, 'party-close-paid');
      await click(host, 'q-confirm-confirm');

      expect(api.close).not.toHaveBeenCalled();
      expect(closed).toEqual([]);
      expect(byId(host, 'party-close-error')!.textContent?.trim()).toBeTruthy();
    });

    it('says the list is behind when the party ended while the operator was deciding', async () => {
      const detail = vi
        .fn()
        .mockReturnValueOnce(of(bill(87_000)))
        .mockReturnValue(of(bill(87_000, { status: 'CLOSED' })));
      const host = await render(['DINEIN_SESSION_MANAGE'], { detail });

      await click(host, 'party-close-open');
      await click(host, 'party-close-paid');
      await click(host, 'q-confirm-confirm');

      expect(api.close).not.toHaveBeenCalled();
      expect(staleCount).toBe(1);
    });
  });

  describe('when it cannot be done', () => {
    it('never guesses a table is empty when the bill cannot be read', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(throwError(() => new Error('offline'))),
      });

      await click(host, 'party-close-open');

      expect(byId(host, 'party-close-error')!.textContent?.trim()).toBeTruthy();
      expect(byId(host, 'q-confirm-dialog')).toBeNull();
      expect(api.close).not.toHaveBeenCalled();
    });

    it('tells the screen its list is behind when the party is already over', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        detail: vi.fn().mockReturnValue(of(bill(0, { status: 'CLOSED' }))),
      });

      await click(host, 'party-close-open');

      expect(staleCount).toBe(1);
      expect(byId(host, 'q-confirm-dialog')).toBeNull();
      expect(api.close).not.toHaveBeenCalled();
    });

    it('says someone moved the table first, and asks the screen to read the room again', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        close: vi
          .fn()
          .mockReturnValue(
            throwError(() => new ApiError(ApiErrorCode.STALE_VERSION, 409, null, null)),
          ),
      });

      await click(host, 'party-close-open');
      await click(host, 'q-confirm-confirm');

      expect(byId(host, 'party-close-error')!.textContent).toContain('Somebody else changed this');
      expect(staleCount).toBe(1);
      expect(closed).toEqual([]);
    });

    it('does not report a close that was refused as done', async () => {
      const host = await render(['DINEIN_SESSION_MANAGE'], {
        close: vi.fn().mockReturnValue(throwError(() => new Error('boom'))),
      });

      await click(host, 'party-close-open');
      await click(host, 'q-confirm-confirm');

      expect(closed).toEqual([]);
      expect(byId(host, 'party-close-error')).not.toBeNull();
    });
  });
});
