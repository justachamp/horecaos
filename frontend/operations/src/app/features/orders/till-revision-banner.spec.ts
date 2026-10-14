import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../core/api/operations-paths';
import { I18n } from '../../core/i18n/i18n';
import {
  TillRevisionApi,
  TillRevisionNotice,
  TillRevisionSendResult,
  TillRevisionView,
} from './till-revision-api';
import { TillRevisionBanner } from './till-revision-banner';

const SCOPE: LocationScope = { tenantId: 't1', brandId: 'b1', locationId: 'l1' };

function notice(overrides: Partial<TillRevisionNotice> = {}): TillRevisionNotice {
  return {
    noticeId: 'n1',
    status: 'OPEN',
    source: 'CATCHWEIGHT',
    currency: 'UZS',
    tillTotalMinor: 215_000,
    revisedTotalMinor: 208_000,
    deltaMinor: -7_000,
    openedAt: '2026-10-14T10:00:00Z',
    resolvedAt: null,
    attemptCount: 0,
    failureCode: null,
    ...overrides,
  };
}

function view(overrides: Partial<TillRevisionView> = {}): TillRevisionView {
  return { notice: notice(), canTell: false, ...overrides };
}

function mount(api: Partial<TillRevisionApi>) {
  TestBed.configureTestingModule({
    imports: [TillRevisionBanner],
    providers: [{ provide: TillRevisionApi, useValue: api }],
  });
  TestBed.inject(I18n).setLocale('en');
  const fixture = TestBed.createComponent(TillRevisionBanner);
  fixture.componentRef.setInput('scope', SCOPE);
  fixture.componentRef.setInput('orderId', 'o1');
  fixture.componentRef.setInput('orderVersion', 5);
  fixture.detectChanges();
  const host = fixture.nativeElement as HTMLElement;
  const q = (testId: string) => host.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  return { fixture, host, q };
}

async function settle(fixture: ComponentFixture<TillRevisionBanner>, check: () => void) {
  await vi.waitFor(() => {
    fixture.detectChanges();
    check();
  });
}

const flat = (text: string | null | undefined) => (text ?? '').replace(/\s+/g, '');

