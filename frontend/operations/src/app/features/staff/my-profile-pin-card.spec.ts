import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { CurrentTenant } from '../../core/auth/current-tenant';
import { I18n } from '../../core/i18n/i18n';
import { MyPinView, StaffPinApi } from './pin-api';
import { MyProfilePinCard } from './my-profile-pin-card';

const withPin: MyPinView = {
  status: 'ACTIVE',
  setAt: '2026-10-05T09:30:00Z',
  lockedAt: null,
  pending: null,
  unlocks: [
    {
      sessionId: 's2',
      deviceId: 'd1',
      deviceName: 'Pass tablet',
      openedAt: '2026-10-08T07:00:00Z',
      endedAt: null,
      endedReason: null,
    },
    {
      sessionId: 's1',
      deviceId: 'd2',
      deviceName: 'Grill tablet',
      openedAt: '2026-10-07T07:00:00Z',
      endedAt: '2026-10-07T09:00:00Z',
      endedReason: 'IDLE',
    },
  ],
};

describe('MyProfilePinCard (ADR 0155)', () => {
  let fixture: ComponentFixture<MyProfilePinCard>;
  let api: { mine: ReturnType<typeof vi.fn>; issueMine: ReturnType<typeof vi.fn> };

  async function setUp(mine: MyPinView | Error): Promise<void> {
    api = { mine: vi.fn(), issueMine: vi.fn() };
    if (mine instanceof Error) {
      api.mine.mockRejectedValue(mine);
    } else {
      api.mine.mockResolvedValue(mine);
    }
    await TestBed.configureTestingModule({
      imports: [MyProfilePinCard],
      providers: [
        { provide: StaffPinApi, useValue: api },
        {
          provide: CurrentTenant,
          useValue: { ensureLoaded: () => Promise.resolve(), tenantId: () => 't1' },
        },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(MyProfilePinCard);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  async function flush(): Promise<void> {
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  }

  const byId = (id: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${id}"]`);

  beforeEach(() => TestBed.resetTestingModule());

  it('lists my last unlocks with the tablet, the time and how each ended', async () => {
    await setUp(withPin);

    const list = byId('my-pin-unlocks')?.textContent ?? '';
    expect(list).toContain('Pass tablet');
    expect(list).toContain('Unlocked now');
    expect(list).toContain('Grill tablet');
    expect(list).toContain('07.10.2026 07:00');
    expect(list).toContain('Locked after sitting idle');
    expect(byId('my-pin-status')?.textContent).toContain('05.10.2026 09:30');
  });

  it('names the manager who issued a waiting code, so a code nobody expected is visible', async () => {
    await setUp({
      ...withPin,
      pending: { issuedVia: 'MANAGER', issuedBy: 'Madina Y.', expiresAt: '2026-10-08T12:00:00Z' },
    });

    expect(byId('my-pin-pending')?.textContent).toContain('Madina Y.');
    expect(byId('my-pin-pending')?.textContent).toContain('tell your manager');
  });

  it('asks for my own code, shows it once, and forgets it when closed', async () => {
    await setUp({ ...withPin, status: 'NOT_SET', setAt: null, unlocks: [] });
    api.issueMine.mockReturnValue(
      of({ code: '87654321', expiresAt: '2026-10-08T12:15:00Z', issuedVia: 'SELF' }),
    );

    (byId('my-pin-get-code') as HTMLButtonElement).click();
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    expect(api.issueMine).toHaveBeenCalledWith('t1');
    expect(byId('my-pin-code')?.textContent).toContain('8765 4321');
    expect(byId('my-pin-no-unlocks')).not.toBeNull();

    (byId('my-pin-code-done') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('8765 4321');
  });

  it("says a locked PIN is the manager's to reset and offers no code, since a code cannot unlock it", async () => {
    await setUp({ ...withPin, status: 'LOCKED', lockedAt: '2026-10-08T08:00:00Z' });

    expect(byId('my-pin-status')?.textContent).toContain('Ask your manager');
    expect(byId('my-pin-get-code')).toBeNull();
  });

  it('says an account with no staff record has no PIN to show, not that something broke', async () => {
    await setUp(new ApiError('RESOURCE_NOT_FOUND', 404, null, null));

    expect(byId('my-pin-absent')).not.toBeNull();
    expect(byId('my-pin-error')).toBeNull();
  });
});
