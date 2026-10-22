import { TestBed, type ComponentFixture } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { vi } from 'vitest';

import { ProfilePrivacyComponent } from './profile-privacy';
import { TranslateService } from '../../../services/translate.service';
import { Session } from '../../../core/auth/session';
import { CustomerApi, type ExportRequestStatus } from '../../../core/api/customer-api';
import { HorecaOSApiError } from '../../../core/api/problem-details';

function status(overrides: Partial<ExportRequestStatus> = {}): ExportRequestStatus {
  return {
    id: 'export-1',
    status: 'PENDING',
    requestedAt: '2026-10-01T10:00:00Z',
    responseDueAt: '2026-10-31T10:00:00Z',
    producedAt: null,
    bundleExpiresAt: null,
    bundleAvailable: false,
    cancelledAt: null,
    ...overrides,
  };
}

class FakeTranslateService {
  get(key: string): string {
    return key;
  }
  getWithParams(key: string): string {
    return key;
  }
  current(): Record<string, unknown> {
    return {};
  }
}

function setUp(signedIn = true) {
  const customers = {
    exportRequest: vi.fn(async (): Promise<ExportRequestStatus | null> => null),
    requestExport: vi.fn(async () => status()),
    cancelExport: vi.fn(async () => status({ status: 'CANCELLED', cancelledAt: '2026-10-02T10:00:00Z' })),
    exportBundleLink: vi.fn(async () => ({
      url: 'https://files.test/mine',
      expiresAt: '2026-10-02T10:05:00Z',
    })),
  };
  TestBed.configureTestingModule({
    imports: [ProfilePrivacyComponent],
    providers: [
      provideRouter([]),
      { provide: CustomerApi, useValue: customers },
      { provide: TranslateService, useClass: FakeTranslateService },
    ],
  });
  const session = TestBed.inject(Session);
  if (signedIn) {
    session.adopt({ accessToken: 'tok', expiresAt: new Date(Date.now() + 3_600_000).toISOString() });
  }
  const fixture = TestBed.createComponent(ProfilePrivacyComponent);
  return { fixture, comp: fixture.componentInstance, customers };
}

async function mount(fixture: ComponentFixture<unknown>): Promise<HTMLElement> {
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

function click(host: HTMLElement, fixture: ComponentFixture<unknown>, testId: string): void {
  (host.querySelector(`[data-testid="${testId}"]`) as HTMLElement).click();
  fixture.detectChanges();
}

describe('ProfilePrivacyComponent', () => {
  // The storefront builders share one jsdom per worker; nothing here may leave a session behind for the next spec.
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('asks a guest to sign in and never calls the platform', async () => {
    const { fixture, customers } = setUp(false);
    const host = await mount(fixture);

    expect(host.querySelector('[data-testid="privacy-sign-in"]')).not.toBeNull();
    expect(customers.exportRequest).not.toHaveBeenCalled();
  });

  it('offers to ask when nothing has been asked, and shows the wait and the due day after asking', async () => {
    const { fixture, customers } = setUp();
    const host = await mount(fixture);
    expect(host.querySelector('[data-testid="privacy-ask"]')).not.toBeNull();

    click(host, fixture, 'privacy-ask');
    await vi.waitFor(() => expect(customers.requestExport).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="privacy-waiting"]')).not.toBeNull();
    });
    expect(host.querySelector('[data-testid="privacy-due"]')?.textContent).toContain('profile.privacyDueBy');
    expect(host.querySelector('[data-testid="privacy-ask"]')).toBeNull();
    expect(host.querySelector('[data-testid="privacy-get-link"]')).toBeNull();
  });

  it('lets her withdraw while it waits, and then ask again', async () => {
    const { fixture, customers } = setUp();
    customers.exportRequest.mockResolvedValue(status());
    const host = await mount(fixture);

    click(host, fixture, 'privacy-withdraw');
    await vi.waitFor(() => expect(customers.cancelExport).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="privacy-ask"]')).not.toBeNull();
    });
    expect(host.querySelector('[data-testid="privacy-waiting"]')).toBeNull();
  });

  it('does not ask for the link until she presses the button, because each ask is recorded against her', async () => {
    const { fixture, customers } = setUp();
    customers.exportRequest.mockResolvedValue(
      status({ status: 'COMPLETED', bundleAvailable: true, bundleExpiresAt: '2026-10-09T10:00:00Z' }),
    );
    const host = await mount(fixture);

    expect(host.querySelector('[data-testid="privacy-ready"]')).not.toBeNull();
    expect(customers.exportBundleLink).not.toHaveBeenCalled();

    click(host, fixture, 'privacy-get-link');
    await vi.waitFor(() => expect(customers.exportBundleLink).toHaveBeenCalledTimes(1));
    await vi.waitFor(() => {
      fixture.detectChanges();
      const anchor = host.querySelector('[data-testid="privacy-open"]') as HTMLAnchorElement;
      expect(anchor.getAttribute('href')).toBe('https://files.test/mine');
      expect(anchor.getAttribute('rel')).toContain('noopener');
    });
  });

  it('says the file has expired and offers to ask again when the link is gone', async () => {
    const { fixture, customers } = setUp();
    customers.exportRequest.mockResolvedValue(
      status({ status: 'COMPLETED', bundleAvailable: false, producedAt: '2026-09-01T10:00:00Z' }),
    );
    const host = await mount(fixture);

    expect(host.querySelector('[data-testid="privacy-expired"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="privacy-ask"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="privacy-get-link"]')).toBeNull();
  });

  it('re-reads where the request stands when the link turns out to have expired meanwhile', async () => {
    const { fixture, customers } = setUp();
    customers.exportRequest
      .mockResolvedValueOnce(status({ status: 'COMPLETED', bundleAvailable: true }))
      .mockResolvedValue(status({ status: 'COMPLETED', bundleAvailable: false }));
    customers.exportBundleLink.mockRejectedValue(
      new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'none' }),
    );
    const host = await mount(fixture);

    click(host, fixture, 'privacy-get-link');
    await vi.waitFor(() => {
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="privacy-expired"]')).not.toBeNull();
    });
  });

  it('treats a customer with no account here as having asked for nothing', async () => {
    const { fixture, customers } = setUp();
    customers.exportRequest.mockRejectedValue(
      new HorecaOSApiError({ status: 404, code: 'RESOURCE_NOT_FOUND', detail: 'none' }),
    );
    const host = await mount(fixture);

    expect(host.querySelector('[data-testid="privacy-ask"]')).not.toBeNull();
  });
});
