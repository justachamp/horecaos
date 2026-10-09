import { Provider, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { Auth } from '../../../core/auth/auth';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { applyRegionalFormats, resetRegionalFormats } from '../../../core/format/regional-format';
import { I18n } from '../../../core/i18n/i18n';
import { CampaignView, MarketingApi, RecipientCountsView, RecipientView } from '../marketing-api';
import { OffersApi } from '../offers/offers-api';
import { ScenariosApi } from '../scenarios/scenarios-api';
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
    scheduledAt: null,
    haltedReason: null,
    isWired: true,
    notWiredReason: null,
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-01T08:00:00Z',
    version: 1,
    kind: 'BROADCAST',
    controlGroupPercent: null,
    supersedesCampaignId: null,
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

  afterEach(() => resetRegionalFormats());

  async function render(
    subject: string,
    campaignView: CampaignView,
    counts: RecipientCountsView = {
      pending: 0,
      queued: 0,
      deferred: 0,
      refused: 0,
      total: 0,
      refusedByReason: {},
    },
    options: { recipients?: readonly RecipientView[]; providers?: readonly Provider[] } = {},
  ): Promise<void> {
    api = {
      getCampaign: vi.fn().mockResolvedValue(campaignView),
      recipients: vi.fn().mockResolvedValue(options.recipients ?? []),
      listChannels: vi.fn().mockResolvedValue([]),
      recipientCounts: vi.fn().mockResolvedValue(counts),
      estimate: vi.fn(),
      submit: vi.fn(),
      approve: vi.fn(),
      launch: vi.fn(),
      halt: vi.fn(),
      resume: vi.fn(),
      reschedule: vi.fn(),
      exportSnapshot: vi.fn().mockResolvedValue(['acct-1', 'acct-2']),
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
        ...(options.providers ?? []),
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

    expect(host.textContent?.replace(/\s/g, ' ')).toContain('42 000–63 000 UZS');
    expect(host.textContent).toContain('420');
  });

  // Row 10.12: the Formats card says it controls how operators read amounts across the console.
  it('writes the cost estimate, ceiling, spend and reservation the way the brand chose', async () => {
    applyRegionalFormats({ moneySymbolPlacement: 'BEFORE', moneyGrouping: 'COMMA' });
    await render(
      OTHER_ID,
      campaign({ status: 'SENDING', reservedCostMinor: 12_000, spentCostMinor: 8_000 }),
    );
    const text = (fixture.nativeElement as HTMLElement).textContent!.replace(/\s/g, ' ');

    expect(text).toContain('UZS 42,000–63,000');
    expect(text).toContain('UZS 100,000');
    expect(text).toContain('UZS 8,000');
    expect(text).toContain('UZS 12,000');
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

  it('T18: shows an unwired-channel warning on an APPROVED campaign the read model says cannot deliver', async () => {
    await render(OTHER_ID, campaign({ status: 'APPROVED', isWired: false }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="campaign-unwired-warning"]')).not.toBeNull();
  });

  // ----------------------------------------- ADR 0146: the SMS gate, in words, before launch

  describe('a channel that cannot deliver, honestly', () => {
    const launchButton = (host: HTMLElement) =>
      Array.from(host.querySelectorAll('button')).find((b) =>
        b.textContent?.trim().toLowerCase().includes('launch'),
      ) as HTMLButtonElement;

    it('says why an approved SMS campaign cannot be launched: the account is not cleared to carry marketing', async () => {
      await render(
        OTHER_ID,
        campaign({
          status: 'APPROVED',
          channel: 'SMS',
          isWired: false,
          notWiredReason: 'SMS_PURPOSE_NOT_PERMITTED',
        }),
      );
      const host = fixture.nativeElement as HTMLElement;

      const warning = host.querySelector('[data-testid="campaign-unwired-warning"]')!;
      expect(warning.textContent).toContain('not cleared to carry marketing messages');
    });

    it('does not offer a launch it already knows will be refused', async () => {
      await render(
        OTHER_ID,
        campaign({
          status: 'APPROVED',
          isWired: false,
          notWiredReason: 'SMS_PURPOSE_NOT_PERMITTED',
        }),
      );

      expect(launchButton(fixture.nativeElement as HTMLElement).disabled).toBe(true);
    });

    it('still offers launch where the channel can deliver', async () => {
      await render(OTHER_ID, campaign({ status: 'APPROVED', channel: 'MESSAGING_APP' }));

      expect(launchButton(fixture.nativeElement as HTMLElement).disabled).toBe(false);
    });

    it('warns an author before the second signature is spent, while the campaign is still a draft', async () => {
      await render(
        AUTHOR_ID,
        campaign({
          status: 'DRAFT',
          createdBy: AUTHOR_ID,
          isWired: false,
          notWiredReason: 'NO_DELIVERY_ADAPTER',
          channel: 'EMAIL',
        }),
      );
      const host = fixture.nativeElement as HTMLElement;

      expect(host.querySelector('[data-testid="campaign-unwired-warning"]')!.textContent).toContain(
        'mail service sends staff invitations',
      );
    });

    it('turns the server’s own refusal into the same sentence when the wiring changed after the page was read', async () => {
      await render(OTHER_ID, campaign({ status: 'APPROVED', channel: 'SMS' }));
      api['launch'] = vi.fn().mockRejectedValue(
        new ApiError(
          ApiErrorCode.UNPROCESSABLE_STATE,
          422,
          {
            status: 422,
            detail:
              'No ADR 0020 delivery path is wired for SMS for this brand (SMS_PURPOSE_NOT_PERMITTED); this campaign cannot be launched',
          } as never,
          null,
        ),
      );
      const host = fixture.nativeElement as HTMLElement;

      launchButton(host).click();
      await flushMicrotasks();
      fixture.detectChanges();

      expect(host.textContent).toContain('not cleared to carry marketing messages');
    });
  });

  // ---------------------------------------------------------- ADR 0146: delivery evidence

  describe('what the delivery path knows about each message', () => {
    function recipient(overrides: Partial<RecipientView> = {}): RecipientView {
      return {
        customerAccountId: 'acct-1',
        status: 'QUEUED',
        notificationId: 'n-1',
        refusalReason: null,
        deferredUntil: null,
        terminalStatus: null,
        deliveryState: null,
        receiptState: null,
        segmentsBilled: null,
        ...overrides,
      };
    }

    it('says "handed to the operator" for a message the gateway accepted and reported nothing on: neither delivered nor failed', async () => {
      await render(OTHER_ID, campaign({ status: 'SENT' }), undefined, {
        recipients: [recipient({ deliveryState: 'HANDED_TO_OPERATOR', segmentsBilled: 2 })],
      });
      const host = fixture.nativeElement as HTMLElement;

      const row = host.querySelector('[data-testid="recipient-row"]')!;
      expect(row.textContent).toContain('Handed to the operator');
      expect(row.textContent).not.toContain('Delivered');
      expect(row.textContent).toContain('2 segment(s) billed');
    });

    it('says delivered only when the gateway said so, and failed when it said that', async () => {
      await render(OTHER_ID, campaign({ status: 'SENT' }), undefined, {
        recipients: [
          recipient({ customerAccountId: 'a', deliveryState: 'DELIVERED' }),
          recipient({ customerAccountId: 'b', deliveryState: 'FAILED' }),
          recipient({ customerAccountId: 'c', deliveryState: 'NO_RECEIPT' }),
        ],
      });
      const rows = [
        ...(fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid="recipient-row"]'),
      ].map((r) => r.textContent);

      expect(rows[0]).toContain('Delivered');
      expect(rows[1]).toContain('Failed');
      expect(rows[2]).toContain('No receipt came back');
    });

    it('calls it evidence, not a promise', async () => {
      await render(OTHER_ID, campaign({ status: 'SENT' }), undefined, {
        recipients: [recipient({ deliveryState: 'DELIVERED' })],
      });

      expect(
        (fixture.nativeElement as HTMLElement).querySelector(
          '[data-testid="recipient-delivery-hint"]',
        )!.textContent,
      ).toContain('not a promise');
    });

    it('keeps showing what a refused recipient was refused for, in words', async () => {
      await render(OTHER_ID, campaign({ status: 'SENT' }), undefined, {
        recipients: [recipient({ status: 'REFUSED', refusalReason: 'SUPPRESSED' })],
      });

      expect(
        (fixture.nativeElement as HTMLElement).querySelector('[data-testid="recipient-row"]')!
          .textContent,
      ).toContain('Suppressed');
    });
  });

  // ------------------------------------------------------------ ADR 0112: a scenario

  describe('a scenario campaign', () => {
    const scenarioView = {
      campaign: {
        campaignId: 'campaign-1',
        name: 'Win back',
        status: 'DRAFT',
        consentPurpose: 'MARKETING_PROMOTIONS',
        controlGroupPercent: null,
        supersedesCampaignId: null,
        createdAt: '2026-09-01T08:00:00Z',
      },
      steps: [],
      participants: {},
      decisions: {},
    };
    const scenarioProviders = (): Provider[] => [
      {
        provide: ScenariosApi,
        useValue: {
          get: vi.fn().mockResolvedValue(scenarioView),
          decisions: vi.fn().mockResolvedValue([]),
          results: vi.fn(),
          revise: vi.fn(),
        },
      },
      { provide: OffersApi, useValue: { list: vi.fn().mockResolvedValue([]) } },
    ];

    it('shows its steps and decisions beside the lifecycle every campaign has', async () => {
      await render(
        AUTHOR_ID,
        campaign({ kind: 'SCENARIO', status: 'DRAFT', createdBy: AUTHOR_ID }),
        undefined,
        {
          providers: scenarioProviders(),
        },
      );
      const host = fixture.nativeElement as HTMLElement;

      expect(host.querySelector('q-scenario-panel')).not.toBeNull();
      expect(host.querySelector('[data-testid="campaign-kind"]')!.textContent).toContain(
        'Scenario',
      );
      // The lifecycle is the one a broadcast has: estimate first.
      expect(
        Array.from(host.querySelectorAll('button')).some((b) =>
          b.textContent?.toLowerCase().includes('estimate'),
        ),
      ).toBe(true);
    });

    it('shows no scenario panel for a broadcast', async () => {
      await render(AUTHOR_ID, campaign({ kind: 'BROADCAST', createdBy: AUTHOR_ID }));

      expect((fixture.nativeElement as HTMLElement).querySelector('q-scenario-panel')).toBeNull();
    });

    it('opens the editor on this draft from the panel’s "edit steps"', async () => {
      await render(
        AUTHOR_ID,
        campaign({ kind: 'SCENARIO', status: 'DRAFT', createdBy: AUTHOR_ID }),
        undefined,
        {
          providers: scenarioProviders(),
        },
      );
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      (fixture.nativeElement as HTMLElement)
        .querySelector<HTMLButtonElement>('[data-testid="scenario-edit"]')!
        .click();

      expect(navigate).toHaveBeenCalledWith(['/marketing/campaigns'], {
        queryParams: { scenario: 'campaign-1' },
      });
    });

    it('moves to a newly drafted version of the scenario', async () => {
      await render(
        AUTHOR_ID,
        campaign({ kind: 'SCENARIO', status: 'SENDING', createdBy: AUTHOR_ID }),
        undefined,
        {
          providers: scenarioProviders(),
        },
      );
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      (
        fixture.componentInstance as unknown as { onScenarioRevised(id: string): void }
      ).onScenarioRevised('v2');

      expect(navigate).toHaveBeenCalledWith(['/marketing/campaigns', 'v2']);
    });
  });

  it('T18: renders scheduledAt as a fact when a campaign is armed for later', async () => {
    await render(OTHER_ID, campaign({ status: 'SCHEDULED', scheduledAt: '2026-10-01T10:00:00Z' }));
    const host = fixture.nativeElement as HTMLElement;

    expect(
      host.querySelector('[data-testid="campaign-scheduled-at-value"]')?.textContent,
    ).toContain('2026-10-01');
  });

  // ------------------------------------------------- row 6.4: halted scheduled send

  it('6.4: shows no halted banner on a campaign that is armed and waiting, not halted', async () => {
    await render(
      OTHER_ID,
      campaign({ status: 'SCHEDULED', scheduledAt: '2026-10-01T10:00:00Z', haltedReason: null }),
    );
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="campaign-halted-banner"]')).toBeNull();
  });

  it('6.4: surfaces haltedReason and a re-schedule affordance for a disarmed scheduled send', async () => {
    await render(
      OTHER_ID,
      campaign({
        status: 'SCHEDULED',
        scheduledAt: null,
        haltedReason: 'No ADR 0020 delivery path is wired for SMS yet',
      }),
    );
    const host = fixture.nativeElement as HTMLElement;

    const banner = host.querySelector('[data-testid="campaign-halted-banner"]');
    expect(banner).not.toBeNull();
    expect(banner?.textContent).toContain('No ADR 0020 delivery path is wired for SMS yet');
    expect(host.querySelector('[data-testid="reschedule-action"]')).not.toBeNull();
  });

  it('6.4: re-schedules a halted send for a new future moment and reloads the campaign', async () => {
    await render(
      OTHER_ID,
      campaign({
        status: 'SCHEDULED',
        scheduledAt: null,
        haltedReason: 'channel unwired at due moment',
      }),
    );
    api['reschedule'] = vi.fn().mockResolvedValue(undefined);
    api['getCampaign'] = vi
      .fn()
      .mockResolvedValue(
        campaign({ status: 'SCHEDULED', scheduledAt: '2026-10-05T09:00:00Z', haltedReason: null }),
      );
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="reschedule-action"]') as HTMLButtonElement).click();
    fixture.detectChanges();

    const input = host.querySelector('[data-testid="reschedule-at-input"]') as HTMLInputElement;
    input.value = '2026-10-05T09:00';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (host.querySelector('[data-testid="reschedule-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api['reschedule']).toHaveBeenCalledWith(
      SCOPE,
      'campaign-1',
      new Date('2026-10-05T09:00').toISOString(),
    );
    expect(host.querySelector('[data-testid="campaign-halted-banner"]')).toBeNull();
    expect(
      host.querySelector('[data-testid="campaign-scheduled-at-value"]')?.textContent,
    ).toContain('2026-10-05');
  });

  it('6.4: a reschedule refusal is shown inside the dialog rather than silently dropped', async () => {
    await render(
      OTHER_ID,
      campaign({
        status: 'SCHEDULED',
        scheduledAt: null,
        haltedReason: 'channel unwired at due moment',
      }),
    );
    api['reschedule'] = vi
      .fn()
      .mockRejectedValue(new ApiError(ApiErrorCode.RESOURCE_CONFLICT, 409, { status: 409 }, null));
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="reschedule-action"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    const input = host.querySelector('[data-testid="reschedule-at-input"]') as HTMLInputElement;
    input.value = '2026-10-05T09:00';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (host.querySelector('[data-testid="reschedule-confirm"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('.dialog .error-band')).not.toBeNull();
  });

  // --------------------------------------------- row 6.4: history/statistics view

  it('6.4: renders the campaign history/statistics view from recipientCounts, including the refused-by-reason breakdown', async () => {
    await render(OTHER_ID, campaign({ status: 'SENT' }), {
      pending: 1,
      queued: 40,
      deferred: 2,
      refused: 3,
      total: 46,
      refusedByReason: { SUPPRESSED: 2, CONSENT_WITHHELD: 1 },
    });
    const host = fixture.nativeElement as HTMLElement;

    const stats = host.querySelector('[data-testid="campaign-stats"]') as HTMLElement;
    expect(stats.textContent).toContain('40');
    expect(stats.textContent).toContain('46');
    expect(stats.textContent).toContain('Suppressed');
    expect(stats.textContent).toContain('2');
    expect(stats.textContent).toContain('No marketing consent on file');
  });

  // ---------------------------------------- row 6.4: audience-snapshot CSV export

  it("6.4: exports the campaign's own snapshot as pseudonymous account ids", async () => {
    await render(OTHER_ID, campaign({ status: 'SENT', snapshotId: 'snapshot-1' }));
    const host = fixture.nativeElement as HTMLElement;

    const button = host.querySelector(
      '[data-testid="campaign-export-snapshot"]',
    ) as HTMLButtonElement;
    expect(button).not.toBeNull();
    button.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(api['exportSnapshot']).toHaveBeenCalledWith(SCOPE, 'snapshot-1', expect.any(String));
    expect(host.querySelector('[data-testid="campaign-export-count"]')?.textContent).toContain('2');
  });

  it('6.4: offers no export action for a campaign with no snapshot yet', async () => {
    await render(AUTHOR_ID, campaign({ status: 'DRAFT', snapshotId: null }));
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('[data-testid="campaign-export-snapshot"]')).toBeNull();
  });

  it('6.4: a refused export shows the reason rather than silently doing nothing', async () => {
    await render(OTHER_ID, campaign({ status: 'SENT', snapshotId: 'snapshot-1' }));
    api['exportSnapshot'] = vi
      .fn()
      .mockRejectedValue(
        new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, { status: 403 }, null),
      );
    const host = fixture.nativeElement as HTMLElement;

    (host.querySelector('[data-testid="campaign-export-snapshot"]') as HTMLButtonElement).click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(host.querySelector('.stats__export-error')).not.toBeNull();
  });
});
