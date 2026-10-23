import { ComponentFixture, TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import { CourierAccessCard } from './courier-access-card';
import { CourierAccessState, CouriersApi } from './couriers-api';

const NONE: CourierAccessState = { state: 'NONE', smsConnected: true };

describe('CourierAccessCard (ADR 0178)', () => {
  let fixture: ComponentFixture<CourierAccessCard>;

  async function render(
    api: Partial<CouriersApi>,
    eligible = true,
  ): Promise<{ host: HTMLElement; invite: ReturnType<typeof vi.fn> }> {
    const invite = (api.invite ?? vi.fn()) as ReturnType<typeof vi.fn>;
    await TestBed.configureTestingModule({
      imports: [CourierAccessCard],
      providers: [{ provide: CouriersApi, useValue: { ...api, invite } }],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CourierAccessCard);
    fixture.componentRef.setInput('tenantId', 't1');
    fixture.componentRef.setInput('courierId', 'c1');
    fixture.componentRef.setInput('eligible', eligible);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-access-state"]')).not.toBeNull();
    });
    return { host, invite };
  }

  it('says where access stands and offers the two ways to move it', async () => {
    const { host } = await render({ accessState: () => Promise.resolve(NONE) });

    expect(host.querySelector('[data-testid="courier-access-state"]')?.textContent).toContain(
      'No invitation sent yet',
    );
    expect(host.querySelector('[data-testid="courier-access-invite"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="courier-access-reset"]')).not.toBeNull();
  });

  it('texts an invite in the console language and then re-reads where it stands', async () => {
    const accessState = vi
      .fn<() => Promise<CourierAccessState>>()
      .mockResolvedValueOnce(NONE)
      .mockResolvedValueOnce({ state: 'QUEUED', kind: 'INVITE', smsConnected: true });
    const invite = vi.fn().mockResolvedValue({
      invitationId: 'i1',
      kind: 'INVITE',
      status: 'QUEUED',
      smsConnected: true,
    });
    const { host } = await render({ accessState, invite });

    host.querySelector<HTMLButtonElement>('[data-testid="courier-access-invite"]')!.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-access-state"]')?.textContent).toContain(
        'Waiting to be texted',
      );
    });

    expect(invite).toHaveBeenCalledWith('t1', 'c1', 'INVITE', 'en');
    expect(host.querySelector('[data-testid="courier-access-notice"]')).not.toBeNull();
  });

  it('sends a reset as a reset, not as an invite', async () => {
    const invite = vi.fn().mockResolvedValue({
      invitationId: 'i2',
      kind: 'RESET',
      status: 'QUEUED',
      smsConnected: true,
    });
    const { host } = await render({
      accessState: () => Promise.resolve({ state: 'ACCEPTED', smsConnected: true }),
      invite,
    });

    host.querySelector<HTMLButtonElement>('[data-testid="courier-access-reset"]')!.click();
    await vi.waitFor(() => expect(invite).toHaveBeenCalledWith('t1', 'c1', 'RESET', 'en'));
  });

  it('says why a text did not go, from a stable code and never a number or a link', async () => {
    const { host } = await render({
      accessState: () =>
        Promise.resolve({
          state: 'FAILED',
          kind: 'INVITE',
          lastErrorCode: 'NO_PHONE_NUMBER',
          smsConnected: true,
        }),
    });

    expect(host.querySelector('[data-testid="courier-access-last-error"]')?.textContent).toContain(
      'no phone number',
    );
    expect(host.textContent).not.toMatch(/\+998|https?:\/\//);
  });

  it('says so when no SMS gateway is connected, so the operator knows the text waits', async () => {
    const { host } = await render({
      accessState: () =>
        Promise.resolve({
          state: 'QUEUED',
          kind: 'INVITE',
          lastErrorCode: 'SMS_NOT_CONNECTED',
          smsConnected: false,
        }),
    });

    expect(host.querySelector('[data-testid="courier-access-sms-off"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="courier-access-last-error"]')?.textContent).toContain(
      'no SMS gateway',
    );
  });

  it('tells a manager who tapped twice to wait, with the seconds the platform gave', async () => {
    const invite = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          'RATE_LIMIT_EXCEEDED',
          429,
          { type: 't', title: 'x', status: 429, retryAfterSeconds: 42 },
          null,
        ),
      );
    const { host } = await render({ accessState: () => Promise.resolve(NONE), invite });

    host.querySelector<HTMLButtonElement>('[data-testid="courier-access-invite"]')!.click();
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="courier-access-error"]')?.textContent).toContain(
        '42',
      );
    });
  });

  it('offers no buttons for a courier who no longer works here', async () => {
    const { host } = await render({ accessState: () => Promise.resolve(NONE) }, false);

    expect(host.querySelector('[data-testid="courier-access-invite"]')).toBeNull();
    expect(host.querySelector('[data-testid="courier-access-reset"]')).toBeNull();
  });
});
