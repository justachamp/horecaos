import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';

import { ConfigurationApi } from '../configuration-api';
import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ActionMenu, ActionMenuItem } from '../../../shared/ui/action-menu';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { Drawer } from '../../../shared/ui/drawer';
import { StatusPill, StatusTone } from '../../../shared/ui/status-pill';
import { describeApiError } from '../../orders/order-errors';
import {
  CHANNEL_SOURCES,
  EventClassOption,
  FULFILLMENT_MODES,
  NOTIFICATION_CHANNELS,
  NotificationsApi,
  RoutingBinding,
  TemplateResponse,
  TestSendResult,
  VersionGroup,
  WIRED_CHANNELS,
  WordingResponse,
} from './notifications-api';
import { TemplateEditor } from './template-editor';

type NotificationsTab = 'templates' | 'routing';
type DrawerMode = 'versions' | 'editor' | null;
type LocaleTag = 'ru' | 'uz-Latn' | 'en';

const NOTIFICATION_CLASSES: readonly string[] = [
  'TRANSACTIONAL_REQUIRED',
  'TRANSACTIONAL_OPTIONAL',
  'MARKETING',
  'SECURITY',
  'OPERATIONS_ALERT',
];

const PAYMENT_LINK_CODE = 'notifications.payment_link_auto_send';
const AGGREGATOR_SHIFT_CODE = 'notifications.aggregator_shift_notifications_enabled';

/**
 * 10.9 Notifications — `docs/operations-gap-map.md` rows `10.9a`-`10.9d`,
 * `X.27` (wave P36).
 *
 * **Tab 1 (Шаблоны)** stops being create-only: a template row opens a
 * versions drawer (`10.9a`) whose moderation column and publish-time warning
 * surface ADR 0091's state (`10.9c`) — the silent failure this wave's brief
 * names by name: a merchant used to publish an OTP wording, watch it go
 * ACTIVE, and every message would be suppressed with nothing anywhere saying
 * so. Authoring itself moved to `TemplateEditor` (`X.27`), which fixes the
 * defect underneath: this page no longer hard-codes `variablesSchema: {}`.
 *
 * **Tab 2 (Маршрутизация)** is built for the first time, over
 * `TelegramRoutingController` (`10.9b`).
 *
 * **The Automation card** (`10.9d`) is two switches with no behaviour behind
 * them yet — see `NotificationConfigurationKeys`'s own doc for why that is
 * named rather than hidden.
 */
