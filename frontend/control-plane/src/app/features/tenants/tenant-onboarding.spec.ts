import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ActivationOutcome, OnboardingRunView } from './tenants-api';
import { TenantOnboarding } from './tenant-onboarding';
import { TenantsApi } from './tenants-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const RUN: OnboardingRunView = {
  run: { id: 'run-1', status: 'FAILED', currentPhase: 'READINESS', startedBy: 'owner@test', lastError: null },
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

class FakeTenantsApi {
  readonly currentOnboardingRun = vi.fn<() => Promise<OnboardingRunView | null>>();
  readonly startOnboarding = vi.fn<() => Promise<{ runId: string }>>();
  readonly resumeOnboarding = vi.fn<() => Promise<{ reopenedSteps: number }>>();
  readonly activateOnboarding = vi.fn<() => Promise<ActivationOutcome>>();
}

describe('TenantOnboarding', () => {
  let fixture: ComponentFixture<TenantOnboarding>;
  let api: FakeTenantsApi;

  async function createWith(run: OnboardingRunView | null): Promise<void> {
    api = new FakeTenantsApi();
    api.currentOnboardingRun.mockResolvedValue(run);
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

  it('offers to start a run when the tenant has none yet', async () => {
    await createWith(null);
    expect(fixture.nativeElement.textContent).toContain('Начать подключение');
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

    const [reasonInput, submit] = fixture.nativeElement.querySelectorAll('.panel')[1].querySelectorAll('input, button');
    reasonInput.value = 'payment configured now';
    reasonInput.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    submit.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.resumeOnboarding).toHaveBeenCalledWith('tenant-1', 'run-1', 'payment configured now');
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

    const activatePanel = fixture.nativeElement.querySelectorAll('.panel')[2];
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

  it('shows a translated error when loading the run fails', async () => {
    api = new FakeTenantsApi();
    api.currentOnboardingRun.mockRejectedValue(new ApiError({ status: 403, code: 'INSUFFICIENT_CAPABILITY' }));
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
});
