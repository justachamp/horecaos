import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ApiError } from '../../core/api/problem';
import { SessionContextService } from '../../core/auth/session-context.service';
import { APP_CONFIG, AppConfig } from '../../core/config/app-config';
import { Page } from '../../core/api/page';
import { TenantDirectory } from './tenant-directory';
import { OwnerStateView, TenantSummaryView, TenantView, TenantsApi } from './tenants-api';

const CONFIG: AppConfig = {
  apiBaseUrl: 'https://api.test.horecaos.uz',
  displayTimeZone: 'Asia/Tashkent',
};

const TENANT_A: TenantSummaryView = {
  id: 'tenant-a',
  slug: 'tenant-a',
  legalName: 'Tenant A LLC',
  displayName: 'Tenant A',
  defaultCurrency: 'UZS',
  defaultTimezone: 'Asia/Tashkent',
  status: 'ACTIVE',
  createdAt: '2026-08-01T00:00:00Z',
  countryCode: 'UZ',
  businessType: 'RESTAURANT',
};

/** Where the owner column sends an operator when there is something to chase. */
const ONBOARDING = '/tenants/tenant-a/onboarding';

class FakeTenantsApi {
  readonly listTenants = vi.fn<() => Promise<Page<TenantSummaryView>>>();
  readonly createTenant = vi.fn<() => Promise<TenantView>>();
  readonly tenantPlans = vi
    .fn()
    .mockResolvedValue([
      { tenantId: 'tenant-a', planCode: 'BASIC', planVersionNumber: 2, status: 'ACTIVE' },
    ]);
  readonly tenantHealth = vi
    .fn()
    .mockResolvedValue([
      { tenantId: 'tenant-a', deadLetters: 1, blockedReceipts: 2, posOrdersAwaiting: 0 },
    ]);
  readonly ownerInvitations = vi.fn().mockResolvedValue([]);
  readonly ownerStates = vi.fn<() => Promise<readonly OwnerStateView[]>>().mockResolvedValue([]);
}

