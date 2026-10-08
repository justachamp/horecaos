import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { en } from '../../core/i18n/messages.en';
import { ru } from '../../core/i18n/messages.ru';
import { TenantsApi } from '../tenants/tenants-api';
import {
  CommerceApi,
  EInvoiceView,
  EInvoicingAccountView,
  EInvoicingClassificationView,
  StatementView,
} from './commerce-api';
import { basisPoints, EInvoicing, percentOf } from './e-invoicing';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};
const UZS = (amountMinor: number) => ({ amountMinor, currency: 'UZS' });

const UNBOUND: EInvoicingAccountView = {
  installationId: 'inst-didox',
  provider: 'DIDOX',
  displayName: 'Didox',
  status: 'DRAFT',
  connected: false,
  secretBound: false,
  missing: ['SECRET_REFERENCE', 'SELLER_TAXPAYER_NUMBER', 'SELLER_NAME'],
  adapterWired: true,
  adapterVersion: 'didox-partner-api-v1',
  environmentCode: 'didox_production',
  baseUrl: 'https://api-partners.didox.uz',
  production: true,
  config: {},
  version: 0,
  updatedBy: 'migration V0516',
  updatedAt: '2026-10-07T09:00:00Z',
};

const FAKTURA: EInvoicingAccountView = {
  ...UNBOUND,
  installationId: 'inst-faktura',
  provider: 'FAKTURA_UZ',
  displayName: 'Faktura.uz',
  environmentCode: 'faktura_uz_production',
  baseUrl: 'https://api.faktura.uz',
};

const CONNECTED: EInvoicingAccountView = {
  ...UNBOUND,
  status: 'ACTIVE',
  connected: true,
  secretBound: true,
  missing: [],
  config: { sellerTaxpayerNumber: '305000001', sellerName: 'HorecaOS MCHJ', locale: 'ru' },
  version: 2,
};

const CLASSIFICATIONS: readonly EInvoicingClassificationView[] = [
  {
    lineKind: 'PLAN',
    itemLabel: 'Подписка на платформу HorecaOS (тарифный план)',
    catalogCode: '10305011001000000',
    catalogName: 'Услуги по предоставлению доступа к программному обеспечению',
    packageCode: '1500002',
    packageName: 'услуга',
    vatRateBp: 1200,
    provisional: true,
    confirmedBy: null,
    confirmedAt: null,
    version: 0,
    updatedBy: 'migration V0516',
    updatedAt: '2026-10-07T09:00:00Z',
  },
  {
    lineKind: 'MODULE',
    itemLabel: 'Подписка (модуль)',
    catalogCode: '10305011001000000',
    catalogName: 'Услуги',
    packageCode: '1500002',
    packageName: 'услуга',
    vatRateBp: 1200,
    provisional: false,
    confirmedBy: 'finance',
    confirmedAt: '2026-10-08T09:00:00Z',
    version: 1,
    updatedBy: 'finance',
    updatedAt: '2026-10-08T09:00:00Z',
  },
];

const ISSUED: StatementView = {
  statementId: 'st-1',
  number: 'S-2026-09-000001',
  periodKey: '2026-09',
  periodStart: '2026-08-31T19:00:00Z',
  periodEnd: '2026-09-30T19:00:00Z',
  status: 'ISSUED',
  total: UZS(95_000_000),
  issuedBy: 'finance',
  issuedAt: '2026-10-01T05:00:00Z',
  issueReason: 'close',
  voidedBy: null,
  voidedAt: null,
  voidReason: null,
  lines: [],
};

const SENT: EInvoiceView = {
  einvoiceId: 'ei-1',
  statementId: 'st-1',
  tenantId: 'tenant-1',
  provider: 'DIDOX',
  documentNumber: 'S-2026-09-000001',
  documentDate: '2026-10-07',
  buyerLegalEntityId: 'le-1',
  buyerName: 'Non uyi MCHJ',
  buyerTaxpayerNumber: '301234567',
  sellerTaxpayerNumber: '305000001',
  net: UZS(95_000_000),
  vat: UZS(11_400_000),
  total: UZS(106_400_000),
  classificationProvisional: true,
  delivery: 'SUBMITTED',
  failureCode: null,
  failureDetail: null,
  operatorDocumentId: 'DOC-1',
  operatorState: 'DRAFT',
  operatorStatus: 'created',
  stateCheckedAt: '2026-10-07T09:00:00Z',
  stateChangedAt: '2026-10-07T09:00:00Z',
  live: true,
  sendReason: 'month closed',
  sentBy: 'finance',
  createdAt: '2026-10-07T09:00:00Z',
  version: 1,
  lines: null,
};

