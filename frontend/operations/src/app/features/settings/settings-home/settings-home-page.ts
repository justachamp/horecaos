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
import { TierCounts, countByTier, orderFindings, tierOf } from './readiness-order';
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
  TEMPLATE_AWAITING_PROVIDER_REVIEW:
    'settings.home.readiness.code.TEMPLATE_AWAITING_PROVIDER_REVIEW',
  TEMPLATE_REJECTED_BY_PROVIDER: 'settings.home.readiness.code.TEMPLATE_REJECTED_BY_PROVIDER',
  // Row 10.0, the three remaining settings.md §10.0 conditions: fiscal
  // classification coverage, channel-payment coverage and secret-rotation age.
  // All three are ad hoc checks like the template one above, with no
  // `OnboardingStep` of their own — see `OnboardingReadinessChecks`.
  FISCAL_CLASSIFICATION_INCOMPLETE: 'settings.home.readiness.code.FISCAL_CLASSIFICATION_INCOMPLETE',
  CHANNEL_NO_PAYMENT_METHOD: 'settings.home.readiness.code.CHANNEL_NO_PAYMENT_METHOD',
  // Batch 16: the two checks batch 15 left — channel fulfilment-mode coverage
  // and location service-binding coverage.
  CHANNEL_NO_FULFILLMENT_MODE: 'settings.home.readiness.code.CHANNEL_NO_FULFILLMENT_MODE',
  CHANNEL_NO_SERVICEABLE_MODE: 'settings.home.readiness.code.CHANNEL_NO_SERVICEABLE_MODE',
  LOCATION_NO_SERVICE_SCHEDULE: 'settings.home.readiness.code.LOCATION_NO_SERVICE_SCHEDULE',
  INSTALLATION_SECRET_ROTATION_DUE: 'settings.home.readiness.code.INSTALLATION_SECRET_ROTATION_DUE',
  MERCHANT_SECRET_ROTATION_DUE: 'settings.home.readiness.code.MERCHANT_SECRET_ROTATION_DUE',
  // Batch 18: the forced-closed row the spec calls its most valuable, the branch no channel
  // reaches, and the first member of the expiring tier.
  LOCATION_FORCED_CLOSED_NO_EXPIRY: 'settings.home.readiness.code.LOCATION_FORCED_CLOSED_NO_EXPIRY',
  LOCATION_NO_SALES_CHANNEL: 'settings.home.readiness.code.LOCATION_NO_SALES_CHANNEL',
  LOCATION_FISCAL_ASSIGNMENT_ENDING:
    'settings.home.readiness.code.LOCATION_FISCAL_ASSIGNMENT_ENDING',
};

/**
 * Where a finding deep-links to. A location-scoped finding always wins (more
 * specific than any code-keyed guess); a handful of tenant/brand-scoped codes
 * still have an obvious door even with no location attached — wave 9 adds the
 * catalogue readiness codes now that `CatalogReadinessValidate` names every
 * offending brand instead of stopping at the first (gap map row 10.0).
 */
function readinessLink(finding: ValidationResult): readonly string[] | null {
  // A branch no channel reaches is named by its location, but it is mended on the channel's list
  // of locations, not in the branch's own hours (settings.md §10.2a: «Каналы … links to 10.4»), so
  // this code wins over the generic location rule below.
  if (finding.errorCode === 'LOCATION_NO_SALES_CHANNEL') {
    return ['/settings/sales-channels'];
  }
  if (finding.locationId) {
    return ['/settings/locations', finding.locationId];
  }
  // A finding's `subject` names the object it is about, but the link goes by
  // error code alone. The per-channel setup hub configures a Telegram bot, a
  // web hostname or a kiosk stub; it has no control that enables a fulfilment
  // mode, sets a payment method or binds a location, which are the three
  // channel findings the server sends. Those are fixed on the sales-channels
  // screen (the matrices and the channel's location list), so a channel
  // subject must not send the operator to the hub.
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
  if (finding.errorCode === 'FISCAL_CLASSIFICATION_INCOMPLETE') {
    return ['/settings/fiscalization'];
  }
  if (
    finding.errorCode === 'CHANNEL_NO_PAYMENT_METHOD' ||
    finding.errorCode === 'CHANNEL_NO_FULFILLMENT_MODE' ||
    finding.errorCode === 'CHANNEL_NO_SERVICEABLE_MODE'
  ) {
    return ['/settings/sales-channels'];
  }
  if (
    finding.errorCode === 'INSTALLATION_SECRET_ROTATION_DUE' ||
    finding.errorCode === 'MERCHANT_SECRET_ROTATION_DUE'
  ) {
    return ['/settings/integrations'];
  }
  return null;
}

/**
 * One row of the readiness list, with the key `@for` tracks it by.
 *
 * A check that names N offending items sends N findings that differ only in
 * their `detail`, and two items can read exactly alike (two CLICK merchant
 * accounts, say), so no field of a finding is a safe identity on its own. The
 * key is therefore the finding's own fields plus how many identical findings
 * came before it: unique whatever the server sends, and stable while the same
 * list is shown again.
 */
