import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { Versioned } from '../../core/api/aggregate-version';
import { LocationScope } from '../../core/api/operations-paths';
import { ApiError } from '../../core/api/problem-details';
import { Capability, SessionCapabilities } from '../../core/auth/session-capabilities';
import { I18n } from '../../core/i18n/i18n';
import { addressEditorFakes } from './address-editor-fakes.testing';
import { CustomerDataRequests } from './customer-data-requests';
import {
  ContactSummary,
  CustomerCorrection,
  CustomerProfile,
  CustomersApi,
  ExportRequest,
} from './customers-api';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const PROFILE: Versioned<CustomerProfile> = {
  value: {
    id: 'customer-1',
    status: 'ACTIVE',
    displayName: 'Karim Yoldosev',
    preferredLocale: 'ru',
    preferredTimezone: 'Asia/Tashkent',
    createdAt: '2026-08-20T09:00:00Z',
    version: 7,
    hasDateOfBirth: false,
    contactSummaries: [],
  },
  version: 7,
};

const CONTACT: ContactSummary = {
  id: 'contact-1',
  type: 'PHONE',
  verificationStatus: 'VERIFIED',
  isPrimary: true,
};

function request(overrides: Partial<ExportRequest> = {}): ExportRequest {
  return {
    id: 'export-1',
    customerAccountId: 'customer-1',
    status: 'PENDING',
    requestedVia: 'STOREFRONT',
    requestedAt: '2026-10-01T10:00:00Z',
    responseDueAt: '2026-10-31T10:00:00Z',
    producedAt: null,
    producedByActorId: null,
    bundleExpiresAt: null,
    bundleAvailable: false,
    cancelledAt: null,
    manifest: null,
    ...overrides,
  };
}

function correction(overrides: Partial<CustomerCorrection> = {}): CustomerCorrection {
  return {
    id: 'correction-1',
    subject: 'DISPLAY_NAME',
    targetId: null,
    evidenceReference: 'TICKET-4711',
    correctedByActorType: 'USER',
    correctedByActorId: 'support-1',
    correctedAt: '2026-10-02T10:00:00Z',
    ...overrides,
  };
}

function capabilities(held: readonly Capability[]): { has: (capability: Capability) => boolean } {
  return { has: (capability) => held.includes(capability) };
}

const EVERYTHING: readonly Capability[] = ['CUSTOMER_MANAGE', 'CUSTOMER_PII_EXPORT'];

