import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';

import { ReferralComponent } from './referral.component';
import { LangService } from '../../services/lang.service';
import type { MyReferralResponse, RedemptionResponse } from '../../services/referral.service';
import { ReferralService } from '../../services/referral.service';
import { TranslateService } from '../../services/translate.service';
import { HorecaOSApiError } from '../../core/api/problem-details';

class FakeTranslateService {
  get = (key: string): string => key;
  getWithParams = (key: string, params?: Record<string, string | number>): string =>
    params ? `${key}(${JSON.stringify(params)})` : key;
  current = (): Record<string, unknown> => ({});
  setLang = vi.fn();
}

function pending(): RedemptionResponse {
  return {
    status: 'PENDING',
    redeemedAt: new Date('2026-09-01T00:00:00Z').toISOString(),
    expiresAt: new Date('2026-09-15T00:00:00Z').toISOString(),
    rewardedAt: null,
  };
}

class FakeReferralService {
  myReferral = vi.fn(
    async (): Promise<MyReferralResponse> => ({ code: 'ABCD1234', redeemedAs: null }),
  );
  redeem = vi.fn(async (): Promise<RedemptionResponse> => pending());
}

async function setUp(configure: (referrals: FakeReferralService) => void = () => {}) {
  const referrals = new FakeReferralService();
  configure(referrals);

  TestBed.configureTestingModule({
    imports: [ReferralComponent],
    providers: [
      { provide: ReferralService, useValue: referrals },
      { provide: TranslateService, useClass: FakeTranslateService },
      { provide: LangService, useValue: { langId: signal('uz') } },
    ],
  });

  const fixture = TestBed.createComponent(ReferralComponent);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, referrals };
}

describe('ReferralComponent -- loading the caller\'s own code', () => {
  it('shows the code once loaded', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('.code-card__value').textContent).toContain(
      'ABCD1234',
    );
  });

  it('shows a translated load error, with a retry action, on failure', async () => {
    const { fixture, referrals } = await setUp((r) => {
      r.myReferral.mockRejectedValue(new Error('offline'));
    });

    expect(fixture.nativeElement.textContent).toContain('referral.loadError');
    (fixture.nativeElement.querySelector('.btn') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(referrals.myReferral).toHaveBeenCalledTimes(2);
  });

  it('never invents a friend count or a reward amount', async () => {
    const { fixture } = await setUp();

    const text = fixture.nativeElement.textContent as string;
    expect(text).not.toMatch(/\d+\s*friend/i);
    expect(fixture.nativeElement.querySelector('.not-a-count')).not.toBeNull();
  });
});

describe('ReferralComponent -- redeeming a friend\'s code', () => {
  it('shows the redeem form when this account has not redeemed one yet', async () => {
    const { fixture } = await setUp();

    expect(fixture.nativeElement.querySelector('.redeem-form')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.status-card')).toBeNull();
  });

  it('shows the redemption status, not the form, once this account already redeemed one', async () => {
    const { fixture } = await setUp((r) => {
      r.myReferral.mockResolvedValue({ code: 'ABCD1234', redeemedAs: pending() });
    });

    expect(fixture.nativeElement.querySelector('.status-card')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.redeem-form')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('referral.status.pending');
  });

  it('does not submit an empty or whitespace-only code', async () => {
    const { fixture, referrals } = await setUp();

    const button = fixture.nativeElement.querySelector('.redeem-card button.btn') as HTMLButtonElement;
    expect(button.disabled).toBe(true);

    referrals.redeem.mockClear();
    button.click();
    expect(referrals.redeem).not.toHaveBeenCalled();
  });

  it('redeems the typed code and then shows the resulting status', async () => {
    const { fixture, referrals } = await setUp();

    const input = fixture.nativeElement.querySelector('.redeem-form input') as HTMLInputElement;
    input.value = 'FRIEND01';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('.redeem-card button.btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(referrals.redeem).toHaveBeenCalledWith('FRIEND01');
    expect(fixture.nativeElement.querySelector('.status-card')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('referral.redeemThanks');
  });

  it('a refusal surfaces the code-specific message, never the raw ADR 0031 code', async () => {
    const { fixture, referrals } = await setUp();
    referrals.redeem.mockRejectedValue(
      new HorecaOSApiError({ status: 409, code: 'RESOURCE_CONFLICT', detail: 'already redeemed' }),
    );

    const input = fixture.nativeElement.querySelector('.redeem-form input') as HTMLInputElement;
    input.value = 'FRIEND01';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.redeem-card button.btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('referral.alreadyRedeemed');
    expect(fixture.nativeElement.textContent).not.toContain('RESOURCE_CONFLICT');
  });

  it('a not-found code and an ineligible account get distinct messages, both readable', async () => {
    const { fixture, referrals } = await setUp();
    referrals.redeem.mockRejectedValue(
      new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'no such code' }),
    );

    const input = fixture.nativeElement.querySelector('.redeem-form input') as HTMLInputElement;
    input.value = 'NOPE0000';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (fixture.nativeElement.querySelector('.redeem-card button.btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('referral.codeNotFound');
  });
});

describe('ReferralComponent -- sharing the code', () => {
  it('uses the Web Share API with the code inside an honest message when available', async () => {
    const share = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, share });
    const { fixture } = await setUp();

    (fixture.nativeElement.querySelector('.code-card .btn') as HTMLButtonElement).click();
    await fixture.whenStable();

    expect(share).toHaveBeenCalledWith(
      expect.objectContaining({ text: expect.stringContaining('ABCD1234') }),
    );
    vi.unstubAllGlobals();
  });

  it('falls back to the clipboard, and shows the copy happened, when Web Share is unavailable', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('navigator', { ...navigator, share: undefined, clipboard: { writeText } });
    const { fixture } = await setUp();

    (fixture.nativeElement.querySelector('.code-card .btn') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(writeText).toHaveBeenCalledWith(expect.stringContaining('ABCD1234'));
    expect(fixture.nativeElement.textContent).toContain('referral.copied');
    vi.unstubAllGlobals();
  });
});