describe('TillRevisionBanner (ADR 0137, row 4.2c)', () => {
  it('says nothing while the till and the order agree', async () => {
    const forOrder = vi.fn().mockResolvedValue({ notice: null, canTell: false });
    const { fixture, q } = mount({ forOrder });

    await settle(fixture, () => expect(forOrder).toHaveBeenCalled());
    expect(q('till-revision-banner')).toBeNull();
    expect(q('till-revision-unreadable')).toBeNull();
  });

  it('says what the till holds against what the order is worth, and that the order has not failed', async () => {
    const { fixture, q } = mount({ forOrder: vi.fn().mockResolvedValue(view()) });

    await settle(fixture, () => expect(q('till-revision-banner')).not.toBeNull());

    const banner = q('till-revision-banner')!;
    expect(banner.getAttribute('role')).toBe('alert');
    const body = flat(q('till-revision-body')!.textContent);
    expect(body).toContain('215000');
    expect(body).toContain('208000');
    expect(body).toMatch(/7000/);
    expect(banner.textContent).toContain('The till still holds the old total');
  });

  it('offers only "I changed the till" while the platform cannot tell this till', async () => {
    const { fixture, q } = mount({ forOrder: vi.fn().mockResolvedValue(view({ canTell: false })) });

    await settle(fixture, () => expect(q('till-revision-banner')).not.toBeNull());

    expect(q('till-revision-send')).toBeNull();
    expect(q('till-revision-acknowledge')).not.toBeNull();
  });

  it('sends the new total where the till can take one, and says so when it was told', async () => {
    const send = vi.fn().mockResolvedValue({
      outcome: 'TOLD',
      code: null,
      message: null,
      revision: view({ notice: notice({ status: 'TOLD', resolvedAt: '2026-10-14T10:05:00Z' }) }),
    } satisfies TillRevisionSendResult);
    const { fixture, q } = mount({
      forOrder: vi.fn().mockResolvedValue(view({ canTell: true })),
      send,
    });
    await settle(fixture, () => expect(q('till-revision-send')).not.toBeNull());

    q('till-revision-send')!.click();

    await settle(fixture, () => expect(q('till-revision-closed')).not.toBeNull());
    expect(send).toHaveBeenCalledWith(SCOPE, 'o1');
    expect(q('till-revision-closed')!.textContent).toContain('The till was sent the new total');
    expect(q('till-revision-banner')).toBeNull();
  });

  it('keeps the banner open and says why when the till refuses, so a person still corrects it', async () => {
    const send = vi.fn().mockResolvedValue({
      outcome: 'REJECTED',
      code: 'CLOPOS_REFUSED',
      message: 'The till refused the new total.',
      revision: view({
        canTell: true,
        notice: notice({ attemptCount: 1, failureCode: 'CLOPOS_REFUSED' }),
      }),
    } satisfies TillRevisionSendResult);
    const { fixture, q } = mount({
      forOrder: vi.fn().mockResolvedValue(view({ canTell: true })),
      send,
    });
    await settle(fixture, () => expect(q('till-revision-send')).not.toBeNull());

    q('till-revision-send')!.click();

    await settle(fixture, () => expect(q('till-revision-message')).not.toBeNull());
    expect(q('till-revision-message')!.textContent).toContain('refused the new total');
    expect(q('till-revision-failure')).not.toBeNull();
    expect(q('till-revision-banner')).not.toBeNull();
    expect(q('till-revision-closed')).toBeNull();
  });

  it('closes on "I changed the till" and says it was noted', async () => {
    const acknowledge = vi
      .fn()
      .mockResolvedValue(
        view({ notice: notice({ status: 'ACKNOWLEDGED', resolvedAt: '2026-10-14T10:06:00Z' }) }),
      );
    const { fixture, q } = mount({
      forOrder: vi.fn().mockResolvedValue(view()),
      acknowledge,
    });
    await settle(fixture, () => expect(q('till-revision-acknowledge')).not.toBeNull());

    q('till-revision-acknowledge')!.click();

    await settle(fixture, () => expect(q('till-revision-closed')).not.toBeNull());
    expect(acknowledge).toHaveBeenCalledWith(SCOPE, 'o1');
    expect(q('till-revision-closed')!.textContent).toContain('the till was changed by hand');
  });

  it('reads again when the order moves, because a weighing changes the total', async () => {
    const forOrder = vi
      .fn()
      .mockResolvedValueOnce({ notice: null, canTell: false })
      .mockResolvedValue(view());
    const { fixture, q } = mount({ forOrder });
    await settle(fixture, () => expect(forOrder).toHaveBeenCalledTimes(1));
    expect(q('till-revision-banner')).toBeNull();

    fixture.componentRef.setInput('orderVersion', 6);

    await settle(fixture, () => expect(q('till-revision-banner')).not.toBeNull());
    expect(forOrder).toHaveBeenCalledTimes(2);
  });

  it('says it could not check, rather than nothing, when the platform cannot be asked', async () => {
    const { fixture, q } = mount({ forOrder: vi.fn().mockRejectedValue(new Error('offline')) });

    await settle(fixture, () => expect(q('till-revision-unreadable')).not.toBeNull());

    expect(q('till-revision-banner')).toBeNull();
  });

  it('carries nothing over to the next order', async () => {
    const forOrder = vi
      .fn()
      .mockResolvedValueOnce(view())
      .mockResolvedValue({ notice: null, canTell: false });
    const { fixture, q } = mount({ forOrder });
    await settle(fixture, () => expect(q('till-revision-banner')).not.toBeNull());

    fixture.componentRef.setInput('orderId', 'o2');

    await settle(fixture, () => expect(q('till-revision-banner')).toBeNull());
    expect(forOrder).toHaveBeenLastCalledWith(SCOPE, 'o2');
  });
});