const ENTITY = (id: string, name: string, tin: string) => ({
  id,
  code: id.toUpperCase(),
  legalName: name,
  shortName: null,
  tin,
  vatRegistered: true,
  vatCertificateReference: null,
  taxProfileId: null,
  registeredAddress: null,
  contactPhone: null,
  status: 'ACTIVE' as const,
  version: 1,
});

function problem(code: string, reason: string): ApiError {
  return new ApiError({ status: 422, code, reason });
}

class FakeCommerceApi {
  accounts = vi.fn().mockResolvedValue([UNBOUND, FAKTURA]);
  classifications = vi.fn().mockResolvedValue(CLASSIFICATIONS);
  readonly einvoicingAccounts = (...args: unknown[]) => this.accounts(...args);
  readonly einvoicingClassifications = (...args: unknown[]) => this.classifications(...args);
  readonly saveEInvoicingAccount = vi.fn().mockResolvedValue(CONNECTED);
  readonly activateEInvoicingAccount = vi.fn().mockResolvedValue(CONNECTED);
  readonly suspendEInvoicingAccount = vi.fn().mockResolvedValue(UNBOUND);
  readonly saveEInvoicingClassification = vi.fn().mockResolvedValue(CLASSIFICATIONS[0]);
  readonly listStatements = vi.fn().mockResolvedValue([ISSUED]);
  tenantEInvoices = vi.fn().mockResolvedValue([] as EInvoiceView[]);
  readonly sendEInvoice = vi.fn().mockResolvedValue(SENT);
  readonly refreshEInvoice = vi.fn().mockResolvedValue({ einvoice: SENT, unavailableCode: null });
  readonly eInvoice = vi.fn().mockResolvedValue({
    ...SENT,
    lines: [
      {
        lineNumber: 1,
        name: 'Подписка: BASIC v1',
        classificationCode: '10305011001000000',
        quantity: 1,
        unitPrice: UZS(95_000_000),
        net: UZS(95_000_000),
        vatRateBp: 1200,
        vat: UZS(11_400_000),
        gross: UZS(106_400_000),
      },
    ],
  });
}

