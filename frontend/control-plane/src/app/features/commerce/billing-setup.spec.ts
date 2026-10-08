import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { ru } from '../../core/i18n/messages.ru';
import { BankDetailsView, CardInstallationView, CommerceApi } from './commerce-api';
import { BillingSetup } from './billing-setup';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const PLACEHOLDER: BankDetailsView = {
  configured: false,
  beneficiary: '[beneficiary: set by HorecaOS finance]',
  bankName: '[bank: set by HorecaOS finance]',
  account: '[account: set by HorecaOS finance]',
  mfo: '[mfo]',
  taxId: '[tax id]',
  version: 0,
  updatedBy: 'system',
  updatedAt: '2026-10-01T00:00:00Z',
  approvedBy: null,
};

const REAL: BankDetailsView = {
  configured: true,
  beneficiary: 'HorecaOS LLC',
  bankName: 'Example Bank',
  account: '20208000100000000001',
  mfo: '00014',
  taxId: '300000001',
  version: 2,
  updatedBy: 'finance-1',
  updatedAt: '2026-10-05T09:00:00Z',
  approvedBy: 'finance-2',
};

const DRAFT_ACCOUNT: CardInstallationView = {
  installationId: 'ci-1',
  providerType: 'FAKE_CARD',
  environmentCode: null,
  displayName: 'Test account',
  status: 'DRAFT',
  secretConfigured: false,
  externalAccountReference: null,
  configuration: {},
  version: 1,
  createdAt: '2026-10-05T09:00:00Z',
  updatedAt: '2026-10-05T09:00:00Z',
};

class FakeCommerceApi {
  bankDetails = vi.fn().mockResolvedValue(PLACEHOLDER);
  proposeBankDetails = vi
    .fn()
    .mockResolvedValue({ status: 'AWAITING_APPROVAL', approvalRequestId: 'ap-1' });
  cardInstallations = vi.fn().mockResolvedValue([DRAFT_ACCOUNT]);
  createCardInstallation = vi.fn().mockResolvedValue({ installationId: 'ci-2' });
  activateCardInstallation = vi.fn().mockResolvedValue({ ...DRAFT_ACCOUNT, status: 'ACTIVE' });
  suspendCardInstallation = vi.fn().mockResolvedValue({ ...DRAFT_ACCOUNT, status: 'SUSPENDED' });
}

