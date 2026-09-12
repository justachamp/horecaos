import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  HostListener,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiClient } from '../../../core/api/api-client';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { FeatureFlags } from '../../../core/feature-flags';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { ReadinessApi, ValidationResult } from './readiness-api';
import { SettingsNavGroup, visibleSettings } from '../settings-nav';

type ReadinessState = 'loading' | 'ready' | 'denied' | 'noRun' | 'error';

/**
 * Plain-language sentences for the `OnboardingStepHandlers` error codes
 * `OnboardingService.validate` names (wave P31's own reshape lists every
 * offending item under these, see `OnboardingStepHandler.StepResult.Finding`).
 * A code with no entry here still renders — {@link SettingsHomePage.readinessMessage}
 * falls back to the server's own `detail` text — so a step this list has not
 * caught up with is still legible, just in the server's own English rather
 * than a translated sentence.
 */
const READINESS_CODE_KEYS: Readonly<Record<string, MessageKey>> = {
  NO_BRAND: 'settings.home.readiness.code.NO_BRAND',
  NO_LOCATION: 'settings.home.readiness.code.NO_LOCATION',
  NO_LEGAL_ENTITY: 'settings.home.readiness.code.NO_LEGAL_ENTITY',
  NO_MERCHANT_BINDING: 'settings.home.readiness.code.NO_MERCHANT_BINDING',
  NO_DELIVERY_ZONE: 'settings.home.readiness.code.NO_DELIVERY_ZONE',
  NO_DELIVERY_TARIFF: 'settings.home.readiness.code.NO_DELIVERY_TARIFF',
  POS_BINDING_UNHEALTHY: 'settings.home.readiness.code.POS_BINDING_UNHEALTHY',
  NO_PUBLISHED_MENU: 'settings.home.readiness.code.NO_PUBLISHED_MENU',
  NO_AVAILABLE_ITEM: 'settings.home.readiness.code.NO_AVAILABLE_ITEM',
  MEDIA_NOT_AVAILABLE: 'settings.home.readiness.code.MEDIA_NOT_AVAILABLE',
};

/**
 * Where a finding deep-links to. A location-scoped finding always wins (more
 * specific than any code-keyed guess); a couple of tenant/brand-scoped codes
 * still have an obvious door even with no location attached.
 */
function readinessLink(finding: ValidationResult): readonly string[] | null {
  if (finding.locationId) {
    return ['/settings/locations', finding.locationId];
  }
  if (finding.errorCode === 'NO_BRAND' || finding.errorCode === 'NO_LOCATION') {
    return ['/settings/locations'];
  }
  if (finding.errorCode === 'POS_BINDING_UNHEALTHY') {
    return ['/settings/integrations'];
  }
  return null;
}

/**
 * 10.0 Settings home — `docs/operations-spec/settings.md` §10.0.
 *
 * **The readiness panel**, added in wave P31: not the spec's full
 * multi-source table (fiscal assignment, channel-payment-method,
 * channel-fulfilment-mode and service-binding coverage — none of those has a
 * read endpoint anywhere yet, control-plane or operations), but
 * `OnboardingController.validate` reshaped into exactly what it can honestly
 * answer today — every `VALIDATING`-phase check, every offending item named
 * rather than only the first (see `OnboardingService.validationResultsFor`).
 * The empty state ("Всё настроено") is the same one settings.md asks for.
 *
 * **Find a setting**, also added in wave P31: `/` filters the six-group
 * index by label and description text. Not yet the spec's full `Combobox`
 * over `ConfigurationKeys.all()` plus policy keys plus reference-list names
 * — most of those screens do not exist yet either — so this is the nav-item
 * half of that ambition, honestly scoped to what P31 built.
 */
@Component({
  selector: 'q-settings-home-page',
  imports: [TPipe, RouterLink],
  templateUrl: './settings-home-page.html',
  styleUrl: './settings-home-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsHomePage {
  private readonly flags = inject(FeatureFlags);
  private readonly tenant = inject(CurrentTenant);
  private readonly readinessApi = inject(ReadinessApi);
  protected readonly i18n = inject(I18n);

  protected readonly groups = computed(() => visibleSettings((flag) => this.flags.isOn(flag)));

  protected readonly query = signal('');
  protected readonly searchInput = viewChild<ElementRef<HTMLInputElement>>('searchInput');

  protected readonly filteredGroups = computed<readonly SettingsNavGroup[]>(() => {
    const needle = this.query().trim().toLowerCase();
    if (!needle) {
      return this.groups();
    }
    return this.groups()
      .map((group) => ({
        ...group,
        items: group.items.filter(
          (item) =>
            this.i18n.t(item.label).toLowerCase().includes(needle) ||
            this.i18n.t(item.description).toLowerCase().includes(needle),
        ),
      }))
      .filter((group) => group.items.length > 0);
  });

  protected readonly readinessState = signal<ReadinessState>('loading');
  protected readonly readinessErrorText = signal<string | null>(null);
  protected readonly findings = signal<readonly ValidationResult[]>([]);

  constructor() {
    void this.flags.ensureLoaded();
    void this.loadReadiness();
  }

  protected onSearchInput(value: string): void {
    this.query.set(value);
  }

  /** `/` focuses the search box from anywhere on this page — settings.md §1.6. */
  @HostListener('document:keydown', ['$event'])
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key !== '/' || event.defaultPrevented) {
      return;
    }
    const target = event.target as HTMLElement | null;
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
      return;
    }
    event.preventDefault();
    this.searchInput()?.nativeElement.focus();
  }

  protected readinessMessage(finding: ValidationResult): string {
    const key = finding.errorCode ? READINESS_CODE_KEYS[finding.errorCode] : undefined;
    return key ? this.i18n.t(key) : (finding.detail ?? finding.errorCode ?? '');
  }

  protected readinessLink(finding: ValidationResult): readonly string[] | null {
    return readinessLink(finding);
  }

  private async loadReadiness(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      this.readinessState.set(this.tenant.denied() ? 'denied' : 'error');
      return;
    }
    try {
      const outcome = await this.readinessApi.validate(tenantId);
      this.findings.set(outcome.checks.filter((check) => !check.passed));
      this.readinessState.set('ready');
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.readinessState.set('denied');
      } else if (error instanceof ApiError && error.status === 404) {
        this.readinessState.set('noRun');
      } else if (error instanceof ApiError) {
        this.readinessErrorText.set(
          describeApiError(error, (key, values) => this.i18n.t(key, values)),
        );
        this.readinessState.set('error');
      } else {
        throw error;
      }
    }
  }
}