interface ReadinessRow {
  readonly key: string;
  readonly finding: ValidationResult;
}

function withUniqueKeys(findings: readonly ValidationResult[]): readonly ReadinessRow[] {
  const seen = new Map<string, number>();
  return findings.map((finding) => {
    const base = [
      finding.stepKey,
      finding.locationId ?? '',
      finding.subject ? `${finding.subject.type}:${finding.subject.id}` : '',
      finding.errorCode ?? '',
      finding.detail ?? '',
    ].join('|');
    const ordinal = seen.get(base) ?? 0;
    seen.set(base, ordinal + 1);
    return { key: `${base}#${ordinal}`, finding };
  });
}

/**
 * The settings tile a finding's link lands on, so the index can carry the same numbers the panel
 * does (settings.md §10.0: «Numbers here and in the readiness panel come from the same query»). A
 * nav item's `path` is relative to `/settings/` except the one that points out of Settings.
 */
function tilePathOf(link: readonly string[] | null): string | null {
  const target = link?.[0];
  if (!target) {
    return null;
  }
  return target.startsWith('/settings/') ? target.slice('/settings/'.length) : target;
}

/**
 * 10.0 Settings home — `docs/operations-spec/settings.md` §10.0.
 *
 * **The readiness panel**, added in wave P31: not the spec's full
 * multi-source table, but `OnboardingController.validate`
 * reshaped into exactly what it can honestly answer today — every
 * `VALIDATING`-phase check, every offending item named rather than only the
 * first (see `OnboardingService.validationResultsFor`), plus the ad hoc checks
 * that ride along without an `OnboardingStep`: SMS-template moderation, and —
 * batch 15 — fiscal classification coverage, channel payment-method coverage
 * and secret-rotation age, then — batch 16 — channel fulfilment-mode coverage
 * and location service-binding coverage. A finding that names one channel
 * carries a `subject` (it tells two like-worded rows apart), but its row links
 * by error code: the fix lives on the sales-channels screen, not in the
 * channel's setup hub. Findings sort by tier (blocking → expiring → advisory, the
 * server names it in `severity`) and then by how many items offend the same
 * condition, largest first (`readiness-order.ts`); an expiring or advisory one
 * carries a muted tag rather than reading as a stop-the-line error. The index
 * tiles carry the same findings as live numbers («2 blocking»), counted from
 * the list the panel shows so the two cannot disagree. The empty state ("Всё настроено") is the same one
 * settings.md asks for.
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
  protected readonly rows = computed(() => withUniqueKeys(this.findings()));

  /** «2 blocking · 1 expiring · 3 advisory» above the list — the same numbers the tiles show. */
  protected readonly summary = computed<TierCounts>(() => countByTier(this.findings()));

  /**
   * Per screen, how many findings link to it (settings.md §10.0 index: «where cheap, a live number»).
   * Built from the findings the panel lists, never from a second read, so a tile and the panel cannot
   * disagree. A finding that links outside Settings (the catalogue's publication screen) has no tile.
   */
  protected readonly tileCounts = computed<ReadonlyMap<string, TierCounts>>(() => {
    const byTile = new Map<string, ValidationResult[]>();
    for (const finding of this.findings()) {
      const tile = tilePathOf(readinessLink(finding));
      if (tile) {
        byTile.set(tile, [...(byTile.get(tile) ?? []), finding]);
      }
    }
    return new Map([...byTile].map(([tile, findings]) => [tile, countByTier(findings)]));
  });

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

  /**
   * Which one it is (settings.md §10.0: «each row states the scope»). The fixed
   * sentence above says what is wrong in the operator's language; the server's
   * `detail` names the brand, channel, provider connection or legal entity it
   * is about, so N offending items read as N different rows. A code with no
   * sentence of its own already shows `detail` as its whole message, so it is
   * not repeated. The detail is the server's English text with tenant-chosen
   * codes and names in it, never a secret or a personal value (ADR 0028).
   */
  protected readinessScope(finding: ValidationResult): string | null {
    const known =
      finding.errorCode !== null && READINESS_CODE_KEYS[finding.errorCode] !== undefined;
    return known ? finding.detail : null;
  }

  protected readinessLink(finding: ValidationResult): readonly string[] | null {
    return readinessLink(finding);
  }

  protected isAdvisory(finding: ValidationResult): boolean {
    return tierOf(finding) === 'ADVISORY';
  }

  protected isExpiring(finding: ValidationResult): boolean {
    return tierOf(finding) === 'EXPIRING';
  }

  /** The numbers under a tile; absent (not zero) for a screen nothing links to. */
  protected countsFor(path: string): TierCounts | null {
    return this.tileCounts().get(path) ?? null;
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
      this.findings.set(orderFindings(outcome.checks.filter((check) => !check.passed)));
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
