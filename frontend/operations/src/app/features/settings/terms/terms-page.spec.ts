import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { I18n } from '../../../core/i18n/i18n';
import { BrandProfileApi, BrandView } from '../brand-profile/brand-profile-api';
import { TermsApi, TermsVersionSummaryView, TermsVersionView } from './terms-api';
import { TermsPage } from './terms-page';

const BRAND: BrandView = {
  id: 'brand-1',
  tenantId: 'tenant-1',
  code: 'RAYHON',
  slug: 'rayhon',
  displayName: 'Rayhon',
  status: 'ACTIVE',
};

const BRAND_2: BrandView = {
  id: 'brand-2',
  tenantId: 'tenant-1',
  code: 'OSHXONA',
  slug: 'oshxona',
  displayName: 'Oshxona',
  status: 'ACTIVE',
};

const NEVER_PUBLISHED: TermsVersionView = {
  published: false,
  id: null,
  version: null,
  contentsByLocale: {},
  publishedBy: null,
  publishedAt: null,
};

function published(overrides: Partial<TermsVersionView> = {}): TermsVersionView {
  return {
    published: true,
    id: 'terms-1',
    version: 2,
    contentsByLocale: { ru: 'Правила', en: 'Terms' },
    publishedBy: 'owner-1',
    publishedAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function summary(overrides: Partial<TermsVersionSummaryView> = {}): TermsVersionSummaryView {
  return {
    id: 'terms-1',
    version: 2,
    locales: ['ru', 'en'],
    publishedBy: 'owner-1',
    publishedAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

class FakeCurrentTenant {
  readonly tenantId = signal<string | null>('tenant-1');
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

async function render(
  brandsApi: Partial<BrandProfileApi>,
  termsApi: Partial<TermsApi>,
  tenant: FakeCurrentTenant = new FakeCurrentTenant(),
): Promise<ComponentFixture<TermsPage>> {
  await TestBed.configureTestingModule({
    imports: [TermsPage],
    providers: [
      provideRouter([]),
      { provide: BrandProfileApi, useValue: brandsApi },
      { provide: TermsApi, useValue: termsApi },
      { provide: CurrentTenant, useValue: tenant },
    ],
  }).compileComponents();
  TestBed.inject(I18n).setLocale('en');
  const fixture: ComponentFixture<TermsPage> = TestBed.createComponent(TermsPage);
  fixture.detectChanges();
  await flushMicrotasks();
  fixture.detectChanges();
  return fixture;
}

describe('TermsPage', () => {
  it('names the platform default when the brand has never published', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { current: vi.fn().mockResolvedValue(NEVER_PUBLISHED), list: vi.fn().mockResolvedValue([]) },
    );
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('platform’s own default terms text');
  });

  it('does not render a brand picker with only one brand', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      { current: vi.fn().mockResolvedValue(NEVER_PUBLISHED), list: vi.fn().mockResolvedValue([]) },
    );
    expect(fixture.nativeElement.querySelector('#terms-brand')).toBeFalsy();
  });

  it('renders a brand picker with more than one brand', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND, BRAND_2]) },
      { current: vi.fn().mockResolvedValue(NEVER_PUBLISHED), list: vi.fn().mockResolvedValue([]) },
    );
    const select = fixture.nativeElement.querySelector('#terms-brand') as HTMLSelectElement;
    expect(select).toBeTruthy();
    expect(select.querySelectorAll('option').length).toBe(2);
  });

  it('renders the publish history newest first, with its locales and who published', async () => {
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        current: vi.fn().mockResolvedValue(published()),
        list: vi
          .fn()
          .mockResolvedValue([
            summary(),
            summary({ id: 'terms-0', version: 1, locales: ['ru'], publishedBy: 'owner-0' }),
          ]),
      },
    );
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('owner-1');
    expect(text).toContain('owner-0');
    expect(text).toContain('ru, en');
  });

  it('loads a historical version read-only when a history row is clicked', async () => {
    const versionFn = vi
      .fn()
      .mockResolvedValue(
        published({ version: 1, contentsByLocale: { ru: 'Старый текст' }, publishedBy: 'owner-0' }),
      );
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        current: vi.fn().mockResolvedValue(published()),
        list: vi
          .fn()
          .mockResolvedValue([summary({ version: 1, locales: ['ru'], publishedBy: 'owner-0' })]),
        version: versionFn,
      },
    );
    const row = fixture.nativeElement.querySelector('.row--clickable') as HTMLElement;
    row.click();
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(versionFn).toHaveBeenCalledWith('tenant-1', 'brand-1', 1);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Старый текст');
  });

  it('publishes a new version from whatever locales were filled in, and shows the result', async () => {
    const publish = vi.fn().mockResolvedValue(published({ version: 3 }));
    const listFn = vi
      .fn()
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([summary({ version: 3 })]);
    const fixture = await render(
      { list: vi.fn().mockResolvedValue([BRAND]) },
      {
        current: vi.fn().mockResolvedValue(NEVER_PUBLISHED),
        list: listFn,
        publish,
      },
    );

    const setValue = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLTextAreaElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#terms-ru', 'Новые правила');
    fixture.detectChanges();

    const submit = fixture.nativeElement.querySelector(
      '.form__actions button',
    ) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();
    fixture.detectChanges();

    expect(publish).toHaveBeenCalledWith('tenant-1', 'brand-1', {
      contentsByLocale: { ru: 'Новые правила' },
      note: undefined,
    });
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Published as version 3');
  });

  it('shows the denied state on a 403 rather than an empty page', async () => {
    const fixture = await render(
      {
        list: vi
          .fn()
          .mockRejectedValue(new ApiError(ApiErrorCode.INSUFFICIENT_CAPABILITY, 403, null, null)),
      },
      { current: vi.fn(), list: vi.fn() },
    );
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
  });

  it('shows the denied state when the tenant itself resolves to none', async () => {
    const tenant = new FakeCurrentTenant();
    tenant.tenantId.set(null);
    tenant.denied.set(true);
    const list = vi.fn();
    const fixture = await render({ list }, { current: vi.fn(), list: vi.fn() }, tenant);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No location in scope');
    expect(list).not.toHaveBeenCalled();
  });
});
