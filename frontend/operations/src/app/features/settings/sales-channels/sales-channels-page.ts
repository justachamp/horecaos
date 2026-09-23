import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ColorInput } from '../../../shared/ui/color-input';
import { MatrixGrid } from '../../../shared/ui/matrix-grid/matrix-grid';
import {
  MatrixBulkToggleEvent,
  MatrixCell,
  MatrixHeader,
} from '../../../shared/ui/matrix-grid/matrix-grid-types';
import { describeApiError } from '../../orders/order-errors';
import { InstallationView, IntegrationsApi } from '../integrations/integrations-api';
import { LocationView, LocationsApi } from '../locations/locations-api';
import { PaymentMethodView, PaymentMethodsApi } from '../payment-methods/payment-methods-api';
import {
  CHANNEL_SOCIAL_PLATFORMS,
  ChannelMatrices,
  ChannelView,
  CreateChannelRequest,
  SalesChannelsApi,
  UpdateChannelRequest,
} from './sales-channels-api';

/** One row of the edit panel's social-links list. */
interface SocialLinkDraft {
  readonly platform: string;
  url: string;
}

/** ADR 0036's closed system-type set. */
export const CHANNEL_SYSTEM_TYPES: readonly string[] = [
  'WEB',
  'IOS',
  'ANDROID',
  'TELEGRAM',
  'KIOSK',
  'QR_TABLE',
  'CALL_CENTRE',
  'AGGREGATOR',
  'POS',
];

export const FULFILLMENT_MODES: readonly string[] = ['DELIVERY', 'PICKUP', 'DINE_IN'];

/** 10.4a's own severity order: broken-and-live first, then working, then off, then gone. */
type Severity = 0 | 1 | 2 | 3 | 4;

/**
 * 10.4 Sales channels — `docs/operations-spec/settings.md` §10.4.
 *
 * A list over a matrix, as the spec frames it: the list is the registry
 * (10.4a, all eleven of its own columns now, including the three this wave
 * adds — branches, payment-method count, fulfilment-type chips — and the
 * four {@link ChannelView} already carried but nothing rendered); the matrix
 * is the capability grid (10.4b), rows = every channel so a cash toggle for
 * six channels is one gesture instead of six row visits.
 *
 * Payment-method columns come from `PaymentMethodsApi` (wave P33's own
 * registry, row 10.6) rather than a frontend constant — the fix for the live
 * 500 the operations gap map names: `payment_method_code` has been a foreign
 * key onto the registry since V0175, and a hard-coded `['CASH','CLICK',
 * 'PAYME']` on a tenant that never registered one of them used to raise an
 * untranslated `DataIntegrityViolationException`. `SalesChannelService`
 * translates that violation now too, so a stale column here fails as a
 * sentence rather than a stack trace either way.
 */