@Component({
  selector: 'q-notifications-page',
  imports: [TPipe, ActionMenu, Combobox, ConfirmDialog, Drawer, StatusPill, TemplateEditor],
  templateUrl: './notifications-page.html',
  styleUrl: './notifications-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsPage {
  private readonly api = inject(NotificationsApi);
  private readonly configApi = inject(ConfigurationApi);
  protected readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly activeTab = signal<NotificationsTab>('templates');
  protected readonly notificationClasses = NOTIFICATION_CLASSES;
  protected readonly channels = NOTIFICATION_CHANNELS;
  protected readonly wiredChannels = WIRED_CHANNELS;
  /** Gap-map row 10.9a: the variant picker's two dimensions. `''` means "any" (the wildcard, null on the wire). */
  protected readonly fulfilmentModes = FULFILLMENT_MODES;
  protected readonly channelSources = CHANNEL_SOURCES;

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly templates = signal<readonly TemplateResponse[]>([]);

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newTemplateKey = signal('');
  protected readonly newNotificationClass = signal(NOTIFICATION_CLASSES[0]);
  protected readonly newChannel = signal(NOTIFICATION_CHANNELS[0]);
  /** `''` is "every fulfilment mode" / "every channel source" — the wildcard row, sent as `null`. */
  protected readonly newFulfilmentMode = signal('');
  protected readonly newChannelSource = signal('');

  // ------------------------------------------------------------ automation

  protected readonly automationLoading = signal(true);
  protected readonly paymentLinkAutoSend = signal(false);
  protected readonly aggregatorShiftNotifications = signal(false);
  protected readonly automationSaving = signal<string | null>(null);
  private paymentLinkVersion: number | null = null;
  private aggregatorShiftVersion: number | null = null;

  // ------------------------------------------------------------- versions drawer

  protected readonly drawerMode = signal<DrawerMode>(null);
  protected readonly selectedTemplate = signal<TemplateResponse | null>(null);
  protected readonly versions = signal<readonly VersionGroup[]>([]);
  protected readonly versionsLoading = signal(false);
  protected readonly versionsError = signal<string | null>(null);
  protected readonly editingPrefill = signal<readonly WordingResponse[] | null>(null);
  protected readonly openTemplateMenu = signal<string | null>(null);
  protected readonly openVersionMenu = signal<number | null>(null);

  protected readonly activateTarget = signal<VersionGroup | null>(null);
  protected readonly activateBusy = signal(false);
  protected readonly activateError = signal<string | null>(null);

  protected readonly testSendTarget = signal<VersionGroup | null>(null);
  protected readonly testSendLocale = signal<LocaleTag>('ru');
  protected readonly testSendDestination = signal('');
  protected readonly testSendSubmitting = signal(false);
  protected readonly testSendError = signal<string | null>(null);
  protected readonly testSendResult = signal<TestSendResult | null>(null);
  protected readonly localeTags: readonly LocaleTag[] = ['ru', 'uz-Latn', 'en'];

  // ------------------------------------------------------------- Tab 2 routing

  protected readonly routingLoading = signal(true);
  protected readonly routingError = signal<string | null>(null);
  protected readonly routingBindings = signal<readonly RoutingBinding[]>([]);
  protected readonly routingEventClasses = signal<readonly EventClassOption[]>([]);
  protected readonly topicEditBindingId = signal<string | null>(null);
  protected readonly topicDraft = signal('');
  protected readonly topicSaving = signal(false);
  protected readonly topicError = signal<string | null>(null);
  protected readonly unbindTarget = signal<RoutingBinding | null>(null);
  protected readonly unbindBusy = signal(false);

  protected readonly eventClassOptions = computed<readonly ComboboxOption[]>(() =>
    this.routingEventClasses().map((entry) => ({ id: entry.eventClass, label: entry.description })),
  );

  /**
   * One shared query across every row's `q-combobox` — the event-class list
   * is a fixed sixteen options, so filtering by typing is a convenience
   * rather than the primary path (a click on the input already lists every
   * option), and a query held per row would need a `Map` for no behaviour a
   * merchant would notice, since only one row's combobox is ever open at once.
   */
  protected readonly eventClassQuery = signal('');
  protected readonly filteredEventClassOptions = computed<readonly ComboboxOption[]>(() => {
    const query = this.eventClassQuery().trim().toLowerCase();
    if (query.length === 0) {
      return this.eventClassOptions();
    }
    return this.eventClassOptions().filter(
      (option) =>
        option.id.toLowerCase().includes(query) || option.label.toLowerCase().includes(query),
    );
  });

  constructor() {
    void this.load();
  }

  // ---------------------------------------------------------------- tabs

  protected selectTab(tab: NotificationsTab): void {
    this.activeTab.set(tab);
    if (tab === 'routing' && this.routingBindings().length === 0 && !this.routingError()) {
      void this.loadRouting();
    }
  }

  // ------------------------------------------------------------- create

  protected canCreate(): boolean {
    return !this.createSubmitting() && this.newTemplateKey().trim().length > 0;
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    try {
      const templateId = await this.api.create(scope, {
        templateKey: this.newTemplateKey().trim(),
        notificationClass: this.newNotificationClass(),
        channel: this.newChannel(),
        fulfillmentMode: this.newFulfilmentMode() === '' ? null : this.newFulfilmentMode(),
        channelSource: this.newChannelSource() === '' ? null : this.newChannelSource(),
      });
      this.showCreateForm.set(false);
      this.newTemplateKey.set('');
      this.newFulfilmentMode.set('');
      this.newChannelSource.set('');
      await this.reload(scope);
      const created = this.templates().find((row) => row.id === templateId);
      if (created) {
        await this.openVersions(created);
        this.startNewVersion();
      }
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
  }

  // -------------------------------------------------------------- drawer

  protected rowActions(): readonly ActionMenuItem[] {
    return [{ id: 'versions', label: this.i18n.t('settings.notifications.action.versions') }];
  }

  protected async onRowAction(id: string, template: TemplateResponse): Promise<void> {
    this.openTemplateMenu.set(null);
    if (id === 'versions') {
      await this.openVersions(template);
    }
  }

  protected async openVersions(template: TemplateResponse): Promise<void> {
    this.selectedTemplate.set(template);
    this.drawerMode.set('versions');
    this.versionsLoading.set(true);
    this.versionsError.set(null);
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    try {
      this.versions.set(await this.api.versions(scope, template.id));
    } catch (error) {
      this.versionsError.set(this.describe(error));
    } finally {
      this.versionsLoading.set(false);
    }
  }

  protected closeDrawer(): void {
    this.drawerMode.set(null);
    this.selectedTemplate.set(null);
    this.editingPrefill.set(null);
  }

  protected startNewVersion(): void {
    const active = this.versions().find((group) => group.status === 'ACTIVE');
    this.editingPrefill.set(active ? active.locales : null);
    this.drawerMode.set('editor');
  }

  protected backToVersions(): void {
    this.drawerMode.set('versions');
  }

  protected async onVersionSaved(): Promise<void> {
    const template = this.selectedTemplate();
    const scope = this.location.scope();
    if (!template || !scope) {
      return;
    }
    this.versions.set(await this.api.versions(scope, template.id));
    this.drawerMode.set('versions');
  }

  protected versionRowMenu(group: VersionGroup): readonly ActionMenuItem[] {
    const items: ActionMenuItem[] = [];
    if (group.status === 'DRAFT') {
      items.push({ id: 'activate', label: this.i18n.t('settings.notifications.action.activate') });
    }
    if (this.selectedTemplate()?.channel === 'SMS') {
      items.push({ id: 'testSend', label: this.i18n.t('settings.notifications.action.testSend') });
    }
    return items;
  }

  protected onVersionRowAction(id: string, group: VersionGroup): void {
    this.openVersionMenu.set(null);
    if (id === 'activate') {
      this.activateTarget.set(group);
      this.activateError.set(null);
    } else if (id === 'testSend') {
      this.openTestSend(group);
    }
  }

  // ----------------------------------------------------------- activation

  protected activateWithheldState(): string | null {
    const group = this.activateTarget();
    if (!group) {
      return null;
    }
    const withheld = group.locales.find(
      (row) => row.providerReview === 'PENDING' || row.providerReview === 'REJECTED',
    );
    return withheld ? this.moderationLabel(withheld.providerReview) : null;
  }

  protected async confirmActivate(): Promise<void> {
    const group = this.activateTarget();
    const template = this.selectedTemplate();
    const scope = this.location.scope();
    if (!group || !template || !scope) {
      return;
    }
    this.activateBusy.set(true);
    this.activateError.set(null);
    try {
      await this.api.activate(scope, template.id, group.versionNumber);
      this.versions.set(await this.api.versions(scope, template.id));
      await this.reload(scope);
      this.activateTarget.set(null);
    } catch (error) {
      this.activateError.set(this.describe(error));
    } finally {
      this.activateBusy.set(false);
    }
  }

  protected cancelActivate(): void {
    if (!this.activateBusy()) {
      this.activateTarget.set(null);
    }
  }

  // ----------------------------------------------------------- test send

  protected openTestSend(group: VersionGroup): void {
    this.testSendTarget.set(group);
    this.testSendLocale.set('ru');
    this.testSendDestination.set('');
    this.testSendError.set(null);
    this.testSendResult.set(null);
  }

  protected closeTestSend(): void {
    if (!this.testSendSubmitting()) {
      this.testSendTarget.set(null);
    }
  }

  protected canSubmitTestSend(): boolean {
    return !this.testSendSubmitting() && this.testSendDestination().trim().length > 0;
  }

  protected async submitTestSend(): Promise<void> {
    const group = this.testSendTarget();
    const template = this.selectedTemplate();
    const scope = this.location.scope();
    if (!group || !template || !scope || !this.canSubmitTestSend()) {
      return;
    }
    this.testSendSubmitting.set(true);
    this.testSendError.set(null);
    this.testSendResult.set(null);
    try {
      this.testSendResult.set(
        await this.api.testSend(scope, template.id, group.versionNumber, {
          locale: this.testSendLocale(),
          destination: this.testSendDestination().trim(),
        }),
      );
    } catch (error) {
      this.testSendError.set(this.describe(error));
    } finally {
      this.testSendSubmitting.set(false);
    }
  }

  // ------------------------------------------------------------- labels

  /** Gap-map row 10.9a: which variant a row resolves for, or "any" for the wildcard default. */
  protected variantLabel(template: TemplateResponse): string {
    const parts = [template.fulfillmentMode, template.channelSource].filter(
      (part): part is string => part !== null,
    );
    return parts.length === 0
      ? this.i18n.t('settings.notifications.field.variant.any')
      : parts.join(' · ');
  }

  protected moderationLabel(review: string): string {
    switch (review) {
      case 'PENDING':
        return this.i18n.t('settings.notifications.moderation.PENDING');
      case 'APPROVED':
        return this.i18n.t('settings.notifications.moderation.APPROVED');
      case 'REJECTED':
        return this.i18n.t('settings.notifications.moderation.REJECTED');
      default:
        return this.i18n.t('settings.notifications.moderation.NOT_REQUIRED');
    }
  }

  protected moderationTone(review: string): StatusTone {
    switch (review) {
      case 'PENDING':
        return 'warning';
      case 'REJECTED':
        return 'danger';
      case 'APPROVED':
        return 'success';
      default:
        return 'none';
    }
  }

  /** The worst-of across a version's own locales — one signal a table row renders. */
  protected worstModeration(group: VersionGroup): string {
    const order = ['REJECTED', 'PENDING', 'APPROVED', 'NOT_REQUIRED'];
    let worst = 'NOT_REQUIRED';
    for (const row of group.locales) {
      if (order.indexOf(row.providerReview) < order.indexOf(worst)) {
        worst = row.providerReview;
      }
    }
    return worst;
  }

  protected localeStatus(group: VersionGroup, tag: LocaleTag): string | null {
    return group.locales.find((row) => row.locale === tag)?.status ?? null;
  }

  protected statusTone(status: string): StatusTone {
    switch (status) {
      case 'ACTIVE':
        return 'success';
      case 'SUPERSEDED':
        return 'none';
      default:
        return 'info';
    }
  }

  protected testSendResultKey(status: string): MessageKey {
    switch (status) {
      case 'ACCEPTED':
        return 'settings.notifications.testSend.result.ACCEPTED';
      case 'REJECTED':
        return 'settings.notifications.testSend.result.REJECTED';
      case 'RETRYABLE':
        return 'settings.notifications.testSend.result.RETRYABLE';
      default:
        return 'settings.notifications.testSend.result.UNCERTAIN';
    }
  }

  // ------------------------------------------------------------ automation

  private async loadAutomation(scope: { tenantId: string; brandId: string }): Promise<void> {
    this.automationLoading.set(true);
    try {
      const [paymentLink, shift] = await Promise.all([
        this.configApi.resolution(scope.tenantId, PAYMENT_LINK_CODE, 'BRAND', scope.brandId, null),
        this.configApi.resolution(
          scope.tenantId,
          AGGREGATOR_SHIFT_CODE,
          'BRAND',
          scope.brandId,
          null,
        ),
      ]);
      this.paymentLinkAutoSend.set(Boolean(paymentLink.value));
      this.paymentLinkVersion = paymentLink.currentVersionAtScope;
      this.aggregatorShiftNotifications.set(Boolean(shift.value));
      this.aggregatorShiftVersion = shift.currentVersionAtScope;
    } finally {
      this.automationLoading.set(false);
    }
  }

  protected async togglePaymentLinkAutoSend(enabled: boolean): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.automationSaving.set(PAYMENT_LINK_CODE);
    try {
      const saved = await this.configApi.setValue(scope.tenantId, PAYMENT_LINK_CODE, {
        scopeType: 'BRAND',
        brandId: scope.brandId,
        locationId: null,
        explicitNull: false,
        booleanValue: enabled,
        expectedVersion: this.paymentLinkVersion,
        reason: 'Toggled from the notifications settings screen',
      });
      this.paymentLinkAutoSend.set(enabled);
      this.paymentLinkVersion = saved.version;
    } finally {
      this.automationSaving.set(null);
    }
  }

  protected async toggleAggregatorShiftNotifications(enabled: boolean): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.automationSaving.set(AGGREGATOR_SHIFT_CODE);
    try {
      const saved = await this.configApi.setValue(scope.tenantId, AGGREGATOR_SHIFT_CODE, {
        scopeType: 'BRAND',
        brandId: scope.brandId,
        locationId: null,
        explicitNull: false,
        booleanValue: enabled,
        expectedVersion: this.aggregatorShiftVersion,
        reason: 'Toggled from the notifications settings screen',
      });
      this.aggregatorShiftNotifications.set(enabled);
      this.aggregatorShiftVersion = saved.version;
    } finally {
      this.automationSaving.set(null);
    }
  }

  // --------------------------------------------------------------- routing

  private async loadRouting(): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.routingLoading.set(true);
    this.routingError.set(null);
    try {
      const [bindings, eventClasses] = await Promise.all([
        this.api.routingBindings(scope),
        this.api.routingEventClasses(scope),
      ]);
      this.routingBindings.set(bindings);
      this.routingEventClasses.set(eventClasses);
    } catch (error) {
      this.routingError.set(this.describe(error));
    } finally {
      this.routingLoading.set(false);
    }
  }

  protected eventClassLabel(code: string): string {
    return (
      this.routingEventClasses().find((entry) => entry.eventClass === code)?.description ?? code
    );
  }

  protected selectedEventClasses(binding: RoutingBinding): readonly ComboboxOption[] {
    return binding.subscriptions
      .filter((sub) => sub.enabled)
      .map((sub) => ({ id: sub.eventClass, label: this.eventClassLabel(sub.eventClass) }));
  }

  protected async onSubscriptionsChanged(
    binding: RoutingBinding,
    selected: readonly ComboboxOption[],
  ): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    const selectedIds = new Set(selected.map((option) => option.id));
    const currentlyEnabled = new Set(
      binding.subscriptions.filter((sub) => sub.enabled).map((sub) => sub.eventClass),
    );
    for (const id of selectedIds) {
      if (!currentlyEnabled.has(id)) {
        await this.api.setRoutingSubscription(scope, binding.bindingId, id, true);
      }
    }
    for (const id of currentlyEnabled) {
      if (!selectedIds.has(id)) {
        await this.api.setRoutingSubscription(scope, binding.bindingId, id, false);
      }
    }
    await this.loadRouting();
  }

  protected startEditTopic(binding: RoutingBinding): void {
    this.topicEditBindingId.set(binding.bindingId);
    this.topicDraft.set(binding.topicId === null ? '' : String(binding.topicId));
    this.topicError.set(null);
  }

  protected cancelEditTopic(): void {
    this.topicEditBindingId.set(null);
  }

  protected async saveTopic(binding: RoutingBinding): Promise<void> {
    const scope = this.location.scope();
    if (!scope) {
      return;
    }
    this.topicSaving.set(true);
    this.topicError.set(null);
    try {
      const raw = this.topicDraft().trim();
      const topicId = raw.length === 0 ? null : Number.parseInt(raw, 10);
      await this.api.changeRoutingTopic(
        scope,
        binding.bindingId,
        Number.isNaN(topicId as number) ? null : topicId,
      );
      this.topicEditBindingId.set(null);
      await this.loadRouting();
    } catch (error) {
      this.topicError.set(this.describe(error));
    } finally {
      this.topicSaving.set(false);
    }
  }

  protected async confirmUnbind(): Promise<void> {
    const target = this.unbindTarget();
    const scope = this.location.scope();
    if (!target || !scope) {
      return;
    }
    this.unbindBusy.set(true);
    try {
      await this.api.unbindRouting(scope, target.bindingId);
      this.unbindTarget.set(null);
      await this.loadRouting();
    } catch (error) {
      this.routingError.set(this.describe(error));
    } finally {
      this.unbindBusy.set(false);
    }
  }

  protected cancelUnbind(): void {
    if (!this.unbindBusy()) {
      this.unbindTarget.set(null);
    }
  }

  // ----------------------------------------------------------------- load

  private async load(): Promise<void> {
    this.loading.set(true);
    await this.location.ensureLoaded();
    const scope = this.location.scope();
    if (!scope) {
      this.denied.set(this.location.denied());
      this.loading.set(false);
      this.automationLoading.set(false);
      return;
    }
    try {
      this.templates.set(await this.api.list(scope));
      void this.loadAutomation(scope);
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

  private async reload(scope: NonNullable<ReturnType<CurrentLocation['scope']>>): Promise<void> {
    this.templates.set(await this.api.list(scope));
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
