import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BrandScope } from '../../../core/api/catalog-paths';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { Capability, SessionCapabilities } from '../../../core/auth/session-capabilities';
import { I18n } from '../../../core/i18n/i18n';
import { MediaApi } from '../../catalog/media-api';
import { ConfigurationApi } from '../../settings/configuration-api';
import { library, promptPolicy, recoveryPolicy } from './feedback-fixtures.testing';
import { FeedbackSettingsApi } from './feedback-settings-api';
import { FeedbackSettingsPage } from './feedback-settings-page';

const SCOPE: BrandScope = { tenantId: 'tenant-1', brandId: 'brand-1' };

class FakeCurrentBrand {
  readonly scope = signal<BrandScope | null>(SCOPE);
  readonly denied = signal(false);
  ensureLoaded = vi.fn().mockResolvedValue(undefined);
}

async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

describe('FeedbackSettingsPage', () => {
  let fixture: ComponentFixture<FeedbackSettingsPage>;
  let api: {
    library: ReturnType<typeof vi.fn>;
    promptPolicy: ReturnType<typeof vi.fn>;
    recoveryPolicy: ReturnType<typeof vi.fn>;
  };

  async function render(
    options: { write?: boolean; brand?: FakeCurrentBrand } = {},
  ): Promise<void> {
    api = {
      library: vi.fn(async () => library()),
      promptPolicy: vi.fn(async () => promptPolicy()),
      recoveryPolicy: vi.fn(async () => recoveryPolicy()),
    };
    const held: Capability[] = options.write === false ? [] : ['TENANT_CONFIGURATION_WRITE'];
    await TestBed.configureTestingModule({
      imports: [FeedbackSettingsPage],
      providers: [
        provideRouter([]),
        { provide: FeedbackSettingsApi, useValue: api },
        {
          provide: MediaApi,
          useValue: { upload: vi.fn(() => of({})), downloadUrl: vi.fn(() => of('')) },
        },
        {
          provide: ConfigurationApi,
          useValue: {
            resolution: vi.fn(async () => ({ value: false, currentVersionAtScope: null })),
            setValue: vi.fn(),
          },
        },
        { provide: CurrentBrand, useValue: options.brand ?? new FakeCurrentBrand() },
        { provide: SessionCapabilities, useValue: { has: (c: Capability) => held.includes(c) } },
      ],
    }).compileComponents();
    TestBed.inject(I18n).setLocale('en');
    fixture = TestBed.createComponent(FeedbackSettingsPage);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  async function open(tab: string): Promise<void> {
    (host().querySelector(`[data-testid="feedback-tab-${tab}"]`) as HTMLElement).click();
    await flush();
    fixture.detectChanges();
  }

  it('opens on the tag library and offers the four sections', async () => {
    await render();

    expect(host().querySelector('[data-testid="tag-library"]')).not.toBeNull();
    expect(host().querySelectorAll('[role="tab"]')).toHaveLength(4);
    expect(api.library).toHaveBeenCalledWith('tenant-1');
  });

  it('offers a level to apply to only for the sections that have one', async () => {
    await render();
    expect(host().querySelector('[data-testid="feedback-target"]')).toBeNull();

    await open('prompt');
    expect(host().querySelector('[data-testid="feedback-target"]')).not.toBeNull();
    expect(host().querySelector('[data-testid="prompt-policy"]')).not.toBeNull();
  });

  it('carries the chosen level into the section it shows', async () => {
    await render();
    await open('recovery');
    expect(api.recoveryPolicy).toHaveBeenLastCalledWith({ kind: 'TENANT', tenantId: 'tenant-1' });

    const target = host().querySelector('[data-testid="feedback-target"]') as HTMLSelectElement;
    target.value = 'BRAND';
    target.dispatchEvent(new Event('change'));
    await flush();
    fixture.detectChanges();

    expect(api.recoveryPolicy).toHaveBeenLastCalledWith({ kind: 'BRAND', scope: SCOPE });
  });

  it('shows the visibility section', async () => {
    await render();
    await open('visibility');

    expect(host().querySelector('[data-testid="visibility"]')).not.toBeNull();
  });

  it('tells a reader who cannot write that the values are theirs to read only', async () => {
    await render({ write: false });

    expect(host().querySelector('[data-testid="feedback-read-only"]')?.textContent).toContain(
      'read these settings but not change them',
    );
    expect(host().querySelector('[data-testid="tag-new"]')).toBeNull();
  });

  it('shows no write notice to someone who can', async () => {
    await render({ write: true });

    expect(host().querySelector('[data-testid="feedback-read-only"]')).toBeNull();
    expect(host().querySelector('[data-testid="tag-new"]')).not.toBeNull();
  });

  it('shows the denied state when the operator covers no brand', async () => {
    const denied = new FakeCurrentBrand();
    denied.scope.set(null);
    await render({ brand: denied });

    expect(host().textContent).toContain('You do not have access to these settings.');
    expect(api.library).not.toHaveBeenCalled();
  });
});

beforeEach(() => TestBed.resetTestingModule());