describe('EInvoicing', () => {
  let fixture: ComponentFixture<EInvoicing>;
  let api: FakeCommerceApi;

  async function create(
    options: {
      held?: (capability: string) => boolean;
      entities?: unknown[];
      prepare?: (api: FakeCommerceApi) => void;
    } = {},
  ): Promise<void> {
    api = new FakeCommerceApi();
    options.prepare?.(api);
    localStorage.clear();
    sessionStorage.clear();
    await TestBed.configureTestingModule({
      imports: [EInvoicing],
      providers: [
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: CommerceApi, useValue: api },
        {
          provide: TenantsApi,
          useValue: {
            listTenants: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
            getLegalEntities: vi
              .fn()
              .mockResolvedValue(options.entities ?? [ENTITY('le-1', 'Non uyi MCHJ', '301234567')]),
          },
        },
        {
          provide: SessionContextService,
          useValue: { has: options.held ?? (() => true), current: () => ({ subject: 'me' }) },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({ tenantId: 'tenant-1' }) } },
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(EInvoicing);
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

  async function type(selector: string, value: string, event = 'input'): Promise<void> {
    const field = el<HTMLInputElement>(selector);
    field.value = value;
    field.dispatchEvent(new Event(event));
    await settle();
  }

  async function click(selector: string): Promise<void> {
    el<HTMLButtonElement>(selector).click();
    await settle();
  }

  // ----------------------------------------------------------------- accounts

  it('says that neither operator is connected, and what each still needs, instead of hiding the action', async () => {
    await create();

    const didox = el('[data-account="DIDOX"]');
    expect(didox.querySelector('[data-status]')?.textContent).toContain(
      ru['einvoicing.account.status.DRAFT'],
    );
    expect(didox.querySelector('.notConnected')?.textContent).toContain(
      ru['einvoicing.account.needs.SECRET_REFERENCE'],
    );
    expect(didox.querySelector('.notConnected')?.textContent).toContain(
      ru['einvoicing.account.needs.SELLER_TAXPAYER_NUMBER'],
    );
    expect(didox.textContent).toContain(ru['einvoicing.account.secretNone']);
    expect(el('[data-account="FAKTURA_UZ"] .notConnected')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.chip.live[data-status]')).toBeNull();
  });

  it('shows a connected account as connected, with the seller, and no complaint', async () => {
    await create({ prepare: (fake) => fake.accounts.mockResolvedValue([CONNECTED, FAKTURA]) });

    const didox = el('[data-account="DIDOX"]');
    expect(didox.querySelector('[data-status]')?.textContent).toContain(
      ru['einvoicing.account.status.ACTIVE'],
    );
    expect(didox.querySelector('.notConnected')).toBeNull();
    expect(didox.textContent).toContain('HorecaOS MCHJ');
    expect(didox.textContent).toContain(ru['einvoicing.account.secretBound']);
  });

  it('saves an account under the version it read, keeping the reference on file when none is typed', async () => {
    await create();
    await click('[data-account="DIDOX"] .editAccount');
    await type('[name="sellerTaxpayerNumber"]', '305000001');
    await type('[name="sellerName"]', 'HorecaOS MCHJ');
    expect(el<HTMLButtonElement>('.saveAccount').disabled).toBe(true);
    await type('[name="accountReason"]', 'account opened');
    await click('.saveAccount');

    expect(api.saveEInvoicingAccount).toHaveBeenCalledWith('inst-didox', 0, {
      displayName: 'Didox',
      secretReference: null,
      config: { sellerTaxpayerNumber: '305000001', sellerName: 'HorecaOS MCHJ' },
      reason: 'account opened',
    });
  });

  it('sends a typed reference, and a blank one when the reference is to be cleared', async () => {
    await create();
    // After the save the screen reloads, and the account then says a reference is on file.
    api.accounts.mockResolvedValue([CONNECTED, FAKTURA]);
    await click('[data-account="DIDOX"] .editAccount');
    expect(
      el('[name="clearSecret"]'),
      'nothing to remove while no reference is on file',
    ).toBeNull();
    await type('[name="secretReference"]', 'horecaos:production:provider_einvoicing:platform:op-1');
    await type('[name="accountReason"]', 'bind');
    await click('.saveAccount');
    expect(api.saveEInvoicingAccount.mock.calls[0][2].secretReference).toBe(
      'horecaos:production:provider_einvoicing:platform:op-1',
    );

    // A reference is on file now, so the form offers to remove it.
    await click('[data-account="DIDOX"] .editAccount');
    const clear = el<HTMLInputElement>('[name="clearSecret"]');
    clear.checked = true;
    clear.dispatchEvent(new Event('change'));
    await type('[name="accountReason"]', 'unbind');
    await click('.saveAccount');
    expect(api.saveEInvoicingAccount.mock.calls[1][2].secretReference).toBe('');
  });

  it('connects an account only with a reason, sending the version it read, and says what the server said when it is refused', async () => {
    await create();
    await click('[data-account="DIDOX"] .activate');
    expect(el<HTMLButtonElement>('.confirmMove').disabled).toBe(true);
    await type('[name="moveReason"]', 'go live');
    api.activateEInvoicingAccount.mockRejectedValueOnce(
      problem('UNPROCESSABLE_STATE', 'OPERATOR_NOT_CONNECTED'),
    );
    await click('.confirmMove');

    expect(api.activateEInvoicingAccount).toHaveBeenCalledWith('inst-didox', 0, 'go live');
    expect(el('[role="alert"]').textContent).toContain(
      ru['einvoicing.error.OPERATOR_NOT_CONNECTED'],
    );
  });

  it('suspends a connected account', async () => {
    await create({ prepare: (fake) => fake.accounts.mockResolvedValue([CONNECTED, FAKTURA]) });
    await click('[data-account="DIDOX"] .suspend');
    await type('[name="moveReason"]', 'operator outage');
    await click('.confirmMove');

    expect(api.suspendEInvoicingAccount).toHaveBeenCalledWith('inst-didox', 2, 'operator outage');
  });

  it('offers no way to change an account to someone who may only read', async () => {
    await create({ held: (capability) => capability === 'COMMERCIAL_USAGE_READ' });

    expect(el('.editAccount')).toBeNull();
    expect(el('.activate')).toBeNull();
    expect(el('.editClassification')).toBeNull();
    expect(el('.openSend')).toBeNull();
    expect(el('[data-account="DIDOX"]')).not.toBeNull();
  });

  // ---------------------------------------------------------- classifications

  it('flags a classification finance has not confirmed, and shows who confirmed the other', async () => {
    await create();

    expect(el('[data-kind="PLAN"] .provisional').textContent).toContain(
      ru['einvoicing.classification.provisional'],
    );
    expect(el('[data-kind="PLAN"]').textContent).toContain('12 %');
    expect(el('[data-kind="MODULE"] .confirmedChip').textContent).toContain('finance');
    expect(el('[data-kind="MODULE"] .provisional')).toBeNull();
  });

  it('confirms a classification, sending the rate in basis points under the version read', async () => {
    await create();
    await click('[data-kind="PLAN"] .editClassification');
    await type('[name="vatPercent"]', '12,5');
    const confirm = el<HTMLInputElement>('[name="confirmed"]');
    confirm.checked = true;
    confirm.dispatchEvent(new Event('change'));
    await type('[name="classReason"]', 'finance ruling');
    await click('.saveClassification');

    expect(api.saveEInvoicingClassification).toHaveBeenCalledWith('PLAN', 0, {
      itemLabel: CLASSIFICATIONS[0].itemLabel,
      catalogCode: '10305011001000000',
      catalogName: CLASSIFICATIONS[0].catalogName,
      packageCode: '1500002',
      packageName: 'услуга',
      vatRateBp: 1250,
      confirmed: true,
      reason: 'finance ruling',
    });
  });

  it('refuses a VAT rate that is not a percentage between 0 and 100', async () => {
    await create();
    await click('[data-kind="PLAN"] .editClassification');
    await type('[name="classReason"]', 'r');
    for (const bad of ['', 'abc', '101', '-1', '12.345']) {
      await type('[name="vatPercent"]', bad);
      expect(el<HTMLButtonElement>('.saveClassification').disabled, bad).toBe(true);
    }
    await type('[name="vatPercent"]', '0');
    expect(el<HTMLButtonElement>('.saveClassification').disabled).toBe(false);
  });

  it('reads and writes a rate the way a person types it', () => {
    expect(basisPoints('12')).toBe(1200);
    expect(basisPoints('2.5')).toBe(250);
    expect(basisPoints('2,5')).toBe(250);
    expect(basisPoints('100')).toBe(10_000);
    expect(basisPoints('100.01')).toBeNull();
    expect(percentOf(1200)).toBe('12');
    expect(percentOf(250)).toBe('2.5');
  });

  // --------------------------------------------------------------- statements

  it("lists the tenant's issued statements with what became of each send", async () => {
    await create({ prepare: (fake) => fake.tenantEInvoices.mockResolvedValue([SENT]) });

    const statement = el('[data-statement="S-2026-09-000001"]');
    expect(statement.querySelector('[data-delivery="SUBMITTED"]')?.textContent).toContain(
      ru['einvoicing.delivery.SUBMITTED'],
    );
    expect(statement.querySelector('[data-state="DRAFT"]')?.textContent).toContain(
      ru['einvoicing.state.DRAFT'],
    );
    expect(statement.textContent).toContain('DOC-1');
    expect(statement.textContent).toContain(ru['einvoicing.attempt.provisional']);
    expect(
      statement.querySelector('.openSend'),
      'a statement with a live invoice is not sent again',
    ).toBeNull();
  });

  it('warns, before anything is sent, that the chosen operator is not connected', async () => {
    await create();
    await click('.openSend');

    expect(el('.notConnectedSend').textContent).toContain('Didox');
    expect(el('.sendForm').textContent).toContain(ru['einvoicing.send.provisional']);
  });

  it('sends with a reason, the operator and the buyer, and reports what the operator holds', async () => {
    await create({ prepare: (fake) => fake.accounts.mockResolvedValue([CONNECTED, FAKTURA]) });
    await click('.openSend');
    expect(el('.notConnectedSend')).toBeNull();
    expect(el<HTMLButtonElement>('.confirmSend').disabled).toBe(true);
    await type('[name="sendReason"]', 'month closed');
    await click('.confirmSend');

    expect(api.sendEInvoice).toHaveBeenCalledWith('tenant-1', 'st-1', {
      provider: 'DIDOX',
      legalEntityId: 'le-1',
      reason: 'month closed',
    });
    expect(el('[role="status"]').textContent).toContain(ru['einvoicing.state.DRAFT']);
  });

  it("says so when the operator is not connected, in this screen's words and not a generic refusal", async () => {
    await create();
    await click('.openSend');
    await type('[name="sendReason"]', 'month closed');
    api.sendEInvoice.mockRejectedValueOnce(
      problem('UNPROCESSABLE_STATE', 'OPERATOR_NOT_CONNECTED'),
    );
    await click('.confirmSend');

    expect(el('[role="alert"]').textContent).toContain(
      ru['einvoicing.error.OPERATOR_NOT_CONNECTED'],
    );
    expect(el('[role="alert"]').textContent).not.toContain(ru['error.UNPROCESSABLE_STATE']);
  });

  it('makes the person choose the company when the tenant has several, and sends the one chosen', async () => {
    await create({
      prepare: (fake) => fake.accounts.mockResolvedValue([CONNECTED, FAKTURA]),
      entities: [
        ENTITY('le-1', 'Non uyi MCHJ', '301234567'),
        ENTITY('le-2', 'Non uyi Savdo MCHJ', '307654321'),
      ],
    });
    await click('.openSend');
    await type('[name="sendReason"]', 'branch is billed');
    expect(el<HTMLButtonElement>('.confirmSend').disabled).toBe(true);
    await type('[name="legalEntityId"]', 'le-2', 'change');
    await click('.confirmSend');

    expect(api.sendEInvoice).toHaveBeenCalledWith('tenant-1', 'st-1', {
      provider: 'DIDOX',
      legalEntityId: 'le-2',
      reason: 'branch is billed',
    });
  });

  it('tells a send whose answer was lost to be refreshed and not sent again', async () => {
    await create({ prepare: (fake) => fake.accounts.mockResolvedValue([CONNECTED, FAKTURA]) });
    api.sendEInvoice.mockResolvedValueOnce({
      ...SENT,
      delivery: 'UNCERTAIN',
      operatorState: null,
      operatorDocumentId: null,
    });
    await click('.openSend');
    await type('[name="sendReason"]', 'month closed');
    await click('.confirmSend');

    expect(el('[role="status"]').textContent).toContain(ru['einvoicing.send.uncertain']);
  });

  it('refreshes the state of a sent invoice, and says when the operator could not be asked', async () => {
    await create({ prepare: (fake) => fake.tenantEInvoices.mockResolvedValue([SENT]) });
    await click('[data-einvoice="ei-1"] .refresh');
    expect(api.refreshEInvoice).toHaveBeenCalledWith('tenant-1', 'ei-1');
    expect(el('[role="status"]').textContent).toContain(ru['einvoicing.refreshed']);

    api.refreshEInvoice.mockResolvedValueOnce({
      einvoice: SENT,
      unavailableCode: 'PROVIDER_UNAVAILABLE',
    });
    await click('[data-einvoice="ei-1"] .refresh');
    expect(el('[role="status"]').textContent).toContain('PROVIDER_UNAVAILABLE');
  });

  it('offers no refresh for a document the operator has settled', async () => {
    await create({
      prepare: (fake) =>
        fake.tenantEInvoices.mockResolvedValue([
          { ...SENT, operatorState: 'SIGNED', operatorStatus: '2' },
        ]),
    });

    expect(el('[data-einvoice="ei-1"]')).not.toBeNull();
    expect(el('[data-einvoice="ei-1"] .refresh')).toBeNull();
  });

  it('shows the document that was sent, line by line', async () => {
    await create({ prepare: (fake) => fake.tenantEInvoices.mockResolvedValue([SENT]) });
    await click('[data-einvoice="ei-1"] .showDetail');

    expect(api.eInvoice).toHaveBeenCalledWith('tenant-1', 'ei-1');
    expect(el('[data-einvoice="ei-1"] .innerTable').textContent).toContain('Подписка: BASIC v1');
    expect(el('[data-einvoice="ei-1"] .innerTable').textContent).toContain('12 %');
  });

  it('keeps the three catalogues in step for every key the screen asks for', () => {
    for (const key of Object.keys(en).filter((candidate) => candidate.startsWith('einvoicing.'))) {
      expect(ru[key as keyof typeof ru], key).toBeTruthy();
    }
  });
});
