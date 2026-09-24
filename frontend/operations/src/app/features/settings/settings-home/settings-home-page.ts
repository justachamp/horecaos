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
import { Router, RouterLink } from '@angular/router';

import { ApiClient } from '../../../core/api/api-client';
import { ConfigurationKeyView } from '../../../core/api/configuration';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { FeatureFlags } from '../../../core/feature-flags';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { describeApiError } from '../../orders/order-errors';
import { ConfigurationApi } from '../configuration-api';
import { ReadinessApi, ValidationResult } from './readiness-api';
import { SettingsNavGroup, visibleSettings } from '../settings-nav';
import { CONFIGURATION_KEY_ROUTES, REFERENCE_LISTS } from '../settings-search-index';

/** Where `q-combobox`'s flat `options` list sends the operator once one is chosen. */
type SearchResultKind = 'configurationKey' | 'referenceList';

const MAX_SEARCH_RESULTS = 8;

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
  NO_ACTIVE_BRAND: 'settings.home.readiness.code.NO_ACTIVE_BRAND',
  NO_PUBLISHED_MENU: 'settings.home.readiness.code.NO_PUBLISHED_MENU',
  NO_AVAILABLE_ITEM: 'settings.home.readiness.code.NO_AVAILABLE_ITEM',
  MEDIA_NOT_AVAILABLE: 'settings.home.readiness.code.MEDIA_NOT_AVAILABLE',
  // Row 10.0: NOTIFICATION_TEMPLATE_MODERATION_VALIDATE, the one check
  // `OnboardingService.validate` runs ad hoc rather than through a formal
  // `OnboardingStep` — see that method's own doc.
  TEMPLATE_AWAITING_PROVIDER_REVIEW: 'settings.home.readiness.code.TEMPLATE_AWAITING_PROVIDER_REVIEW',
  TEMPLATE_REJECTED_BY_PROVIDER: 'settings.home.readiness.code.TEMPLATE_REJECTED_BY_PROVIDER',
};

/**
 * Where a finding deep-links to. A location-scoped finding always wins (more
 * specific than any code-keyed guess); a handful of tenant/brand-scoped codes
 * still have an obvious door even with no location attached — wave 9 adds the
 * catalogue readiness codes now that `CatalogReadinessValidate` names every
 * offending brand instead of stopping at the first (gap map row 10.0).
 */
