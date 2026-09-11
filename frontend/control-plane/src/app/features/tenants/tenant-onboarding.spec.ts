import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import {
  ActivationOutcome,
  OnboardingRunView,
  OnboardingTemplateSuggestion,
  OnboardingTemplateView,
  OwnerInvitationView,
  ValidationOutcome,
} from './tenants-api';
import { TenantOnboarding } from './tenant-onboarding';
import { TenantsApi } from './tenants-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const RUN: OnboardingRunView = {
  run: {
    id: 'run-1',
    status: 'FAILED',
    currentPhase: 'READINESS',
    startedBy: 'owner@test',
    lastError: null,
  },
  steps: [
    {
      stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
      phase: 'READINESS',
      status: 'FAILED',
      required: true,
      attemptCount: 1,
      errorCode: 'NO_PAYMENT_METHOD',
      detail: null,
      externalReference: null,
    },
  ],
  outstandingRequired: ['PAYMENT_CONFIGURATION_VALIDATE'],
};

const DEFAULT: OnboardingTemplateView = {
  id: 'template-1',
  code: 'default',
  version: 1,
  status: 'ACTIVE',
  description: 'The default onboarding',
  requiredSteps: ['BRANDS_AND_LOCATIONS_VALIDATE', 'PAYMENT_CONFIGURATION_VALIDATE'],
  businessTypes: [],
};

const DARK_KITCHEN: OnboardingTemplateView = {
  ...DEFAULT,
  id: 'template-2',
  code: 'dark-kitchen',
  description: 'Delivery only',
  businessTypes: ['DARK_KITCHEN'],
};

class FakeTenantsApi {
  readonly currentOnboardingRun = vi.fn<() => Promise<OnboardingRunView | null>>();
  readonly startOnboarding = vi.fn<() => Promise<{ runId: string }>>();
  readonly resumeOnboarding = vi.fn<() => Promise<{ reopenedSteps: number }>>();
  readonly activateOnboarding = vi.fn<() => Promise<ActivationOutcome>>();
  readonly validateOnboarding = vi.fn<() => Promise<ValidationOutcome>>();
  readonly cancelOnboarding = vi.fn<(...args: unknown[]) => Promise<void>>();
  readonly suggestedOnboardingTemplate = vi.fn<() => Promise<OnboardingTemplateSuggestion>>();
  readonly onboardingTemplates = vi.fn<() => Promise<OnboardingTemplateView[]>>();
  readonly ownerInvitation = vi
    .fn<() => Promise<OwnerInvitationView | null>>()
    .mockResolvedValue(null);
  readonly resendOwnerInvitation = vi
    .fn<(...args: unknown[]) => Promise<void>>()
    .mockResolvedValue(undefined);
}

