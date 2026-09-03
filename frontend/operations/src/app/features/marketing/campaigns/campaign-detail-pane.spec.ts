import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { Auth } from '../../../core/auth/auth';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { I18n } from '../../../core/i18n/i18n';
import { CampaignView, MarketingApi } from '../marketing-api';
import { CampaignDetailPane } from './campaign-detail-pane';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

const AUTHOR_ID = '018f9b20-4000-7000-8000-0000000000a1';
const OTHER_ID = '018f9b20-4000-7000-8000-0000000000a2';

function campaign(overrides: Partial<CampaignView> = {}): CampaignView {
  return {
    campaignId: 'campaign-1',
    name: 'Autumn promotion',
    channel: 'SMS',
    consentPurpose: 'MARKETING_PROMOTIONS',
    status: 'IN_REVIEW',
    audienceId: 'audience-1',
    snapshotId: 'snapshot-1',
    templateKey: 'MARKETING_PROMOTION',
    timezone: 'Asia/Tashkent',
    recipientCap: 1000,
    estimatedRecipients: 420,
    estimatedCostLowMinor: 42_000,
    estimatedCostHighMinor: 63_000,
    estimatedDeliverySeconds: 120,
    costCeilingMinor: 100_000,
    reservedCostMinor: 0,
    spentCostMinor: 0,
    reservedRecipients: 0,
    currency: 'UZS',
    benefitOfferId: null,
    loyaltyAccrualRuleId: null,
    createdBy: AUTHOR_ID,
    approvedBy: null,
    blockedCount: 0,
    pausedAt: null,
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-01T08:00:00Z',
    version: 1,
    ...overrides,
  };
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('CampaignDetailPane', () => {
  let fixture: ComponentFixture<CampaignDetailPane>;
  let api: Record<string, ReturnType<typeof vi.fn>>;

  async function render(subject: string, campaignView: CampaignView): Promise<void> {
    api = {
      getCampaign: vi.fn().mockResolvedValue(campaignView),
      recipients: vi.fn().mockResolvedValue([]),
      estimate: vi.fn(),
      submit: vi.fn(),
      approve: vi.fn(),
      launch: vi.fn(),
      halt: vi.fn(),
      resume: vi.fn(),
    };
    await TestBed.configureTestingModule({
      imports: [CampaignDetailPane],
      providers: [
        provideRouter([]),
        { provide: MarketingApi, useValue: api },
        {
          provide: CurrentBrand,
          useValue: {
            scope: signal<BrandScope | null>(SCOPE),
            denied: signal(false),
            ensureLoaded: () => Promise.resolve(),
          },
        },
        { provide: Auth, useValue: { subject: signal(subject) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(CampaignDetailPane);
    fixture.componentRef.setInput('campaignId', campaignView.campaignId);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  }

  it('shows the maker "awaiting a second signature" — no approve action for the author', async () => {
    await render(AUTHOR_ID, campaign({ status: 'IN_REVIEW', createdBy: AUTHOR_ID }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="four-eyes-maker"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="approve-action"]')).toBeNull();
  });

  it('shows the checker the approve action — not the author', async () => {
    await render(OTHER_ID, campaign({ status: 'IN_REVIEW', createdBy: AUTHOR_ID }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="approve-action"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="four-eyes-maker"]')).toBeNull();
  });

  it('renders the estimate as a range, captioned as an estimate rather than a promise', async () => {
    await render(OTHER_ID, campaign({ status: 'DRAFT', estimatedRecipients: 420 }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.textContent).toContain('42000–63000');
    expect(host.textContent).toContain('420');
  });

  it('shows a paused campaign’s block count', async () => {
    await render(OTHER_ID, campaign({ status: 'PAUSED', blockedCount: 7 }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.textContent).toContain('7');
  });

  it('reports what a resume suppressed, from the resume response rather than discarding it', async () => {
    await render(OTHER_ID, campaign({ status: 'PAUSED', blockedCount: 2 }));
    api['resume'] = vi.fn().mockResolvedValue({ suppressedDuringPause: 5 });
    api['getCampaign'] = vi
      .fn()
      .mockResolvedValue(campaign({ status: 'SENDING', blockedCount: 0 }));

    const host = fixture.nativeElement as HTMLElement;
    const resumeButton = Array.from(host.querySelectorAll('button')).find((b) =>
      b.textContent?.trim().toLowerCase().includes('resume'),
    ) as HTMLButtonElement;
    resumeButton.click();
    fixture.detectChanges();

    const reasonInput = host.querySelector('.dialog input') as HTMLInputElement;
    reasonInput.value = 'Investigated; template was not spam';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    const confirmButton = Array.from(host.querySelectorAll('.dialog__actions button')).find(
      (b) => !b.textContent?.trim().toLowerCase().includes('cancel'),
    ) as HTMLButtonElement;
    confirmButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).toContain('5');
  });

  it('names the entitlement when a launch is refused on entitlement grounds', async () => {
    await render(OTHER_ID, campaign({ status: 'APPROVED', channel: 'MESSAGING_APP' }));
    api['launch'] = vi
      .fn()
      .mockRejectedValue(
        new ApiError(
          ApiErrorCode.ENTITLEMENT_REQUIRED,
          403,
          { status: 403, entitlementKey: 'telegram.broadcasts.enabled' },
          null,
        ),
      );

    const host = fixture.nativeElement as HTMLElement;
    const launchButton = Array.from(host.querySelectorAll('button')).find((b) =>
      b.textContent?.trim().toLowerCase().includes('launch'),
    ) as HTMLButtonElement;
    launchButton.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.textContent).toContain('Telegram');
  });
});