function readinessLink(finding: ValidationResult): readonly string[] | null {
  if (finding.locationId) {
    return ['/settings/locations', finding.locationId];
  }
  if (finding.errorCode === 'NO_BRAND' || finding.errorCode === 'NO_ACTIVE_BRAND') {
    return ['/settings/brand'];
  }
  if (finding.errorCode === 'NO_LOCATION') {
    return ['/settings/locations'];
  }
  if (finding.errorCode === 'POS_BINDING_UNHEALTHY') {
    return ['/settings/integrations'];
  }
  if (finding.errorCode === 'NO_PUBLISHED_MENU' || finding.errorCode === 'NO_AVAILABLE_ITEM') {
    return ['/catalog/publication'];
  }
  if (
    finding.errorCode === 'TEMPLATE_AWAITING_PROVIDER_REVIEW' ||
    finding.errorCode === 'TEMPLATE_REJECTED_BY_PROVIDER'
  ) {
    return ['/settings/notifications'];
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
 * **Find a setting**, added in wave P31 and widened this wave into a real
 * `q-combobox`: `/` still filters the six-group nav grid by label and
 * description text (unchanged — a browsable tile grid loses nothing by
 * staying a plain filter), and the same input now also drives a combobox
 * dropdown of two further sources the spec's own `Combobox` asks for —
 * `ConfigurationKeys.all()` (ADR 0030, over `ConfigurationApi.keys`,
 * narrowed to the keys `CONFIGURATION_KEY_ROUTES` can actually send
 * somewhere) and `reference-data-page.ts`'s five named lists — choosing a
 * result navigates straight there. Policy keys are not a separate source:
 * order policy's eleven fields (10.3b) are themselves `ConfigurationKey`
 * rows, already covered by the first source.
 */
@Component({
  selector: 'q-settings-home-page',
  imports: [TPipe, RouterLink, Combobox],
  templateUrl: './settings-home-page.html',
  styleUrl: './settings-home-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsHomePage {
  private readonly flags = inject(FeatureFlags);
  private readonly tenant = inject(CurrentTenant);
  private readonly readinessApi = inject(ReadinessApi);
  private readonly configurationApi = inject(ConfigurationApi);
  private readonly router = inject(Router);
  protected readonly i18n = inject(I18n);

  protected readonly groups = computed(() => visibleSettings((flag) => this.flags.isOn(flag)));

  protected readonly query = signal('');
  protected readonly searchHost = viewChild<ElementRef<HTMLElement>>('searchHost');

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

  // ----------------------------------------------------- 10.0: the combobox half

  protected readonly configurationKeys = signal<readonly ConfigurationKeyView[]>([]);

  /**
   * The combobox's own `options` — configuration keys and reference lists
   * matching the same {@link query} the tile grid filters by, each `id`
   * carrying which source it came from (`configurationKey:<code>` /
   * `referenceList:<fragment>`) for {@link onResultSelected} to route on.
   * Capped at {@link MAX_SEARCH_RESULTS}: a combobox dropdown is for picking
   * one thing, not for browsing the whole registry — the tile grid above it
   * already does that job for nav routes.
   */
  protected readonly searchResults = computed<readonly ComboboxOption[]>(() => {
    const needle = this.query().trim().toLowerCase();
    if (!needle) {
      return [];
    }
    const keyResults: ComboboxOption[] = this.configurationKeys()
      .filter((key) => CONFIGURATION_KEY_ROUTES[key.code] !== undefined)
      .filter(
        (key) =>
          key.code.toLowerCase().includes(needle) || key.description.toLowerCase().includes(needle),
      )
      .map((key) => ({
        id: `configurationKey:${key.code}`,
        label: key.code,
        sublabel: key.description,
      }));
    const referenceResults: ComboboxOption[] = REFERENCE_LISTS.filter((entry) =>
      this.i18n.t(entry.labelKey).toLowerCase().includes(needle),
    ).map((entry) => ({
      id: `referenceList:${entry.fragment}`,
      label: this.i18n.t(entry.labelKey),
      sublabel: this.i18n.t('settings.nav.referenceData'),
    }));
    return [...keyResults, ...referenceResults].slice(0, MAX_SEARCH_RESULTS);
  });

  protected readonly readinessState = signal<ReadinessState>('loading');
  protected readonly readinessErrorText = signal<string | null>(null);
  protected readonly findings = signal<readonly ValidationResult[]>([]);

  constructor() {
    void this.flags.ensureLoaded();
    void this.loadReadiness();
    void this.loadSearchIndex();
  }

  protected onSearchInput(value: string): void {
    this.query.set(value);
  }

  /**
   * A combobox result was chosen — parsed back into its {@link
   * SearchResultKind} and destination by the same `id` prefix {@link
   * searchResults} wrote it with.
   */
  protected onResultSelected(option: ComboboxOption): void {
    const [kind, ...rest] = option.id.split(':');
    const value = rest.join(':');
    if ((kind as SearchResultKind) === 'configurationKey') {
      const path = CONFIGURATION_KEY_ROUTES[value];
      if (path) {
        void this.navigateToSettingsPath(path);
      }
    } else if ((kind as SearchResultKind) === 'referenceList') {
      void this.router.navigate(['/settings/reference-data'], { fragment: value });
    }
    this.query.set('');
  }

  private async navigateToSettingsPath(path: string): Promise<void> {
    if (path.startsWith('/')) {
      await this.router.navigateByUrl(path);
    } else {
      await this.router.navigate(['/settings', path]);
    }
  }

  private async loadSearchIndex(): Promise<void> {
    await this.tenant.ensureLoaded();
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    try {
      this.configurationKeys.set(await this.configurationApi.keys(tenantId));
    } catch {
      // Best-effort, the same posture brand-profile.ts's own tenant-market
      // read takes: the tile grid above still works with an empty second
      // source, and a failed search-index load must not fail the page.
    }
  }

  /**
   * `/` focuses the search box from anywhere on this page — settings.md
   * §1.6. `q-combobox` is fully controlled and exposes no imperative focus
   * method of its own, so the plain `<input>` its own template renders is
   * reached through the host element's light DOM instead — nothing is read
   * or written on that node beyond calling `.focus()`.
   */
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
    this.searchHost()?.nativeElement.querySelector('input')?.focus();
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