describe('TenantOnboarding', () => {
  let fixture: ComponentFixture<TenantOnboarding>;
  let api: FakeTenantsApi;

  async function createWith(
    run: OnboardingRunView | null,
    suggestion: OnboardingTemplateSuggestion = {
      template: DEFAULT,
      businessType: 'CAFE',
      matched: false,
    },
    templates: OnboardingTemplateView[] = [DEFAULT],
    invitation: OwnerInvitationView | null = null,
  ): Promise<void> {
    api = new FakeTenantsApi();
    api.currentOnboardingRun.mockResolvedValue(run);
    api.suggestedOnboardingTemplate.mockResolvedValue(suggestion);
    api.onboardingTemplates.mockResolvedValue(templates);
    api.ownerInvitation.mockResolvedValue(invitation);
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [TenantOnboarding],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: TenantsApi, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ tenantId: 'tenant-1' }) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TenantOnboarding);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  /** A panel found by its heading, so adding a panel never reshuffles the rest. */
  function panel(title: string): HTMLElement {
    const found = Array.from(
      fixture.nativeElement.querySelectorAll('.panel') as NodeListOf<HTMLElement>,
    ).find((section) => section.querySelector('h2')?.textContent?.trim() === title);
    if (found === undefined) {
      throw new Error(`No panel titled ${title}`);
    }
    return found;
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  it('offers to start a run when the tenant has none yet', async () => {
    await createWith(null);
    expect(fixture.nativeElement.textContent).toContain('Начать подключение');
  });

  it("pre-selects the template named for the tenant's business type, and starts under the one chosen", async () => {
    await createWith(
      null,
      { template: DARK_KITCHEN, businessType: 'DARK_KITCHEN', matched: true },
      [DEFAULT, DARK_KITCHEN],
    );
    api.startOnboarding.mockResolvedValue({ runId: 'run-2' });
    api.currentOnboardingRun.mockResolvedValue(RUN);
    await settle();

    const start = panel(ru['onboarding.start.title']);
    expect(start.querySelector('.templateSuggestion')?.textContent).toContain('dark-kitchen');
    const select = start.querySelector('select[name="templateId"]') as HTMLSelectElement;
    expect(select.value).toBe('template-2');

    (start.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(api.startOnboarding).toHaveBeenLastCalledWith(
      'tenant-1',
      undefined,
      undefined,
      'template-2',
      'ru',
      true,
    );
  });

  it('lets the operator choose another template, and says when the default is only a fallback', async () => {
    await createWith(null, { template: DEFAULT, businessType: 'CAFE', matched: false }, [
      DEFAULT,
      DARK_KITCHEN,
    ]);
    api.startOnboarding.mockResolvedValue({ runId: 'run-2' });
    api.currentOnboardingRun.mockResolvedValue(RUN);
    await settle();

    const start = panel(ru['onboarding.start.title']);
    expect(start.querySelector('.templateSuggestion')?.textContent).toContain(
      ru['businessTypes.type.CAFE'],
    );
    const select = start.querySelector('select[name="templateId"]') as HTMLSelectElement;
    select.value = 'template-2';
    select.dispatchEvent(new Event('change'));
    await settle();

    (start.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(api.startOnboarding).toHaveBeenLastCalledWith(
      'tenant-1',
      undefined,
      undefined,
      'template-2',
      'ru',
      true,
    );
  });

  it('shows the owner invitation waiting for mail, and resends it with a reason in another language', async () => {
    const waiting: OwnerInvitationView = {
      state: 'QUEUED',
      recipient: null,
      emailMasked: 'd***a@example.uz',
      locale: 'ru',
      attempts: 0,
      lastErrorCode: 'MAIL_NOT_CONFIGURED',
      queuedAt: '2026-09-11T04:00:00Z',
      sentAt: null,
      openedAt: null,
      acceptedAt: null,
      expiresAt: null,
      timeline: [],
    };
    await createWith(RUN, undefined, undefined, waiting);
    await settle();

    const invitation = panel(ru['onboarding.invitation.title']);
    expect(invitation.textContent).toContain(ru['onboarding.invitation.state.QUEUED']);
    expect(invitation.textContent).toContain('d***a@example.uz');
    expect(invitation.textContent).toContain(ru['onboarding.invitation.hint.MAIL_NOT_CONFIGURED']);

    const reason = invitation.querySelector('input[name="resendReason"]') as HTMLInputElement;
    reason.value = 'the owner asked for Uzbek';
    reason.dispatchEvent(new Event('input'));
    const language = invitation.querySelector('select[name="resendLocale"]') as HTMLSelectElement;
    language.value = 'uz';
    language.dispatchEvent(new Event('change'));
    await settle();
    (invitation.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.resendOwnerInvitation).toHaveBeenCalledWith(
      'tenant-1',
      'the owner asked for Uzbek',
      'uz',
    );
    expect(fixture.nativeElement.textContent).toContain(ru['onboarding.invitation.resent']);
  });

  it('offers a first invitation when the owner was linked before invitations existed', async () => {
    const linked: OnboardingRunView = {
      ...RUN,
      steps: [
        {
          stepKey: 'TENANT_OWNER_LINK_OR_INVITE',
          phase: 'PROVISIONING',
          status: 'COMPLETED',
          required: true,
          attemptCount: 1,
          errorCode: null,
          detail: null,
          externalReference: 'owner-subject',
        },
        ...RUN.steps,
      ],
    };
    await createWith(linked);
    await settle();

    const invitation = panel(ru['onboarding.invitation.title']);
    expect(invitation.textContent).toContain(ru['onboarding.invitation.none']);
    const reason = invitation.querySelector('input[name="resendReason"]') as HTMLInputElement;
    reason.value = 'onboarded before invitations';
    reason.dispatchEvent(new Event('input'));
    await settle();
    (invitation.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.resendOwnerInvitation).toHaveBeenCalledWith(
      'tenant-1',
      'onboarded before invitations',
      'ru',
    );
  });

  it('offers no resend once the owner has accepted', async () => {
    await createWith(RUN, undefined, undefined, {
      state: 'ACCEPTED',
      recipient: null,
      emailMasked: 'd***a@example.uz',
      locale: 'ru',
      attempts: 1,
      lastErrorCode: null,
      queuedAt: '2026-09-11T04:00:00Z',
      sentAt: '2026-09-11T04:01:00Z',
      openedAt: '2026-09-11T05:00:00Z',
      acceptedAt: '2026-09-11T05:02:00Z',
      expiresAt: '2026-09-14T04:01:00Z',
      timeline: [],
    });
    await settle();

    const invitation = panel(ru['onboarding.invitation.title']);
    expect(invitation.textContent).toContain(ru['onboarding.invitation.state.ACCEPTED']);
    expect(invitation.querySelector('form')).toBeNull();
  });

  it('shows the whole address when the server sends one, and the invitation history under it', async () => {
    await createWith(RUN, undefined, undefined, {
      state: 'SENT',
      // The server sends this only to a caller holding TENANT_ONBOARDING_MANAGE
      // here, and records an ADR 0029 reveal when it does (ADR 0100).
      recipient: 'dilnoza.karimova@example.uz',
      emailMasked: 'd***a@example.uz',
      locale: 'ru',
      attempts: 2,
      lastErrorCode: null,
      queuedAt: '2026-09-11T04:00:00Z',
      sentAt: '2026-09-11T04:05:00Z',
      openedAt: null,
      acceptedAt: null,
      expiresAt: '2026-09-14T04:05:00Z',
      timeline: [
        {
          type: 'QUEUED',
          attempt: 0,
          locale: 'ru',
          outcomeCode: null,
          actorType: 'SYSTEM_JOB',
          actor: 'onboarding-run:run-1',
          reason: null,
          occurredAt: '2026-09-11T04:00:00Z',
        },
        {
          type: 'SEND_DEFERRED',
          attempt: 1,
          locale: 'ru',
          outcomeCode: 'SMTP_UNAVAILABLE',
          actorType: 'SYSTEM_JOB',
          actor: 'owner-invitation-relay',
          reason: null,
          occurredAt: '2026-09-11T04:01:00Z',
        },
        {
          type: 'RESENT',
          attempt: 0,
          locale: 'ru',
          outcomeCode: 'QUEUED',
          actorType: 'USER',
          actor: 'operator-subject',
          reason: 'the owner never got the first one',
          occurredAt: '2026-09-11T04:04:00Z',
        },
        {
          type: 'SENT',
          attempt: 2,
          locale: 'ru',
          outcomeCode: null,
          actorType: 'SYSTEM_JOB',
          actor: 'owner-invitation-relay',
          reason: null,
          occurredAt: '2026-09-11T04:05:00Z',
        },
      ],
    });
    await settle();

    const invitation = panel(ru['onboarding.invitation.title']);
    const recipient = invitation.querySelector('.recipient') as HTMLElement;
    expect(recipient.textContent).toContain('dilnoza.karimova@example.uz');
    expect(recipient.textContent).not.toContain('d***a');
    expect(recipient.dataset['whole']).toBe('true');

    const events = invitation.querySelectorAll('.timeline li');
    expect(events).toHaveLength(4);
    expect(events[1].textContent).toContain('SMTP_UNAVAILABLE');
    expect(events[2].textContent).toContain('the owner never got the first one');
    expect(events[2].textContent).toContain('operator-subject');
    expect((events[3] as HTMLElement).dataset['event']).toBe('SENT');
  });

  it('dims the mask when the server would not send the address, and says so when there is no history', async () => {
    await createWith(RUN, undefined, undefined, {
      state: 'SENT',
      recipient: null,
      emailMasked: 'd***a@example.uz',
      locale: 'ru',
      attempts: 1,
      lastErrorCode: null,
      queuedAt: '2026-09-11T04:00:00Z',
      sentAt: '2026-09-11T04:01:00Z',
      openedAt: null,
      acceptedAt: null,
      expiresAt: '2026-09-14T04:01:00Z',
      timeline: [],
    });
    await settle();

    const invitation = panel(ru['onboarding.invitation.title']);
    const recipient = invitation.querySelector('.recipient') as HTMLElement;
    expect(recipient.textContent).toContain('d***a@example.uz');
    expect(recipient.dataset['whole']).toBe('false');
    expect(invitation.querySelector('.timeline')).toBeNull();
    expect(invitation.textContent).toContain(ru['onboarding.invitation.timelineEmpty']);
  });

  it('renders every step with its status', async () => {
    await createWith(RUN);
    expect(fixture.nativeElement.textContent).toContain('PAYMENT_CONFIGURATION_VALIDATE');
    expect(fixture.nativeElement.querySelector('.pill-FAILED')).not.toBeNull();
  });

  it('resumes the run and reports how many steps reopened', async () => {
    await createWith(RUN);
    api.resumeOnboarding.mockResolvedValue({ reopenedSteps: 2 });
    api.currentOnboardingRun.mockResolvedValue(RUN);

    const [reasonInput, submit] = panel(ru['onboarding.resume.title']).querySelectorAll(
      'input, button',
    ) as unknown as [HTMLInputElement, HTMLButtonElement];
    reasonInput.value = 'payment configured now';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    submit.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.resumeOnboarding).toHaveBeenCalledWith(
      'tenant-1',
      'run-1',
      'payment configured now',
    );
    expect(fixture.nativeElement.textContent).toContain('Открыто заново шагов: 2');
  });

  it('shows the awaiting-approval outcome distinctly from a plain activation', async () => {
    await createWith(RUN);
    api.activateOnboarding.mockResolvedValue({
      activated: false,
      outcome: 'AWAITING_APPROVAL',
      outstandingRequired: [],
      approvalRequestId: 'req-42',
    });
    api.currentOnboardingRun.mockResolvedValue(RUN);

    const activatePanel = panel(ru['onboarding.activate.title']);
    const reasonInput = activatePanel.querySelector('input') as HTMLInputElement;
    reasonInput.value = 'ready to go live';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (activatePanel.querySelector('button') as HTMLButtonElement).click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Ожидает вторую подпись');
    expect(fixture.nativeElement.textContent).toContain('req-42');
  });

  it('names a step in the operator’s language and says what to do about its failure', async () => {
    await createWith({
      ...RUN,
      steps: [
        {
          ...RUN.steps[0],
          errorCode: 'NO_LEGAL_ENTITY',
          detail: 'Location CHILONZOR has no active legal entity assigned',
        },
      ],
    });

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain(ru['onboarding.step.PAYMENT_CONFIGURATION_VALIDATE']);
    expect(text).toContain(ru['onboarding.hint.NO_LEGAL_ENTITY']);
    expect(text).toContain('Location CHILONZOR has no active legal entity assigned');
    const link = fixture.nativeElement.querySelector('a.hintLink') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/tenants/tenant-1/legal-entities');
  });

  it('checks the tenant now without touching the run, and lists what fails', async () => {
    await createWith(RUN);
    api.validateOnboarding.mockResolvedValue({
      allPassed: false,
      checks: [
        { stepKey: 'BRANDS_AND_LOCATIONS_VALIDATE', passed: true, errorCode: null, detail: null },
        {
          stepKey: 'PAYMENT_CONFIGURATION_VALIDATE',
          passed: false,
          errorCode: 'NO_MERCHANT_BINDING',
          detail: null,
        },
      ],
    });

    (panel(ru['onboarding.validate.title']).querySelector('button') as HTMLButtonElement).click();
    await settle();

    expect(api.validateOnboarding).toHaveBeenCalledWith('tenant-1', 'run-1');
    const checks = panel(ru['onboarding.validate.title']).textContent as string;
    expect(checks).toContain(ru['onboarding.validate.passed']);
    expect(checks).toContain(ru['onboarding.hint.NO_MERCHANT_BINDING']);
  });

  it('cancels a run in flight with a reason, and then offers a fresh start', async () => {
    const inFlight: OnboardingRunView = { ...RUN, run: { ...RUN.run, status: 'PROVISIONING' } };
    await createWith(inFlight);
    expect(() => panel(ru['onboarding.start.title'])).toThrow();

    api.cancelOnboarding.mockResolvedValue();
    api.currentOnboardingRun.mockResolvedValue({
      ...inFlight,
      run: { ...inFlight.run, status: 'CANCELLED' },
    });
    const cancelPanel = panel(ru['onboarding.cancel.title']);
    const reason = cancelPanel.querySelector('input') as HTMLInputElement;
    reason.value = 'started for the wrong tenant';
    reason.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (cancelPanel.querySelector('button') as HTMLButtonElement).click();
    await settle();

    expect(api.cancelOnboarding).toHaveBeenCalledWith(
      'tenant-1',
      'run-1',
      'started for the wrong tenant',
    );
    expect(panel(ru['onboarding.start.title']).textContent).toContain('default');
    expect(() => panel(ru['onboarding.cancel.title'])).toThrow();
  });

  it('shows a translated error when loading the run fails', async () => {
    api = new FakeTenantsApi();
    api.currentOnboardingRun.mockRejectedValue(
      new ApiError({ status: 403, code: 'INSUFFICIENT_CAPABILITY' }),
    );
    localStorage.clear();

    await TestBed.configureTestingModule({
      imports: [TenantOnboarding],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: TenantsApi, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ tenantId: 'tenant-1' }) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(TenantOnboarding);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('У вас нет права');
  });
  it('asks for a sample menu by default, and says so in the operator\u2019s language', async () => {
    await createWith(null);
    api.startOnboarding.mockResolvedValue({ runId: 'run-2' });
    api.currentOnboardingRun.mockResolvedValue(RUN);
    await settle();

    const start = panel(ru['onboarding.start.title']);
    const checkbox = start.querySelector('input[name="sampleMenu"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(true);
    expect(start.textContent).toContain(ru['onboarding.start.sampleMenu']);

    (start.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.startOnboarding).toHaveBeenLastCalledWith(
      'tenant-1',
      undefined,
      undefined,
      'template-1',
      'ru',
      true,
    );
  });

  it('does not ask for a sample menu when this is a restart rather than a first run', async () => {
    // RUN is FAILED, so the start panel is showing over an ended run. A tenant
    // on its second run has usually been authoring in between, and the server's
    // own decline only sees a *published* menu -- a draft is invisible to it. So
    // the box is off unless the operator deliberately ticks it.
    await createWith(RUN);
    api.startOnboarding.mockResolvedValue({ runId: 'run-2' });
    await settle();

    const start = panel(ru['onboarding.start.title']);
    const checkbox = start.querySelector('input[name="sampleMenu"]') as HTMLInputElement;
    expect(checkbox.checked).toBe(false);

    (start.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    expect(api.startOnboarding).toHaveBeenLastCalledWith(
      'tenant-1',
      undefined,
      undefined,
      'template-1',
      'ru',
      false,
    );
  });

  it('starts without a sample menu when the operator clears the box', async () => {
    await createWith(null);
    api.startOnboarding.mockResolvedValue({ runId: 'run-2' });
    api.currentOnboardingRun.mockResolvedValue(RUN);
    await settle();

    const start = panel(ru['onboarding.start.title']);
    const checkbox = start.querySelector('input[name="sampleMenu"]') as HTMLInputElement;
    checkbox.checked = false;
    checkbox.dispatchEvent(new Event('change'));
    await settle();

    (start.querySelector('button[type="submit"]') as HTMLButtonElement).click();
    await settle();

    // Sent as an explicit false rather than omitted: the server reads an absent
    // field as a no either way, but a caller that means no should say so.
    expect(api.startOnboarding).toHaveBeenLastCalledWith(
      'tenant-1',
      undefined,
      undefined,
      'template-1',
      'ru',
      false,
    );
  });

  it('shows the sample menu step like any other, and explains a skipped one', async () => {
    await createWith({
      run: {
        id: 'run-3',
        status: 'IN_PROGRESS',
        currentPhase: 'CONFIGURING',
        startedBy: 'admin@test',
        lastError: null,
      },
      steps: [
        {
          stepKey: 'SAMPLE_MENU_PUBLISH',
          phase: 'CONFIGURING',
          status: 'SKIPPED',
          required: false,
          attemptCount: 0,
          errorCode: 'NOT_REQUESTED',
          detail: null,
          externalReference: null,
        },
      ],
      outstandingRequired: [],
    });

    // Named, not left as a raw enum key, and the hint says what happened.
    expect(fixture.nativeElement.textContent).toContain(ru['onboarding.step.SAMPLE_MENU_PUBLISH']);
    expect(fixture.nativeElement.textContent).toContain(ru['onboarding.hint.NOT_REQUESTED']);

    // detail: null is what the server really writes for a declined row -- it
    // writes the code and nothing else, precisely so a Russian operator is not
    // shown the translated hint followed by an untranslated English copy of it.
    // The raw caption falls back to the code, the way every unhinted code reads.
    expect(fixture.nativeElement.textContent).toContain('NOT_REQUESTED');
    expect(fixture.nativeElement.textContent).not.toContain(
      'No sample menu was asked for when this run was started',
    );
  });

  // Every way the sample menu step can refuse, and the hint is the only place
  // the console says what to do about it. A code wired into HINTS but never
  // rendered is indistinguishable from one missing: key parity across the
  // catalogues proves the string exists, not that an operator ever sees it.
  const SAMPLE_REFUSALS = [
    { errorCode: 'SAMPLE_MENU_REJECTED', detail: 'VARIANT_HAS_NO_ACTIVE_PRICE' },
    { errorCode: 'SAMPLE_MENU_UNSUPPORTED_CURRENCY', detail: 'KZT' },
    { errorCode: 'SAMPLE_PRICING_REFUSED', detail: 'PRICE_BOOK_PRIORITY_CONFLICT' },
  ] as const;

  for (const { errorCode, detail } of SAMPLE_REFUSALS) {
    it(`explains a sample menu that failed ${errorCode}`, async () => {
      await createWith({
        run: {
          id: 'run-4',
          status: 'FAILED',
          currentPhase: 'CONFIGURING',
          startedBy: 'admin@test',
          lastError: null,
        },
        steps: [
          {
            stepKey: 'SAMPLE_MENU_PUBLISH',
            phase: 'CONFIGURING',
            status: 'FAILED',
            required: false,
            attemptCount: 1,
            errorCode,
            detail,
            externalReference: null,
          },
        ],
        outstandingRequired: [],
      });

      expect(fixture.nativeElement.textContent).toContain(ru[`onboarding.hint.${errorCode}`]);
      // The server's own detail is still shown, because it names the specifics.
      expect(fixture.nativeElement.textContent).toContain(detail);
    });
  }
});
