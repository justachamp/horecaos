import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentLocation } from '../../../core/auth/current-location';
import { I18n } from '../../../core/i18n/i18n';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { CreateTemplateRequest, NotificationsApi, TemplateResponse } from './notifications-api';

type NotificationsTab = 'templates' | 'routing';

const NOTIFICATION_CLASSES: readonly string[] = [
  'TRANSACTIONAL_REQUIRED',
  'TRANSACTIONAL_OPTIONAL',
  'MARKETING',
  'SECURITY',
  'OPERATIONS_ALERT',
];

const CHANNELS: readonly string[] = ['SMS', 'TELEGRAM', 'PUSH', 'EMAIL'];

/**
 * 10.9 Notifications — `docs/operations-spec/settings.md` §10.9.
 *
 * **Tab 1 (Шаблоны) is real** — `NotificationTemplateController` covers
 * list/register/draft-version/activate, already on the operations surface.
 * Simplified relative to the spec's `TemplateEditor`: one form creates the
 * template and its first version together (`ru`/`uz-Latn`/`en` body, no
 * subject, no `VariableChip` picker, no live phone-frame preview, no
 * moderation-state column — that state is genuinely not built anywhere in
 * the platform yet, confirmed against the schema, not only against the
 * spec's own citation). Publishing a template still exercises the server's
 * real invariant: all three locales are required in one call, or it refuses.
 *
 * **Tab 2 (Маршрутизация) is not built.** No controller reads or writes
 * `notifications.recipient_endpoints` for admin routing, and ADR 0058's own
 * `integration.telegram_bindings` model has since superseded the spec's
 * simpler per-event-class field with no admin CRUD of its own either — a
 * future build should target ADR 0058's shape, not this spec's.
 */
@Component({
  selector: 'q-notifications-page',
  imports: [TPipe],
  templateUrl: './notifications-page.html',
  styleUrl: './notifications-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NotificationsPage {
  private readonly api = inject(NotificationsApi);
  private readonly location = inject(CurrentLocation);
  protected readonly i18n = inject(I18n);

  protected readonly activeTab = signal<NotificationsTab>('templates');
  protected readonly notificationClasses = NOTIFICATION_CLASSES;
  protected readonly channels = CHANNELS;

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly templates = signal<readonly TemplateResponse[]>([]);

  protected readonly showCreateForm = signal(false);
  protected readonly createSubmitting = signal(false);
  protected readonly createError = signal<string | null>(null);
  protected readonly newTemplateKey = signal('');
  protected readonly newNotificationClass = signal(NOTIFICATION_CLASSES[0]);
  protected readonly newChannel = signal(CHANNELS[0]);
  protected readonly newBodyRu = signal('');
  protected readonly newBodyUz = signal('');
  protected readonly newBodyEn = signal('');
  protected readonly activateImmediately = signal(true);

  constructor() {
    void this.load();
  }

  protected selectTab(tab: NotificationsTab): void {
    this.activeTab.set(tab);
  }

  protected canCreate(): boolean {
    return (
      !this.createSubmitting() &&
      this.newTemplateKey().trim().length > 0 &&
      this.newBodyRu().trim().length > 0 &&
      this.newBodyUz().trim().length > 0 &&
      this.newBodyEn().trim().length > 0
    );
  }

  protected async submitCreate(): Promise<void> {
    const scope = this.location.scope();
    if (!scope || !this.canCreate()) {
      return;
    }
    this.createSubmitting.set(true);
    this.createError.set(null);
    const request: CreateTemplateRequest = {
      templateKey: this.newTemplateKey().trim(),
      notificationClass: this.newNotificationClass(),
      channel: this.newChannel(),
    };
    try {
      const templateId = await this.api.create(scope, request);
      const versionNumber = await this.api.addVersion(scope, templateId, {
        wordings: {
          ru: { body: this.newBodyRu().trim() },
          'uz-Latn': { body: this.newBodyUz().trim() },
          en: { body: this.newBodyEn().trim() },
        },
        variablesSchema: {},
      });
      if (this.activateImmediately()) {
        await this.api.activate(scope, templateId, versionNumber);
      }
      this.showCreateForm.set(false);
      this.newTemplateKey.set('');
      this.newBodyRu.set('');
      this.newBodyUz.set('');
      this.newBodyEn.set('');
      await this.reload(scope);
    } catch (error) {
      this.createError.set(this.describe(error));
    } finally {
      this.createSubmitting.set(false);
    }
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
      this.templates.set(await this.api.list(scope));
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
