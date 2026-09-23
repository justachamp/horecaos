import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { LocationScope } from '../../../core/api/operations-paths';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { MediaApi } from '../../catalog/media-api';
import { describeApiError } from '../../orders/order-errors';
import { MediaUploader } from '../../../shared/ui/media-uploader';
import { DineInApi, DineInSettingsView, QrMode } from '../locations/dinein-api';
import { LocationView, LocationsApi } from '../locations/locations-api';
import { InstallationView, IntegrationsApi } from '../integrations/integrations-api';
import { ChannelView, SalesChannelsApi } from '../sales-channels/sales-channels-api';
import {
  CHANNEL_PAGE_SLUGS,
  ChannelHostnameView,
  ChannelPageVersionView,
  ChannelPresentationView,
  ChannelSetupApi,
} from './channel-setup-api';

const LOCALES = ['ru', 'uz-Latn', 'en'] as const;

/** One bound location's own QR dine-in mode, for the read-only facet (d) summary. */
interface LocationQrMode {
  readonly locationId: string;
  readonly locationName: string;
  readonly qrMode: QrMode;
}

/**
 * Row 10.5's channel setup hub — `docs/operations-spec/settings.md` §10.5.
 *
 * <p>Type-dispatched, matching the spec's own framing ("the form differs
 * entirely by system_type"): each facet card renders only for the channel
 * types the spec assigns it to. What is built here (a) links to the
 * Telegram bot's own status on the integrations hub rather than duplicating
 * it, (b)/(c) are the new hostname mapping and SEO/pages tables (V0403/
 * V0404), (d) reads the channel's bound locations' own QR mode — configured
 * per *location* since ADR 0047, not per channel, see {@code
 * ChannelSetupService}'s own doc for why this hub does not add a
 * channel-scoped field that would contradict that decision — and (e) is
 * honestly locked: no kiosk device pairing exists yet (settings.md 10.5's
 * own KIOSK section declines the hardware/device integration for now).
 */