describe('TenantDirectory', () => {
  let fixture: ComponentFixture<TenantDirectory>;
  let api: FakeTenantsApi;
  let router: Router;
  let capabilities: ReadonlySet<string>;

  beforeEach(async () => {
    api = new FakeTenantsApi();
    localStorage.clear();
    capabilities = new Set(['TENANT_READ', 'COMMERCIAL_PLAN_READ', 'TENANT_ONBOARDING_MANAGE']);

    await TestBed.configureTestingModule({
      imports: [TenantDirectory],
      providers: [
        provideRouter([]),
        { provide: APP_CONFIG, useValue: CONFIG },
        { provide: TenantsApi, useValue: api },
        {
          provide: SessionContextService,
          useValue: {
            has: (capability: string) => capabilities.has(capability),
            current: () => ({ subject: 'me' }),
          },
        },
      ],
    }).compileComponents();

    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
  });

  it('loads and renders the tenant list', async () => {
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const rows = fixture.nativeElement.querySelectorAll('tbody tr');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('Tenant A');
    expect(rows[0].textContent).toContain('tenant-a');
  });

  it('shows where each tenant trades, what it is, its plan and its open problems', async () => {
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    const row = fixture.nativeElement.querySelector('tbody tr') as HTMLElement;
    expect(row.textContent).toContain('UZ');
    expect(row.textContent).toContain('Ресторан');
    expect(row.querySelector('.plan')?.textContent).toContain('BASIC v2');
    expect(row.querySelector('.health')?.getAttribute('data-problems')).toBe('3');
  });

  it('leaves a dash where a platform-wide read fails, not an error across the page', async () => {
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });
    api.tenantPlans.mockRejectedValue(
      new ApiError({ status: 403, code: 'INSUFFICIENT_CAPABILITY' }),
    );

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.plan')?.textContent).toContain('—');
    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(1);
  });

  it('shows the empty state when there are no tenants', async () => {
    api.listTenants.mockResolvedValue({ items: [], nextCursor: null });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Клиентов пока нет.');
  });

  it('shows a translated error rather than a raw code on failure', async () => {
    api.listTenants.mockRejectedValue(
      new ApiError({ status: 403, code: 'INSUFFICIENT_CAPABILITY' }),
    );

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('У вас нет права');
  });

  it('offers a "load more" button only while a cursor remains, and appends the next page', async () => {
    api.listTenants.mockResolvedValueOnce({ items: [TENANT_A], nextCursor: 'tenant-a' });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const loadMore = fixture.nativeElement.querySelector('.loadMore') as HTMLButtonElement;
    expect(loadMore).not.toBeNull();

    const tenantB: TenantSummaryView = {
      ...TENANT_A,
      id: 'tenant-b',
      slug: 'tenant-b',
      displayName: 'Tenant B',
    };
    api.listTenants.mockResolvedValueOnce({ items: [tenantB], nextCursor: null });

    loadMore.click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(2);
    expect(fixture.nativeElement.querySelector('.loadMore')).toBeNull();
  });

  it('creates a tenant and navigates straight to its detail page', async () => {
    api.listTenants.mockResolvedValue({ items: [], nextCursor: null });
    api.createTenant.mockResolvedValue({
      id: 'new-tenant',
      slug: 'new-tenant',
      legalName: 'New Tenant LLC',
      displayName: 'New Tenant',
      defaultCurrency: 'UZS',
      defaultTimezone: 'Asia/Tashkent',
      keycloakOrganizationId: null,
      status: 'PROVISIONING',
      customerIdentityMode: 'TENANT_SHARED',
    });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    fixture.nativeElement.querySelector('.primary').click();
    fixture.detectChanges();

    const fields = fixture.nativeElement.querySelectorAll('.drawer input[type="text"]');
    const values = ['new-tenant', 'New Tenant LLC', 'New Tenant', 'UZS', 'Asia/Tashkent'];
    fields.forEach((field: HTMLInputElement, index: number) => {
      field.value = values[index];
      field.dispatchEvent(new Event('input'));
    });
    fixture.detectChanges();

    fixture.nativeElement
      .querySelector('.drawer')
      .dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();

    expect(api.createTenant).toHaveBeenCalledWith(
      expect.objectContaining({
        slug: 'new-tenant',
        legalName: 'New Tenant LLC',
        displayName: 'New Tenant',
      }),
    );
    expect(router.navigate).toHaveBeenCalledWith(['/tenants', 'new-tenant']);
  });

  /**
   * ADR 0100: a tenant sitting in PROVISIONING because its owner never opened
   * an email looks exactly like a tenant mid-onboarding for any other reason,
   * so the directory says which.
   */
  it('marks a tenant whose owner has not set up an account, and links to its onboarding', async () => {
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });
    api.ownerStates.mockResolvedValue([{ tenantId: 'tenant-a', state: 'SENT' }]);

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    const owner = fixture.nativeElement.querySelector('tbody tr .owner') as HTMLElement;
    expect(owner.dataset['owner']).toBe('waiting');
    const link = owner.querySelector('a') as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/tenants/tenant-a/onboarding');
  });

  /**
   * The assertion is on the request, not on the rendered cell. The overview
   * carries every outstanding owner's address and records an ADR 0029 reveal
   * for all of them; a column that renders a word has no business asking for
   * it, and a cell that happens to show no address is no evidence that it did
   * not (ADR 0100 \u00a76).
   */
  it('reads the address-free projection, never the overview that reveals recipients', async () => {
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    expect(api.ownerStates).toHaveBeenCalled();
    expect(api.ownerInvitations).not.toHaveBeenCalled();
  });

  /**
   * Four different things, four different words. "Set up" used to be what the
   * screen said about every tenant the list did not mention -- including one
   * created a minute earlier that had no owner at all.
   *
   * The link is the fourth column here because the two cells that carry one are
   * the two an operator has somewhere to go from, and `data-owner` sits on the
   * `<td>`: swapping the `<a>` inside for a `<span class="muted">`, the shape
   * the three settled cases use, changes neither the marker nor the text. The
   * NO_OWNER cell is the one that matters most and was the one nothing watched.
   */
  it.each([
    ['SENT', 'waiting', '\u041d\u0435 \u0441\u043e\u0437\u0434\u0430\u043d', ONBOARDING],
    ['NONE', 'waiting', '\u041d\u0435 \u0441\u043e\u0437\u0434\u0430\u043d', ONBOARDING],
    ['ACCEPTED', 'ready', '\u0421\u043e\u0437\u0434\u0430\u043d', null],
    [
      'NOT_NEEDED',
      'notNeeded',
      '\u041d\u0435 \u0442\u0440\u0435\u0431\u0443\u0435\u0442\u0441\u044f',
      null,
    ],
    [
      'NO_OWNER',
      'none',
      '\u0412\u043b\u0430\u0434\u0435\u043b\u044c\u0446\u0430 \u043d\u0435\u0442',
      ONBOARDING,
    ],
  ])(
    'says what the server said about an owner in state %s',
    async (state, marker, text, href) => {
      api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });
      api.ownerStates.mockResolvedValue([{ tenantId: 'tenant-a', state }]);

      fixture = TestBed.createComponent(TenantDirectory);
      fixture.detectChanges();
      await fixture.whenStable();
      await new Promise((resolve) => setTimeout(resolve));
      fixture.detectChanges();

      const owner = fixture.nativeElement.querySelector('tbody tr .owner') as HTMLElement;
      expect(owner.dataset['owner']).toBe(marker);
      expect(owner.textContent?.trim()).toBe(text);
      // `?? null` and not the bare optional chain: with no anchor the chain is
      // `undefined`, and `expect(undefined).toBe(null)` fails the two rows that
      // are correct exactly because they have no link.
      expect(owner.querySelector('a')?.getAttribute('href') ?? null).toBe(href);
    },
  );

  it('leaves a dash for a tenant the projection does not mention', async () => {
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });
    api.ownerStates.mockResolvedValue([{ tenantId: 'some-other-tenant', state: 'SENT' }]);

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    const owner = fixture.nativeElement.querySelector('tbody tr .owner') as HTMLElement;
    expect(owner.dataset['owner']).toBeUndefined();
    expect(owner.textContent?.trim()).toBe('\u2014');
    expect(owner.querySelector('a'), 'a dash is not a link to a screen this caller may not read').toBeNull();
  });

  /**
   * The column is optional in exactly the way plan and health are: a refused or
   * failing read leaves a dash, never an error across a page that is mostly
   * about something else.
   */
  it('leaves a dash rather than an error when the projection cannot be read', async () => {
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });
    api.ownerStates.mockRejectedValue(
      new ApiError({ status: 403, code: 'INSUFFICIENT_CAPABILITY', detail: 'no' }),
    );

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    const owner = fixture.nativeElement.querySelector('tbody tr .owner') as HTMLElement;
    expect(owner.textContent?.trim()).toBe('\u2014');
    expect(owner.querySelector('a'), 'a refused read leaves a dash, not a link').toBeNull();
    expect(fixture.nativeElement.querySelector('.state.error')).toBeNull();
  });

  /**
   * The capability guards were only ever taken true, so deleting one would have
   * been invisible: the refused read renders the same dash the unasked one
   * does. What is pinned here is that the read is never attempted -- every
   * `TENANT_READ`-only operator's page load would otherwise fire a platform-scope
   * request that can only ever be refused.
   */
  it('does not ask for what the operator may not read', async () => {
    capabilities = new Set(['TENANT_READ']);
    api.listTenants.mockResolvedValue({ items: [TENANT_A], nextCursor: null });

    fixture = TestBed.createComponent(TenantDirectory);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));
    fixture.detectChanges();

    expect(api.ownerStates).not.toHaveBeenCalled();
    expect(api.tenantPlans).not.toHaveBeenCalled();
    const owner = fixture.nativeElement.querySelector('tbody tr .owner') as HTMLElement;
    expect(owner.dataset['owner']).toBeUndefined();
    expect(owner.textContent?.trim()).toBe('\u2014');
    expect(fixture.nativeElement.querySelector('.plan')?.textContent).toContain('\u2014');
  });
});