describe('CustomerDataRequests', () => {
  let api: Record<string, ReturnType<typeof vi.fn>>;

  async function mount(
    held: readonly Capability[] = EVERYTHING,
  ): Promise<{
    fixture: ComponentFixture<CustomerDataRequests>;
    host: HTMLElement;
    corrected: () => number;
  }> {
    await TestBed.configureTestingModule({
      imports: [CustomerDataRequests],
      providers: [
        provideRouter([]),
        ...addressEditorFakes().providers,
        { provide: CustomersApi, useValue: api },
        { provide: SessionCapabilities, useValue: capabilities(held) },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    const fixture = TestBed.createComponent(CustomerDataRequests);
    fixture.componentRef.setInput('scope', SCOPE);
    fixture.componentRef.setInput('accountId', 'customer-1');
    fixture.componentRef.setInput('contacts', [CONTACT]);
    let correctedCount = 0;
    fixture.componentInstance.corrected.subscribe(() => correctedCount++);
    fixture.detectChanges();
    await vi.waitFor(() => expect(api['exportRequests']).toHaveBeenCalled());
    await fixture.whenStable();
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement as HTMLElement, corrected: () => correctedCount };
  }

  function click(host: HTMLElement, testId: string, fixture: ComponentFixture<unknown>): void {
    (host.querySelector(`[data-testid="${testId}"]`) as HTMLElement).click();
    fixture.detectChanges();
  }

  function type(
    host: HTMLElement,
    testId: string,
    value: string,
    fixture: ComponentFixture<unknown>,
  ): void {
    const input = host.querySelector(`[data-testid="${testId}"]`) as HTMLInputElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    api = {
      exportRequests: vi.fn().mockResolvedValue([]),
      requestExport: vi.fn().mockResolvedValue(request()),
      cancelExport: vi.fn().mockResolvedValue(request({ status: 'CANCELLED' })),
      produceExport: vi.fn().mockResolvedValue(request({ status: 'COMPLETED' })),
      exportBundleLink: vi
        .fn()
        .mockResolvedValue({ url: 'https://files.test/bundle', expiresAt: '2026-10-02T10:05:00Z' }),
      corrections: vi.fn().mockResolvedValue([]),
      profile: vi.fn().mockResolvedValue(PROFILE),
      correctDisplayName: vi
        .fn()
        .mockResolvedValue({ applied: true, correctionId: 'c-1', version: 8 }),
      correctDateOfBirth: vi
        .fn()
        .mockResolvedValue({ applied: true, correctionId: 'c-2', version: 9 }),
      correctContactPoint: vi
        .fn()
        .mockResolvedValue({ applied: true, correctionId: 'c-3', version: null }),
      correctAddress: vi.fn().mockResolvedValue({ applied: true, correctionId: 'c-4', version: 3 }),
      revealAddresses: vi.fn().mockResolvedValue([]),
    };
  });

  describe('a copy of her data', () => {
    it('lists the requests with the day she is owed an answer by', async () => {
      api['exportRequests'].mockResolvedValue([request()]);
      const { host } = await mount();

      const row = host.querySelector('[data-testid="export-request-row"]') as HTMLElement;
      expect(row.textContent).toContain('Waiting');
      expect(row.textContent).toContain('By the customer');
      expect(host.querySelector('[data-testid="export-due"]')?.textContent?.trim()).not.toBe('');
    });

    it('lets a manager ask on her behalf, and not while one is already waiting', async () => {
      const { fixture, host } = await mount();
      expect(host.querySelector('[data-testid="export-raise"]')).not.toBeNull();

      api['exportRequests'].mockResolvedValue([request()]);
      click(host, 'export-raise', fixture);
      await vi.waitFor(() =>
        expect(api['requestExport']).toHaveBeenCalledWith(SCOPE, 'customer-1'),
      );
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(host.querySelector('[data-testid="export-raise"]')).toBeNull();
      });
    });

    it('offers nothing to someone who holds neither capability', async () => {
      api['exportRequests'].mockResolvedValue([request()]);
      const { host } = await mount([]);

      expect(host.querySelector('[data-testid="export-raise"]')).toBeNull();
      expect(host.querySelector('[data-testid="export-withdraw"]')).toBeNull();
      expect(host.querySelector('[data-testid="export-produce"]')).toBeNull();
      expect(host.querySelector('[data-testid="correct-form"]')).toBeNull();
    });

    it('lets a manager withdraw but only the export capability produce', async () => {
      api['exportRequests'].mockResolvedValue([request()]);
      const manager = await mount(['CUSTOMER_MANAGE']);
      expect(manager.host.querySelector('[data-testid="export-withdraw"]')).not.toBeNull();
      expect(manager.host.querySelector('[data-testid="export-produce"]')).toBeNull();
    });

    it('asks for a purpose before it produces, and passes exactly what was typed', async () => {
      api['exportRequests'].mockResolvedValue([request()]);
      const { fixture, host } = await mount();

      click(host, 'export-produce', fixture);
      const submit = host.querySelector(
        '[data-testid="export-purpose-submit"]',
      ) as HTMLButtonElement;
      expect(submit.disabled).toBe(true);

      type(host, 'export-purpose', 'Subject access request received by post', fixture);
      expect(submit.disabled).toBe(false);
      submit.click();

      await vi.waitFor(() =>
        expect(api['produceExport']).toHaveBeenCalledWith(
          SCOPE,
          'customer-1',
          'export-1',
          'Subject access request received by post',
        ),
      );
    });

    it('says a second person has to approve first, naming the request, and does not pretend it was produced', async () => {
      api['exportRequests'].mockResolvedValue([request()]);
      api['produceExport'].mockRejectedValue(
        new ApiError(
          'SECOND_APPROVER_REQUIRED',
          422,
          { status: 422, code: 'SECOND_APPROVER_REQUIRED', approvalRequestId: 'approval-9' },
          null,
        ),
      );
      const { fixture, host } = await mount();
      click(host, 'export-produce', fixture);
      type(host, 'export-purpose', 'Subject access request', fixture);
      click(host, 'export-purpose-submit', fixture);

      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(host.querySelector('[data-testid="export-waiting"]')?.textContent).toContain(
          'approval-9',
        );
      });
      expect(api['exportRequests']).toHaveBeenCalledTimes(1);
    });

    it('gets a link for a produced file under a stated purpose, and shows it as a link to open', async () => {
      api['exportRequests'].mockResolvedValue([
        request({
          status: 'COMPLETED',
          bundleAvailable: true,
          bundleExpiresAt: '2026-10-09T10:00:00Z',
          producedAt: '2026-10-02T10:00:00Z',
        }),
      ]);
      const { fixture, host } = await mount();

      click(host, 'export-link', fixture);
      type(host, 'export-purpose', 'Sending the file to the customer', fixture);
      click(host, 'export-purpose-submit', fixture);

      await vi.waitFor(() =>
        expect(api['exportBundleLink']).toHaveBeenCalledWith(
          SCOPE,
          'customer-1',
          'export-1',
          'Sending the file to the customer',
        ),
      );
      await vi.waitFor(() => {
        fixture.detectChanges();
        const anchor = host.querySelector(
          '[data-testid="export-link-ready"] a',
        ) as HTMLAnchorElement;
        expect(anchor.getAttribute('href')).toBe('https://files.test/bundle');
        expect(anchor.getAttribute('rel')).toContain('noopener');
      });
    });

    it('says an expired file has expired and offers no link to it', async () => {
      api['exportRequests'].mockResolvedValue([
        request({
          status: 'COMPLETED',
          bundleAvailable: false,
          producedAt: '2026-09-01T10:00:00Z',
        }),
      ]);
      const { host } = await mount();

      expect(host.querySelector('[data-testid="export-link"]')).toBeNull();
      expect(host.querySelector('[data-testid="export-expired"]')).not.toBeNull();
    });

    it('shows what the file contains as counts per module and table, with what was left out', async () => {
      api['exportRequests'].mockResolvedValue([
        request({
          status: 'COMPLETED',
          bundleAvailable: true,
          manifest: {
            sections: [
              {
                name: 'ordering',
                tables: [{ table: 'ordering.orders', rows: 12, truncated: false }],
              },
            ],
            withheld: ['reviews:recovery_case_events.staff_notes'],
          },
        }),
      ]);
      const { fixture, host } = await mount();

      click(host, 'export-manifest-toggle', fixture);

      const line = host.querySelector('[data-testid="export-manifest-line"]') as HTMLElement;
      expect(line.textContent).toContain('ordering.orders');
      expect(line.textContent).toContain('12');
      expect(host.querySelector('[data-testid="export-withheld"]')?.textContent).toContain(
        'recovery_case_events.staff_notes',
      );
    });
  });

  describe('corrections', () => {
    it('lists what was corrected, against which reference, by whom — never a value', async () => {
      api['corrections'].mockResolvedValue([correction()]);
      const { host } = await mount();

      const row = host.querySelector('[data-testid="correction-row"]') as HTMLElement;
      expect(row.textContent).toContain('Name');
      expect(row.textContent).toContain('TICKET-4711');
      expect(row.textContent).toContain('support-1');
    });

    it('will not submit until the evidence is a reference, and says what a reference is', async () => {
      const { fixture, host } = await mount();
      const submit = host.querySelector('[data-testid="correct-submit"]') as HTMLButtonElement;

      type(host, 'correct-value', 'Karim Yoldoshev', fixture);
      expect(submit.disabled).toBe(true);
      type(host, 'correct-evidence', 'customer showed a passport', fixture);
      expect(submit.disabled).toBe(true);
      type(host, 'correct-evidence', 'TICKET-4711', fixture);
      expect(submit.disabled).toBe(false);
      expect(host.textContent).toContain('no spaces');
    });

    it('corrects a name against the version it just read, then says so and tells the pane', async () => {
      const { fixture, host, corrected } = await mount();
      type(host, 'correct-value', 'Karim Yoldoshev', fixture);
      type(host, 'correct-evidence', 'TICKET-4711', fixture);

      click(host, 'correct-submit', fixture);

      await vi.waitFor(() =>
        expect(api['correctDisplayName']).toHaveBeenCalledWith(
          SCOPE,
          'customer-1',
          { displayName: 'Karim Yoldoshev', evidenceReference: 'TICKET-4711' },
          7,
        ),
      );
      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(host.querySelector('[data-testid="correct-applied"]')).not.toBeNull();
      });
      expect(corrected()).toBe(1);
      expect(api['corrections']).toHaveBeenCalledTimes(2);
    });

    it('says nothing was recorded when the value was already that, and does not tell the pane', async () => {
      api['correctDisplayName'].mockResolvedValue({
        applied: false,
        correctionId: null,
        version: null,
      });
      const { fixture, host, corrected } = await mount();
      type(host, 'correct-value', 'Karim Yoldosev', fixture);
      type(host, 'correct-evidence', 'TICKET-4711', fixture);

      click(host, 'correct-submit', fixture);

      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(host.querySelector('[data-testid="correct-unchanged"]')).not.toBeNull();
      });
      expect(host.querySelector('[data-testid="correct-applied"]')).toBeNull();
      expect(corrected()).toBe(0);
    });

    it('corrects a date of birth only from a full date', async () => {
      const { fixture, host } = await mount();
      const subject = host.querySelector('[data-testid="correct-subject"]') as HTMLSelectElement;
      subject.value = 'DATE_OF_BIRTH';
      subject.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      type(host, 'correct-evidence', 'TICKET-4711', fixture);
      const submit = host.querySelector('[data-testid="correct-submit"]') as HTMLButtonElement;

      type(host, 'correct-value', '17/05/1991', fixture);
      expect(submit.disabled).toBe(true);
      type(host, 'correct-value', '1991-05-17', fixture);
      expect(submit.disabled).toBe(false);
      submit.click();

      await vi.waitFor(() =>
        expect(api['correctDateOfBirth']).toHaveBeenCalledWith(
          SCOPE,
          'customer-1',
          { dateOfBirth: '1991-05-17', evidenceReference: 'TICKET-4711' },
          7,
        ),
      );
    });

    it('needs a contact chosen before a contact can be corrected', async () => {
      const { fixture, host } = await mount();
      const subject = host.querySelector('[data-testid="correct-subject"]') as HTMLSelectElement;
      subject.value = 'CONTACT_POINT';
      subject.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      type(host, 'correct-evidence', 'TICKET-4711', fixture);
      type(host, 'correct-value', '+998901112233', fixture);
      const submit = host.querySelector('[data-testid="correct-submit"]') as HTMLButtonElement;
      expect(submit.disabled).toBe(true);

      const contact = host.querySelector('[data-testid="correct-contact"]') as HTMLSelectElement;
      contact.value = 'contact-1';
      contact.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      expect(submit.disabled).toBe(false);
      submit.click();

      await vi.waitFor(() =>
        expect(api['correctContactPoint']).toHaveBeenCalledWith(SCOPE, 'customer-1', 'contact-1', {
          value: '+998901112233',
          evidenceReference: 'TICKET-4711',
        }),
      );
    });

    it('opens the addresses under a stated purpose only when an address is what is being corrected', async () => {
      const { fixture, host } = await mount();
      expect(api['revealAddresses']).not.toHaveBeenCalled();

      const subject = host.querySelector('[data-testid="correct-subject"]') as HTMLSelectElement;
      subject.value = 'ADDRESS';
      subject.dispatchEvent(new Event('change'));
      fixture.detectChanges();

      await vi.waitFor(() =>
        expect(api['revealAddresses']).toHaveBeenCalledWith(
          SCOPE,
          'customer-1',
          expect.stringContaining('correct a customer address'),
        ),
      );
    });

    it('shows a refusal in words instead of failing silently', async () => {
      api['correctDisplayName'].mockRejectedValue(
        new ApiError('STALE_VERSION', 409, { status: 409, code: 'STALE_VERSION' }, null),
      );
      const { fixture, host } = await mount();
      type(host, 'correct-value', 'Karim Yoldoshev', fixture);
      type(host, 'correct-evidence', 'TICKET-4711', fixture);

      click(host, 'correct-submit', fixture);

      await vi.waitFor(() => {
        fixture.detectChanges();
        expect(host.querySelector('[data-testid="data-notice"]')?.textContent?.trim()).not.toBe('');
      });
    });
  });
});