@Component({
  selector: 'q-channel-setup-page',
  imports: [TPipe, RouterLink, MediaUploader],
  templateUrl: './channel-setup-page.html',
  styleUrl: './channel-setup-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChannelSetupPage {
  private readonly channelsApi = inject(SalesChannelsApi);
  private readonly setupApi = inject(ChannelSetupApi);
  private readonly integrationsApi = inject(IntegrationsApi);
  private readonly locationsApi = inject(LocationsApi);
  private readonly dineInApi = inject(DineInApi);
  private readonly mediaApi = inject(MediaApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  readonly channelId = input.required<string>();

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly channel = signal<ChannelView | null>(null);

  protected readonly locales = LOCALES;
  protected readonly pageSlugs = CHANNEL_PAGE_SLUGS;

  // -------------------------------------------------------------- (a) bot
  protected readonly installation = signal<InstallationView | null>(null);

  // --------------------------------------------------------- (b) hostname
  protected readonly hostname = signal<ChannelHostnameView | null>(null);
  protected readonly hostnameMode = signal<'subdomain' | 'custom'>('subdomain');
  protected readonly hostnameSlug = signal('');
  protected readonly hostnameCustom = signal('');
  protected readonly hostnameSaving = signal(false);
  protected readonly hostnameError = signal<string | null>(null);
  protected readonly baseDomain = 'stores.horecaos.uz';

  // ------------------------------------------------------- (c) presentation
  protected readonly presentation = signal<ChannelPresentationView | null>(null);
  protected readonly seoTitle = signal('');
  protected readonly seoDescription = signal('');
  protected readonly ogImageAssetId = signal<string | null>(null);
  protected readonly ogImagePreviewUrl = signal<string | null>(null);
  protected readonly presentationSaving = signal(false);
  protected readonly uploadingOgImage = signal(false);
  protected readonly presentationError = signal<string | null>(null);

  protected readonly pages = signal<Readonly<Record<string, ChannelPageVersionView>>>({});
  protected readonly editingSlug = signal<string | null>(null);
  protected readonly pageDrafts = signal<Readonly<Record<string, string>>>({});
  protected readonly pageSaving = signal(false);
  protected readonly pageError = signal<string | null>(null);

  // --------------------------------------------------------- (d) dine-in
  protected readonly locationQrModes = signal<readonly LocationQrMode[]>([]);

  protected readonly version = computed(() => this.channel()?.version ?? 0);

  constructor() {
    void this.load();
  }

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
      const channels = await this.channelsApi.list(scope);
      const found = channels.find((candidate) => candidate.id === this.channelId()) ?? null;
      this.channel.set(found);
      if (!found) {
        this.loadError.set(this.i18n.t('settings.channelSetup.notFound'));
        return;
      }
      await this.loadFacets(scope, found);
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        this.denied.set(true);
      } else if (error instanceof ApiError) {
        this.loadError.set(describeApiError(error, (key, values) => this.i18n.t(key, values)));
      } else {
        throw error;
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async loadFacets(scope: LocationScope, channel: ChannelView): Promise<void> {
    if (channel.systemType === 'TELEGRAM' && channel.providerInstallationId) {
      const installations = await this.integrationsApi.listInstallations(scope);
      this.installation.set(
        installations.find((candidate) => candidate.id === channel.providerInstallationId) ?? null,
      );
    }

    if (channel.systemType === 'WEB') {
      const [hostname, presentation] = await Promise.all([
        this.setupApi.hostname(scope, channel.id),
        this.setupApi.presentation(scope, channel.id),
      ]);
      this.hostname.set(hostname);
      this.presentation.set(presentation);
      this.seoTitle.set(presentation.seoTitle ?? '');
      this.seoDescription.set(presentation.seoDescription ?? '');
      this.ogImageAssetId.set(presentation.ogImageAssetId);
      if (presentation.ogImageAssetId) {
        try {
          this.ogImagePreviewUrl.set(
            await firstValueFrom(this.mediaApi.downloadUrl(scope.tenantId, presentation.ogImageAssetId, 'THUMBNAIL')),
          );
        } catch {
          this.ogImagePreviewUrl.set(null);
        }
      }
      const pageEntries = await Promise.all(
        this.pageSlugs.map(async (slug) => [slug, await this.setupApi.currentPage(scope, channel.id, slug)] as const),
      );
      this.pages.set(Object.fromEntries(pageEntries));
    }

    if (channel.systemType === 'QR_TABLE') {
      const [matrices, locations] = await Promise.all([
        this.channelsApi.matrices(scope, channel.id),
        this.locationsApi.list(scope),
      ]);
      const byId = new Map<string, LocationView>(locations.map((location) => [location.id, location]));
      const settled = await Promise.all(
        matrices.locationIds.map(async (locationId) => {
          const locationScope: LocationScope = { tenantId: scope.tenantId, brandId: scope.brandId, locationId };
          try {
            const settings: DineInSettingsView = await this.dineInApi.settings(locationScope);
            return {
              locationId,
              locationName: byId.get(locationId)?.displayName ?? locationId,
              qrMode: settings.qrMode,
            } satisfies LocationQrMode;
          } catch {
            return null;
          }
        }),
      );
      this.locationQrModes.set(settled.filter((row): row is LocationQrMode => row !== null));
    }
  }

  // --------------------------------------------------------- (b) hostname

  protected canClaimSubdomain(): boolean {
    return !this.hostnameSaving() && this.hostnameSlug().trim().length > 0;
  }

  protected canClaimCustom(): boolean {
    return !this.hostnameSaving() && this.hostnameCustom().trim().includes('.');
  }

  protected async claimSubdomain(): Promise<void> {
    const scope = this.location.scope();
    const channel = this.channel();
    if (!scope || !channel || !this.canClaimSubdomain()) {
      return;
    }
    this.hostnameSaving.set(true);
    this.hostnameError.set(null);
    try {
      const updated = await this.setupApi.setSubdomain(scope, channel.id, this.hostnameSlug().trim(), this.version());
      this.hostname.set(updated);
      this.channel.update((current) => (current ? { ...current, version: current.version + 1 } : current));
      this.hostnameSlug.set('');
    } catch (error) {
      this.hostnameError.set(this.describe(error));
    } finally {
      this.hostnameSaving.set(false);
    }
  }

  protected async claimCustomHostname(): Promise<void> {
    const scope = this.location.scope();
    const channel = this.channel();
    if (!scope || !channel || !this.canClaimCustom()) {
      return;
    }
    this.hostnameSaving.set(true);
    this.hostnameError.set(null);
    try {
      const updated = await this.setupApi.setCustomHostname(scope, channel.id, this.hostnameCustom().trim(), this.version());
      this.hostname.set(updated);
      this.channel.update((current) => (current ? { ...current, version: current.version + 1 } : current));
      this.hostnameCustom.set('');
    } catch (error) {
      this.hostnameError.set(this.describe(error));
    } finally {
      this.hostnameSaving.set(false);
    }
  }

  protected async verifyHostname(): Promise<void> {
    const scope = this.location.scope();
    const channel = this.channel();
    if (!scope || !channel) {
      return;
    }
    this.hostnameSaving.set(true);
    this.hostnameError.set(null);
    try {
      const updated = await this.setupApi.verifyHostname(scope, channel.id, this.version());
      this.hostname.set(updated);
      this.channel.update((current) => (current ? { ...current, version: current.version + 1 } : current));
    } catch (error) {
      this.hostnameError.set(this.describe(error));
    } finally {
      this.hostnameSaving.set(false);
    }
  }

  protected async clearHostname(): Promise<void> {
    const scope = this.location.scope();
    const channel = this.channel();
    if (!scope || !channel) {
      return;
    }
    this.hostnameSaving.set(true);
    this.hostnameError.set(null);
    try {
      await this.setupApi.clearHostname(scope, channel.id, this.version());
      this.hostname.set({ configured: false, hostname: null, verified: false });
      this.channel.update((current) => (current ? { ...current, version: current.version + 1 } : current));
    } catch (error) {
      this.hostnameError.set(this.describe(error));
    } finally {
      this.hostnameSaving.set(false);
    }
  }

  // ------------------------------------------------------- (c) presentation

  protected async savePresentation(): Promise<void> {
    const scope = this.location.scope();
    const channel = this.channel();
    if (!scope || !channel) {
      return;
    }
    this.presentationSaving.set(true);
    this.presentationError.set(null);
    try {
      const updated = await this.setupApi.setPresentation(
        scope,
        channel.id,
        {
          seoTitle: this.seoTitle().trim() || null,
          seoDescription: this.seoDescription().trim() || null,
          ogImageAssetId: this.ogImageAssetId(),
        },
        this.version(),
      );
      this.presentation.set(updated);
      this.channel.update((current) => (current ? { ...current, version: current.version + 1 } : current));
    } catch (error) {
      this.presentationError.set(this.describe(error));
    } finally {
      this.presentationSaving.set(false);
    }
  }

  protected async uploadOgImage(file: File): Promise<void> {
    const scope = this.location.scope();
    if (!scope || this.uploadingOgImage()) {
      return;
    }
    this.uploadingOgImage.set(true);
    this.presentationError.set(null);
    try {
      const asset = await firstValueFrom(this.mediaApi.upload(scope.tenantId, 'TENANT', scope.tenantId, 'PUBLIC', file));
      this.ogImageAssetId.set(asset.assetId);
      const previous = this.ogImagePreviewUrl();
      if (previous) {
        URL.revokeObjectURL(previous);
      }
      this.ogImagePreviewUrl.set(URL.createObjectURL(file));
    } catch (error) {
      this.presentationError.set(this.describe(error));
    } finally {
      this.uploadingOgImage.set(false);
    }
  }

  protected onMediaRejected(reason: string): void {
    this.presentationError.set(
      this.i18n.t(reason === 'tooLarge' ? 'ui.mediaUploader.tooLarge' : 'ui.mediaUploader.unsupportedType'),
    );
  }

  // -------------------------------------------------------------- pages

  protected editPage(slug: string): void {
    const current = this.pages()[slug];
    const drafts: Record<string, string> = {};
    for (const locale of this.locales) {
      drafts[localeDraftKey(slug, locale)] = current?.contentsByLocale[locale] ?? '';
    }
    this.pageDrafts.update((existing) => ({ ...existing, ...drafts }));
    this.pageError.set(null);
    this.editingSlug.set(slug);
  }

  protected cancelEditPage(): void {
    this.editingSlug.set(null);
  }

  protected draftFor(slug: string, locale: string): string {
    return this.pageDrafts()[localeDraftKey(slug, locale)] ?? '';
  }

  protected setDraft(slug: string, locale: string, value: string): void {
    this.pageDrafts.update((existing) => ({ ...existing, [localeDraftKey(slug, locale)]: value }));
  }

  /** At least one locale must carry text before Publish is enabled -- ChannelPageService's own rule, mirrored here so the button disables rather than a submit bouncing off a 400. */
  protected canPublish(slug: string): boolean {
    return this.locales.some((locale) => this.draftFor(slug, locale).trim().length > 0);
  }

  protected async publishPage(slug: string): Promise<void> {
    const scope = this.location.scope();
    const channel = this.channel();
    if (!scope || !channel || !this.canPublish(slug)) {
      return;
    }
    const contentsByLocale: Record<string, string> = {};
    for (const locale of this.locales) {
      const value = this.draftFor(slug, locale).trim();
      if (value) {
        contentsByLocale[locale] = value;
      }
    }
    this.pageSaving.set(true);
    this.pageError.set(null);
    try {
      const published = await this.setupApi.publishPage(scope, channel.id, slug, contentsByLocale);
      this.pages.update((current) => ({ ...current, [slug]: published }));
      this.editingSlug.set(null);
    } catch (error) {
      this.pageError.set(this.describe(error));
    } finally {
      this.pageSaving.set(false);
    }
  }

  protected pageSlugLabel(slug: string): string {
    switch (slug) {
      case 'about':
        return this.i18n.t('settings.channelSetup.pages.slug.about');
      case 'contacts':
        return this.i18n.t('settings.channelSetup.pages.slug.contacts');
      case 'delivery-terms':
        return this.i18n.t('settings.channelSetup.pages.slug.deliveryTerms');
      case 'privacy-offer':
        return this.i18n.t('settings.channelSetup.pages.slug.privacyOffer');
      default:
        return slug;
    }
  }

  private describe(error: unknown): string {
    return error instanceof ApiError
      ? describeApiError(error, (key, values) => this.i18n.t(key, values))
      : String(error);
  }
}

function localeDraftKey(slug: string, locale: string): string {
  return slug + '::' + locale;
}