describe('BillingSetup', () => {
  let fixture: ComponentFixture<BillingSetup>;
  let api: FakeCommerceApi;

  async function create(held: ReadonlySet<string> | 'all' = 'all'): Promise<void> {
    api = new FakeCommerceApi();
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [BillingSetup],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: CommerceApi, useValue: api },
        {
          provide: SessionContextService,
          useValue: {
            has: (capability: string) => held === 'all' || held.has(capability),
            current: () => ({ subject: 'me' }),
          },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(BillingSetup);
    await settle();
  }

  async function settle(): Promise<void> {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();
  }

  function el<T extends HTMLElement>(selector: string): T {
    return fixture.nativeElement.querySelector(selector) as T;
  }

  async function type(selector: string, value: string): Promise<void> {
    const field = el<HTMLInputElement>(selector);
    field.value = value;
    field.dispatchEvent(new Event('input'));
    await settle();
  }

  async function fillProposal(): Promise<void> {
    await type('.bankForm [name="beneficiary"]', 'HorecaOS LLC');
    await type('.bankForm [name="bankName"]', 'Example Bank');
    await type('.bankForm [name="account"]', '20208000100000000001');
    await type('.bankForm [name="mfo"]', '00014');
    await type('.bankForm [name="taxId"]', '300000001');
    await type('.bankForm [name="reason"]', 'the account was opened');
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
  });

  it('shows placeholder bank details as placeholders, and says no invoice can be issued until they are replaced', async () => {
    await create();
    expect(el('.placeholder').textContent).toContain(ru['billing.bank.placeholder']);
    expect(el('.bankDetails').classList.contains('isPlaceholder')).toBe(true);
    // Who set what is not claimed for a placeholder nobody approved.
    expect(el('.bankWho')).toBeNull();
  });

  it('shows real details with who proposed and who approved them', async () => {
    await create();
    api.bankDetails.mockResolvedValue(REAL);
    await fixture.componentInstance['load']();
    await settle();
    expect(el('.placeholder')).toBeNull();
    expect(el('.bankDetails').textContent).toContain('20208000100000000001');
    expect(el('.bankWho').textContent).toContain('finance-1');
    expect(el('.bankWho').textContent).toContain('finance-2');
  });

  it('proposes new details, and says plainly that nothing changed and that the same proposal is sent again once approved', async () => {
    await create();
    el<HTMLButtonElement>('.openBankDetails').click();
    await settle();
    expect(el<HTMLButtonElement>('.bankForm button[type="submit"]').disabled).toBe(true);
    await fillProposal();
    el<HTMLButtonElement>('.bankForm button[type="submit"]').click();
    await settle();

    expect(api.proposeBankDetails).toHaveBeenCalledWith({
      beneficiary: 'HorecaOS LLC',
      bankName: 'Example Bank',
      account: '20208000100000000001',
      mfo: '00014',
      taxId: '300000001',
      reason: 'the account was opened',
    });
    expect(fixture.nativeElement.textContent).toContain(ru['billing.bank.awaiting']);
    // The form keeps what was typed, so the second step is one click rather than six fields again.
    expect(el<HTMLInputElement>('.bankForm [name="account"]').value).toBe('20208000100000000001');
    expect(el<HTMLAnchorElement>('.approvalsLink').getAttribute('href')).toBe(
      '/compliance/approvals',
    );

    // Approved by a different person; the identical proposal again is what applies it.
    api.proposeBankDetails.mockResolvedValue({ status: 'CHANGED', approvalRequestId: 'ap-1' });
    api.bankDetails.mockResolvedValue(REAL);
    el<HTMLButtonElement>('.bankForm button[type="submit"]').click();
    await settle();
    expect(api.proposeBankDetails).toHaveBeenCalledTimes(2);
    expect(fixture.nativeElement.textContent).toContain(ru['billing.bank.changed']);
    expect(el('.bankForm')).toBeNull();
    expect(el('.bankDetails').textContent).toContain('20208000100000000001');
  });

  it('says a declined proposal changed nothing', async () => {
    await create();
    api.proposeBankDetails.mockResolvedValue({ status: 'DECLINED', approvalRequestId: 'ap-1' });
    el<HTMLButtonElement>('.openBankDetails').click();
    await settle();
    await fillProposal();
    el<HTMLButtonElement>('.bankForm button[type="submit"]').click();
    await settle();
    expect(fixture.nativeElement.textContent).toContain(ru['billing.bank.declined']);
  });

  it('will not submit while any field or the reason is blank', async () => {
    await create();
    el<HTMLButtonElement>('.openBankDetails').click();
    await settle();
    await fillProposal();
    expect(el<HTMLButtonElement>('.bankForm button[type="submit"]').disabled).toBe(false);
    await type('.bankForm [name="mfo"]', '   ');
    expect(el<HTMLButtonElement>('.bankForm button[type="submit"]').disabled).toBe(true);
  });

  it('offers neither proposing nor account management to someone who may only read', async () => {
    await create(new Set(['COMMERCIAL_WALLET_READ']));
    expect(el('.openBankDetails')).toBeNull();
    expect(el('.openInstallation')).toBeNull();
    expect(el('.noAccess').textContent).toContain(ru['billing.installation.noAccess']);
    // The card account is not even asked for: the server would refuse, and a 403 is not a result to show.
    expect(api.cardInstallations).not.toHaveBeenCalled();
    expect(el('.bankDetails')).not.toBeNull();
  });

  it('says every card tenant is collected like an invoice tenant while no card account is active', async () => {
    await create();
    expect(el('.noneActive').textContent).toContain(ru['billing.installation.noneActive']);
    api.cardInstallations.mockResolvedValue([{ ...DRAFT_ACCOUNT, status: 'ACTIVE' }]);
    await fixture.componentInstance['load']();
    await settle();
    expect(el('.noneActive')).toBeNull();
  });

  it('never shows a credential: only whether a secret is named', async () => {
    await create();
    api.cardInstallations.mockResolvedValue([{ ...DRAFT_ACCOUNT, secretConfigured: true }]);
    await fixture.componentInstance['load']();
    await settle();
    const row = el('[data-installation="ci-1"]');
    expect(row.textContent).toContain(ru['billing.installation.credential.named']);
  });

  it('activates a draft account with a reason, sending the version it read', async () => {
    await create();
    el<HTMLButtonElement>('[data-installation="ci-1"] .moveToggle').click();
    await settle();
    expect(el('.confirmMove')).not.toBeNull();
    expect(el<HTMLButtonElement>('.confirmMove').disabled).toBe(true);
    await type('[name="moveReason"]', 'the merchant account is live');
    el<HTMLButtonElement>('.confirmMove').click();
    await settle();
    expect(api.activateCardInstallation).toHaveBeenCalledWith(
      'ci-1',
      1,
      'the merchant account is live',
    );
  });

  it('suspends an active account, and offers nothing but a suspension on it', async () => {
    await create();
    api.cardInstallations.mockResolvedValue([{ ...DRAFT_ACCOUNT, status: 'ACTIVE', version: 3 }]);
    await fixture.componentInstance['load']();
    await settle();
    const toggle = el<HTMLButtonElement>('[data-installation="ci-1"] .moveToggle');
    expect(toggle.getAttribute('data-move')).toBe('suspension');
    toggle.click();
    await settle();
    await type('[name="moveReason"]', 'the provider is down');
    el<HTMLButtonElement>('.confirmMove').click();
    await settle();
    expect(api.suspendCardInstallation).toHaveBeenCalledWith('ci-1', 3, 'the provider is down');
    expect(api.activateCardInstallation).not.toHaveBeenCalled();
  });

  it('tells staff which charges are still waiting and suspends anyway only when they say they know', async () => {
    await create();
    api.cardInstallations.mockResolvedValue([{ ...DRAFT_ACCOUNT, status: 'ACTIVE', version: 3 }]);
    await fixture.componentInstance['load']();
    await settle();
    api.suspendCardInstallation.mockRejectedValueOnce(
      new ApiError({
        status: 409,
        code: 'RESOURCE_CONFLICT',
        reason: 'UNRESOLVED_CARD_CHARGES',
        unresolvedTopUps: 2,
        unresolvedStatementCharges: 1,
      }),
    );
    el<HTMLButtonElement>('[data-installation="ci-1"] .moveToggle').click();
    await settle();
    expect(el('[name="acknowledgeUnresolved"]')).toBeNull();
    await type('[name="moveReason"]', 'the provider is down');
    el<HTMLButtonElement>('.confirmMove').click();
    await settle();

    const alert = fixture.nativeElement.querySelector('[role="alert"]') as HTMLElement;
    expect(alert.textContent).toContain('2');
    expect(alert.textContent).toContain('1');
    expect(alert.textContent).not.toContain(ru['error.UNKNOWN']);
    expect(api.suspendCardInstallation).toHaveBeenLastCalledWith('ci-1', 3, 'the provider is down');
    const acknowledge = el<HTMLInputElement>('[name="acknowledgeUnresolved"]');
    expect(acknowledge).not.toBeNull();

    acknowledge.click();
    await settle();
    el<HTMLButtonElement>('.confirmMove').click();
    await settle();

    expect(api.suspendCardInstallation).toHaveBeenLastCalledWith(
      'ci-1',
      3,
      'the provider is down',
      true,
    );
    expect(el('[name="acknowledgeUnresolved"]')).toBeNull();
  });

  it('declares an account, leaving out the fields that were not filled in', async () => {
    await create();
    el<HTMLButtonElement>('.openInstallation').click();
    await settle();
    await type('.installationForm [name="providerType"]', 'FAKE_CARD');
    await type('.installationForm [name="displayName"]', 'Walk-through account');
    await type('.installationForm [name="reason"]', 'local walk-through');
    el<HTMLButtonElement>('.installationForm button[type="submit"]').click();
    await settle();
    expect(api.createCardInstallation).toHaveBeenCalledWith({
      providerType: 'FAKE_CARD',
      displayName: 'Walk-through account',
      reason: 'local walk-through',
    });
    expect(Object.keys(api.createCardInstallation.mock.calls[0][0]).sort()).toEqual([
      'displayName',
      'providerType',
      'reason',
    ]);
  });

  it('shows a refusal as an alert and keeps the form', async () => {
    await create();
    api.createCardInstallation.mockRejectedValue(
      new ApiError({ status: 422, code: 'UNPROCESSABLE_STATE' }),
    );
    el<HTMLButtonElement>('.openInstallation').click();
    await settle();
    await type('.installationForm [name="providerType"]', 'CLICK');
    await type('.installationForm [name="displayName"]', 'Click');
    await type('.installationForm [name="reason"]', 'trying');
    el<HTMLButtonElement>('.installationForm button[type="submit"]').click();
    await settle();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).not.toBeNull();
    expect(el('.installationForm')).not.toBeNull();
  });
});
