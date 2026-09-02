import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { LocationScope } from '../../../core/api/operations-paths';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { NotificationsApi, TemplateResponse } from './notifications-api';
import { NotificationsPage } from './notifications-page';

const SCOPE: LocationScope = { tenantId: 'tenant-1', brandId: 'brand-1', locationId: 'location-1' };

const TEMPLATE: TemplateResponse = {
  id: 'template-1',
  brandId: 'brand-1',
  templateKey: 'CONFIRMED',
  notificationClass: 'TRANSACTIONAL_REQUIRED',
  channel: 'SMS',
  consentPurpose: null,
  status: 'ACTIVE',
  activeVersion: 1,
  version: 1,
};

class FakeCurrentLocation {
  readonly scope = signal<LocationScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('NotificationsPage', () => {
  let fixture: ComponentFixture<NotificationsPage>;
  let api: {
    list: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    addVersion: ReturnType<typeof vi.fn>;
    activate: ReturnType<typeof vi.fn>;
  };

  beforeEach(async () => {
    api = {
      list: vi.fn().mockResolvedValue([TEMPLATE]),
      create: vi.fn().mockResolvedValue('template-2'),
      addVersion: vi.fn().mockResolvedValue(1),
      activate: vi.fn().mockResolvedValue(undefined),
    };

    await TestBed.configureTestingModule({
      imports: [NotificationsPage],
      providers: [
        { provide: NotificationsApi, useValue: api },
        { provide: CurrentLocation, useValue: new FakeCurrentLocation() },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(NotificationsPage);
    fixture.detectChanges();
    await flushMicrotasks();
    fixture.detectChanges();
  });

  it('lists templates on Tab 1', () => {
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('CONFIRMED');
    expect(api.list).toHaveBeenCalledWith(SCOPE);
  });

  it('shows an honest not-built note on the routing tab', () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab');
    (tabs[1] as HTMLButtonElement).click();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'operations-spec/settings.md §10.9 Tab 2',
    );
  });

  it('creates a template and its first version in all three locales, then activates it', async () => {
    const toggle = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('button'),
    ).find((button) => button.textContent?.includes('New template')) as HTMLButtonElement;
    toggle.click();
    fixture.detectChanges();

    const setValue = (id: string, value: string) => {
      const el = fixture.nativeElement.querySelector(id) as HTMLInputElement | HTMLTextAreaElement;
      el.value = value;
      el.dispatchEvent(new Event('input'));
    };
    setValue('#template-key', 'CANCELLED');
    setValue('#template-body-ru', 'Заказ отменён');
    setValue('#template-body-uz', 'Buyurtma bekor qilindi');
    setValue('#template-body-en', 'Order cancelled');
    fixture.detectChanges();

    const submit = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('.form__actions button'),
    ).find((button) => button.textContent?.includes('Create')) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
    submit.click();
    await flushMicrotasks();

    expect(api.create).toHaveBeenCalledWith(
      SCOPE,
      expect.objectContaining({ templateKey: 'CANCELLED' }),
    );
    expect(api.addVersion).toHaveBeenCalledWith(
      SCOPE,
      'template-2',
      expect.objectContaining({
        wordings: {
          ru: { body: 'Заказ отменён' },
          'uz-Latn': { body: 'Buyurtma bekor qilindi' },
          en: { body: 'Order cancelled' },
        },
      }),
    );
    expect(api.activate).toHaveBeenCalledWith(SCOPE, 'template-2', 1);
  });
});