@Component({
  selector: 'q-sales-channels-page',
  imports: [TPipe, MatrixGrid, ColorInput],
  templateUrl: './sales-channels-page.html',
  styleUrl: './sales-channels-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SalesChannelsPage {
  private readonly api = inject(SalesChannelsApi);
  private readonly paymentMethodsApi = inject(PaymentMethodsApi);
  private readonly integrations = inject(IntegrationsApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly channels = signal<readonly ChannelView[]>([]);
  protected readonly paymentMethods = signal<readonly PaymentMethodView[]>([]);
  protected readonly installations = signal<readonly InstallationView[]>([]);
  protected readonly locations = signal<readonly LocationView[]>([]);
  protected readonly matricesByChannel = signal<Readonly<Record<string, ChannelMatrices>>>({});
  protected readonly matricesSaving = signal(false);
  protected readonly matrixError = signal<string | null>(null);

  protected readonly systemTypes = CHANNEL_SYSTEM_TYPES;
  protected readonly fulfillmentModes = FULFILLMENT_MODES;

  protected readonly typeFilter = signal('');
  protected readonly statusFilter = signal('');
  protected readonly onlyProblems = signal(false);

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newCode = signal('');
  protected readonly newSystemType = signal<string>(CHANNEL_SYSTEM_TYPES[0]);
  protected readonly newDisplayName = signal('');

  protected readonly selectedChannelId = signal<string | null>(null);
  protected readonly editDisplayName = signal('');
  protected readonly editPricePlaneChannelId = signal('');
  protected readonly editExternallyPriced = signal(false);
  protected readonly editGuestOrdersAllowed = signal(true);
  protected readonly editInstallationId = signal('');
  protected readonly editLocationIds = signal<ReadonlySet<string>>(new Set());
  protected readonly rowSaving = signal(false);
  protected readonly rowError = signal<string | null>(null);

  // ------------------------------------------------------- 10.4a presentation
  protected readonly editIcon = signal('');
  protected readonly editBrandColorPrimary = signal('');
  protected readonly editBrandColorSecondary = signal('');
  protected readonly editSocialLinks = signal<readonly SocialLinkDraft[]>([]);
  protected readonly newSocialPlatform = signal<string>(CHANNEL_SOCIAL_PLATFORMS[0]);
  protected readonly newSocialUrl = signal('');
  protected readonly socialPlatforms = CHANNEL_SOCIAL_PLATFORMS;

  /** {@link CHANNEL_SOCIAL_PLATFORMS} not already on the draft list. */
  protected readonly availableSocialPlatforms = computed(() => {
    const used = new Set(this.editSocialLinks().map((row) => row.platform));
    return this.socialPlatforms.filter((platform) => !used.has(platform));
  });

  constructor() {
    void this.load();
  }

  protected readonly installationName = computed(() => {
    const byId = new Map(
      this.installations().map((installation) => [installation.id, installation.displayName]),
    );
    return (id: string | null) => (id ? (byId.get(id) ?? id) : null);
  });

  protected readonly channelName = computed(() => {
    const byId = new Map(this.channels().map((channel) => [channel.id, channel.displayName]));
    return (id: string | null) => (id ? (byId.get(id) ?? id) : null);
  });

  private severityOf(channel: ChannelView): Severity {
    if (channel.status === 'ARCHIVED') {
      return 4;
    }
    if (channel.status === 'INACTIVE') {
      return 3;
    }
    // ACTIVE from here.
    if (channel.enabledPaymentMethodCount === 0 || channel.enabledFulfillmentModes.length === 0) {
      return 0;
    }
    if (channel.locationCount === 0) {
      return 1;
    }
    return 2;
  }

  protected hasProblem(channel: ChannelView): boolean {
    return this.severityOf(channel) <= 1;
  }

  protected readonly filteredChannels = computed<readonly ChannelView[]>(() => {
    const type = this.typeFilter();
    const status = this.statusFilter();
    const problemsOnly = this.onlyProblems();
    return this.channels()
      .filter((channel) => !type || channel.systemType === type)
      .filter((channel) => !status || channel.status === status)
      .filter((channel) => !problemsOnly || this.hasProblem(channel))
      .slice()
      .sort((a, b) => {
        const severity = this.severityOf(a) - this.severityOf(b);
        return severity !== 0 ? severity : a.displayName.localeCompare(b.displayName);
      });
  });

  // ---------------------------------------------------------------- create

  protected canCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.newCode().trim().length > 0 &&
      this.newDisplayName().trim().length > 0
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: CreateChannelRequest = {
      code: this.newCode().trim().toUpperCase(),
      systemType: this.newSystemType(),
      displayName: this.newDisplayName().trim(),
      externallyPriced: false,
      guestOrdersAllowed: true,
    };
    try {
      await this.api.create(scope, request);
      this.showCreateForm.set(false);
      this.newCode.set('');
      this.newDisplayName.set('');
      await this.reloadAll(scope);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  // ------------------------------------------------------------------ edit

  protected selectChannel(channel: ChannelView): void {
    if (this.selectedChannelId() === channel.id) {
      this.selectedChannelId.set(null);
      return;
    }
    this.selectedChannelId.set(channel.id);
    this.editDisplayName.set(channel.displayName);
    this.editPricePlaneChannelId.set(channel.pricePlaneChannelId ?? '');
    this.editExternallyPriced.set(channel.externallyPriced);
    this.editGuestOrdersAllowed.set(channel.guestOrdersAllowed);
    this.editInstallationId.set(channel.providerInstallationId ?? '');
    const bound = this.matricesByChannel()[channel.id]?.locationIds ?? [];
    this.editLocationIds.set(new Set(bound));
    this.editIcon.set(channel.icon ?? '');
    this.editBrandColorPrimary.set(channel.brandColorPrimary ?? '');
    this.editBrandColorSecondary.set(channel.brandColorSecondary ?? '');
    const links = this.matricesByChannel()[channel.id]?.socialLinks ?? {};
    this.editSocialLinks.set(Object.entries(links).map(([platform, url]) => ({ platform, url })));
    this.newSocialPlatform.set(CHANNEL_SOCIAL_PLATFORMS[0]);
    this.newSocialUrl.set('');
    this.rowError.set(null);
  }

  protected toggleLocation(locationId: string): void {
    this.editLocationIds.update((current) => {
      const next = new Set(current);
      if (next.has(locationId)) {
        next.delete(locationId);
      } else {
        next.add(locationId);
      }
      return next;
    });
  }

  // ------------------------------------------------------- 10.4a presentation

  /** `q-color-input` only ever emits a complete hex value; an empty draft (unset) never calls this. */
  protected setBrandColorPrimary(hex: string): void {
    this.editBrandColorPrimary.set(hex);
  }

  protected setBrandColorSecondary(hex: string): void {
    this.editBrandColorSecondary.set(hex);
  }

  protected clearBrandColorPrimary(): void {
    this.editBrandColorPrimary.set('');
  }

  protected clearBrandColorSecondary(): void {
    this.editBrandColorSecondary.set('');
  }

  protected addSocialLink(): void {
    const platform = this.newSocialPlatform();
    const url = this.newSocialUrl().trim();
    if (!platform || !url || this.editSocialLinks().some((row) => row.platform === platform)) {
      return;
    }
    this.editSocialLinks.update((rows) => [...rows, { platform, url }]);
    this.newSocialUrl.set('');
    const next = this.availableSocialPlatforms().find((candidate) => candidate !== platform);
    this.newSocialPlatform.set(next ?? platform);
  }

  protected removeSocialLink(platform: string): void {
    this.editSocialLinks.update((rows) => rows.filter((row) => row.platform !== platform));
  }

  protected async saveEdit(channel: ChannelView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.rowSaving.set(true);
    this.rowError.set(null);
    const request: UpdateChannelRequest = {
      displayName: this.editDisplayName().trim(),
      pricePlaneChannelId: this.editPricePlaneChannelId() || null,
      externallyPriced: this.editExternallyPriced(),
      guestOrdersAllowed: this.editGuestOrdersAllowed(),
      providerInstallationId: this.editInstallationId() || null,
      icon: this.editIcon().trim() || undefined,
      brandColorPrimary: this.editBrandColorPrimary() || undefined,
      brandColorSecondary: this.editBrandColorSecondary() || undefined,
    };
    try {
      const updated = await this.api.update(scope, channel.id, request, channel.version);
      await this.api.replaceLocations(
        scope,
        channel.id,
        Array.from(this.editLocationIds()),
        updated.version,
      );
      // #update then #replaceLocations each bump the channel's own version
      // by exactly one (SalesChannelService's own doc); +1 here is that same
      // arithmetic carried one call further, not a guess.
      const links = Object.fromEntries(this.editSocialLinks().map((row) => [row.platform, row.url]));
      await this.api.replaceSocialLinks(scope, channel.id, links, updated.version + 1);
      await this.reloadAll(scope);
      this.selectedChannelId.set(null);
    } catch (error) {
      this.rowError.set(this.describe(error));
    } finally {
      this.rowSaving.set(false);
    }
  }

  protected async deactivate(channel: ChannelView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.deactivate(scope, channel.id, channel.version);
      await this.reloadAll(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  protected async reactivate(channel: ChannelView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      await this.api.reactivate(scope, channel.id, channel.version);
      await this.reloadAll(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  protected async archiveChannel(channel: ChannelView): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    if (
      !confirm(this.i18n.t('settings.salesChannels.archive.confirm', { name: channel.displayName }))
    ) {
      return;
    }
    try {
      await this.api.archive(scope, channel.id, channel.version);
      await this.reloadAll(scope);
    } catch (error) {
      this.loadError.set(this.describe(error));
    }
  }

  // ----------------------------------------------------------- the matrix

  /**
   * A `TERMINAL`-responsibility method needs a fiscal-capable terminal bound
   * at a location the channel actually serves (ADR 0038). This wave hatches
   * the one necessary condition it can prove without a cross-brand fiscal-
   * terminal directory read: a channel bound to zero locations definitely
   * has none. A channel with locations but none of them carrying a live
   * terminal is P34/P35's own fuller check, not yet cross-referenced here.
   */
  protected unavailable(method: PaymentMethodView, channel: ChannelView): boolean {
    return method.responsibility === 'TERMINAL' && channel.locationCount === 0;
  }

  protected readonly matrixRowHeaders = computed<readonly MatrixHeader[]>(() =>
    this.channels()
      .filter((channel) => channel.status !== 'ARCHIVED')
      .map((channel) => ({ id: channel.id, label: channel.displayName })),
  );

  protected readonly paymentMatrixColumns = computed<readonly MatrixHeader[]>(() =>
    this.paymentMethods().map((method) => ({ id: method.code, label: method.displayName })),
  );

  protected readonly paymentMatrixCells = computed<readonly MatrixCell[]>(() => {
    const matrices = this.matricesByChannel();
    const methods = this.paymentMethods();
    const cells: MatrixCell[] = [];
    for (const channel of this.channels()) {
      if (channel.status === 'ARCHIVED') {
        continue;
      }
      const matrix = matrices[channel.id];
      for (const method of methods) {
        cells.push({
          rowId: channel.id,
          colId: method.code,
          state: this.unavailable(method, channel)
            ? 'UNAVAILABLE'
            : matrix?.paymentMethods[method.code] === true
              ? 'ON'
              : 'OFF',
        });
      }
    }
    return cells;
  });

  protected readonly fulfillmentMatrixColumns = computed<readonly MatrixHeader[]>(() =>
    this.fulfillmentModes.map((mode) => ({ id: mode, label: mode })),
  );

  protected readonly fulfillmentMatrixCells = computed<readonly MatrixCell[]>(() => {
    const matrices = this.matricesByChannel();
    const cells: MatrixCell[] = [];
    for (const channel of this.channels()) {
      if (channel.status === 'ARCHIVED') {
        continue;
      }
      const matrix = matrices[channel.id];
      for (const mode of this.fulfillmentModes) {
        cells.push({
          rowId: channel.id,
          colId: mode,
          state: matrix?.fulfillmentModes[mode] === true ? 'ON' : 'OFF',
        });
      }
    }
    return cells;
  });

  protected async onPaymentMatrixToggle(event: MatrixBulkToggleEvent): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const byChannel = new Map<string, Record<string, boolean>>();
    for (const change of event.changes) {
      const current = byChannel.get(change.rowId) ?? {};
      current[change.colId] = change.nextState === 'ON';
      byChannel.set(change.rowId, current);
    }

    this.matricesSaving.set(true);
    this.matrixError.set(null);
    const matrices = this.matricesByChannel();
    try {
      for (const [channelId, changes] of byChannel) {
        const channel = this.channels().find((c) => c.id === channelId);
        if (!channel) {
          continue;
        }
        const next = { ...(matrices[channelId]?.paymentMethods ?? {}), ...changes };
        const stillEnabled = Object.values(next).some(Boolean);
        if (channel.status === 'ACTIVE' && !stillEnabled) {
          const confirmed = confirm(
            this.i18n.t('settings.salesChannels.matrix.lastMethodConfirm', {
              name: channel.displayName,
            }),
          );
          if (!confirmed) {
            continue;
          }
        }
        await this.api.replacePaymentMethods(scope, channelId, next, channel.version);
      }
      await this.reloadAll(scope);
    } catch (error) {
      this.matrixError.set(this.describe(error));
    } finally {
      this.matricesSaving.set(false);
    }
  }

  protected async onFulfillmentMatrixToggle(event: MatrixBulkToggleEvent): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const byChannel = new Map<string, Record<string, boolean>>();
    for (const change of event.changes) {
      const current = byChannel.get(change.rowId) ?? {};
      current[change.colId] = change.nextState === 'ON';
      byChannel.set(change.rowId, current);
    }

    this.matricesSaving.set(true);
    this.matrixError.set(null);
    const matrices = this.matricesByChannel();
    try {
      for (const [channelId, changes] of byChannel) {
        const channel = this.channels().find((c) => c.id === channelId);
        if (!channel) {
          continue;
        }
        const next = { ...(matrices[channelId]?.fulfillmentModes ?? {}), ...changes };
        await this.api.replaceFulfillmentModes(scope, channelId, next, channel.version);
      }
      await this.reloadAll(scope);
    } catch (error) {
      this.matrixError.set(this.describe(error));
    } finally {
      this.matricesSaving.set(false);
    }
  }

  // ------------------------------------------------------------------ load

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      return;
    }
    try {
      const [channels, methods, installations, locations] = await Promise.all([
        this.api.list(scope),
        this.paymentMethodsApi.list(scope),
        this.integrations.listInstallations(scope),
        this.locationsApi.list(scope),
      ]);
      this.channels.set(channels);
      this.paymentMethods.set(methods);
      this.installations.set(installations);
      this.locations.set(locations);
      await this.loadMatrices(scope, channels);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else {
        this.loadError.set(this.describe(error));
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async loadMatrices(
    scope: NonNullable<ReturnType<CurrentLocation['scope']>>,
    channels: readonly ChannelView[],
  ): Promise<void> {
    const active = channels.filter((channel) => channel.status !== 'ARCHIVED');
    const entries = await Promise.all(
      active.map(async (channel): Promise<readonly [string, ChannelMatrices]> => [
        channel.id,
        await this.api.matrices(scope, channel.id),
      ]),
    );
    this.matricesByChannel.set(Object.fromEntries(entries));
  }

  private async reloadAll(scope: NonNullable<ReturnType<CurrentLocation['scope']>>): Promise<void> {
    const channels = await this.api.list(scope);
    this.channels.set(channels);
    await this.loadMatrices(scope, channels);
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
