import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentTenant } from '../../../core/auth/current-tenant';
import { formatDateTime } from '../../../core/format/datetime';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { SettingsSaved } from '../settings-saved';
import { SettingsScope } from '../settings-scope';
import {
  AssistantKnowledgeApi,
  KNOWLEDGE_ANSWER_MAX,
  KNOWLEDGE_QUESTION_MAX,
  KNOWLEDGE_QUESTION_MIN,
  KNOWLEDGE_REASON_MAX,
  KnowledgeEntry,
  KnowledgeLocale,
  KnowledgeVersion,
} from './assistant-knowledge-api';

/** See `order-queue.ts`'s identical constant for why this is a fixed zone, not the browser's. */
const PLACEHOLDER_TIME_ZONE = 'Asia/Tashkent';

const LOCALES: readonly KnowledgeLocale[] = ['ru', 'uz', 'en'];

/** What one entry's history disclosure is showing. */
type HistoryView =
  | { readonly status: 'loading' }
  | { readonly status: 'error' }
  | { readonly status: 'ready'; readonly versions: readonly KnowledgeVersion[] };

/**
 * The notes the chat assistant answers from (ADR 0069): short answers the operations team writes,
 * in one language, for the whole company, a brand, or one branch. The assistant retrieves a note
 * when a customer's question is about what it asks, and quotes it; it never states a price or an
 * opening time from a note, because those come from the platform.
 *
 * **A note is never edited.** Changing one publishes the next version; retiring one publishes a
 * version that ends it. What an earlier version said stays readable under History, because a
 * customer was told those exact words at a specific time. Both carry the version the person read as
 * `If-Match`, so two people changing one note at once is refused for the second.
 *
 * **Follows the scope bar.** At company level this lists and writes the company-wide notes; at a
 * brand or a branch it lists and writes the brand's, whose list includes its branches'. The path an
 * entry is written through decides its scope and the capability checked, so a note cannot be
 * authored somewhere the person has no right to.
 *
 * Reads need `assistant.read` and writes `assistant.knowledge.manage`: a person who can read and
 * not write sees the notes and is told, when they try, that their role cannot change them.
 */
@Component({
  selector: 'q-assistant-knowledge-page',
  imports: [TPipe, RouterLink, NgTemplateOutlet],
  templateUrl: './assistant-knowledge-page.html',
  styleUrl: './assistant-knowledge-page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssistantKnowledgePage {
  private readonly api = inject(AssistantKnowledgeApi);
  private readonly tenant = inject(CurrentTenant);
  protected readonly scope = inject(SettingsScope);
  private readonly saved = inject(SettingsSaved);
  protected readonly i18n = inject(I18n);

  protected readonly locales = LOCALES;
  protected readonly questionMax = KNOWLEDGE_QUESTION_MAX;
  protected readonly answerMax = KNOWLEDGE_ANSWER_MAX;

  /** The brand to address, or `null` for the company-wide route. */
  protected readonly brandId = computed<string | null>(() =>
    this.scope.level() === 'TENANT' ? null : this.scope.brandId(),
  );

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly entries = signal<readonly KnowledgeEntry[]>([]);

  /** Which form is open: a new note, a new version of one, or the end of one. Never two at once. */
  protected readonly creating = signal(false);
  protected readonly revisingId = signal<string | null>(null);
  protected readonly retiringId = signal<string | null>(null);

  protected readonly draftLocale = signal<KnowledgeLocale>('ru');
  protected readonly draftLocationId = signal('');
  protected readonly draftQuestion = signal('');
  protected readonly draftAnswer = signal('');
  protected readonly draftReason = signal('');
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly histories = signal<ReadonlyMap<string, HistoryView>>(new Map());

  protected readonly showsBranchPicker = computed(() => this.brandId() !== null);
  protected readonly branches = computed(() => this.scope.locations());

  protected readonly questionLength = computed(() => this.draftQuestion().trim().length);
  protected readonly answerLength = computed(() => this.draftAnswer().trim().length);

  /** Whether the open form is complete and within what the platform accepts. */
  protected readonly canSave = computed(() => {
    if (this.saving() || this.draftReason().trim().length === 0) {
      return false;
    }
    if (this.retiringId() !== null) {
      return this.draftReason().length <= KNOWLEDGE_REASON_MAX;
    }
    const question = this.questionLength();
    const answer = this.answerLength();
    return (
      question >= KNOWLEDGE_QUESTION_MIN &&
      question <= KNOWLEDGE_QUESTION_MAX &&
      answer > 0 &&
      answer <= KNOWLEDGE_ANSWER_MAX &&
      this.draftReason().length <= KNOWLEDGE_REASON_MAX
    );
  });

  constructor() {
    effect(() => {
      const tenantId = this.tenant.tenantId();
      const brandId = this.brandId();
      this.scope.level();
      if (tenantId && (brandId !== null || this.scope.level() === 'TENANT')) {
        void this.load(tenantId, brandId);
      } else if (this.scope.denied()) {
        this.denied.set(true);
        this.loading.set(false);
      }
    });
  }

  // --------------------------------------------------------------- labels

  protected localeLabel(locale: string): string {
    switch (locale) {
      case 'ru':
        return this.i18n.t('settings.assistant.notes.locale.ru');
      case 'uz':
        return this.i18n.t('settings.assistant.notes.locale.uz');
      case 'en':
        return this.i18n.t('settings.assistant.notes.locale.en');
      default:
        return locale;
    }
  }

  protected scopeLabel(entry: KnowledgeEntry): string {
    switch (entry.scope) {
      case 'TENANT':
        return this.i18n.t('settings.assistant.notes.scope.TENANT');
      case 'BRAND':
        return this.i18n.t('settings.assistant.notes.scope.BRAND');
      case 'LOCATION': {
        const name = this.branches().find((branch) => branch.id === entry.locationId)?.displayName;
        return name
          ? this.i18n.t('settings.assistant.notes.scope.LOCATION', { name })
          : this.i18n.t('settings.assistant.notes.scope.LOCATION.unnamed');
      }
      default:
        return entry.scope;
    }
  }

  protected statusLabel(status: string): string {
    switch (status) {
      case 'PUBLISHED':
        return this.i18n.t('settings.assistant.notes.status.PUBLISHED');
      case 'RETIRED':
        return this.i18n.t('settings.assistant.notes.status.RETIRED');
      default:
        return status;
    }
  }

  protected formatWhen(instant: string): string {
    return formatDateTime(new Date(instant), PLACEHOLDER_TIME_ZONE);
  }

  protected historyOf(entry: KnowledgeEntry): HistoryView | null {
    return this.histories().get(entry.id) ?? null;
  }

  protected levelHintKey(): MessageKey {
    return this.scope.level() === 'TENANT'
      ? 'settings.assistant.notes.hint.tenant'
      : 'settings.assistant.notes.hint.brand';
  }

  // ------------------------------------------------------------ opening forms

  protected startCreating(): void {
    this.closeForms();
    this.draftLocale.set('ru');
    this.draftLocationId.set(this.scope.locationId() ?? '');
    this.draftQuestion.set('');
    this.draftAnswer.set('');
    this.beginDraft();
    this.creating.set(true);
  }

  protected startRevising(entry: KnowledgeEntry): void {
    this.closeForms();
    this.draftQuestion.set(entry.questionForm);
    this.draftAnswer.set(entry.answerBody);
    this.beginDraft();
    this.revisingId.set(entry.id);
  }

  protected startRetiring(entry: KnowledgeEntry): void {
    this.closeForms();
    this.beginDraft();
    this.retiringId.set(entry.id);
  }

  protected cancel(): void {
    this.closeForms();
  }

  private beginDraft(): void {
    this.draftReason.set('');
    this.saveError.set(null);
  }

  private closeForms(): void {
    this.creating.set(false);
    this.revisingId.set(null);
    this.retiringId.set(null);
    this.saveError.set(null);
  }

  // -------------------------------------------------------------- history

  protected async toggleHistory(entry: KnowledgeEntry): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId) {
      return;
    }
    if (this.histories().has(entry.id)) {
      const next = new Map(this.histories());
      next.delete(entry.id);
      this.histories.set(next);
      return;
    }
    this.setHistory(entry.id, { status: 'loading' });
    try {
      const versions = await this.api.versions(tenantId, this.brandId(), entry.id);
      this.setHistory(entry.id, { status: 'ready', versions });
    } catch {
      this.setHistory(entry.id, { status: 'error' });
    }
  }

  private setHistory(entryId: string, view: HistoryView): void {
    // A history closed while its read was in flight stays closed.
    if (view.status !== 'loading' && !this.histories().has(entryId)) {
      return;
    }
    this.histories.set(new Map(this.histories()).set(entryId, view));
  }

  // ---------------------------------------------------------------- writes

  protected async save(): Promise<void> {
    const tenantId = this.tenant.tenantId();
    if (!tenantId || !this.canSave()) {
      return;
    }
    const brandId = this.brandId();
    const question = this.draftQuestion().trim();
    const answer = this.draftAnswer().trim();
    const reason = this.draftReason().trim();
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const retiring = this.retiringId();
      const revising = this.revisingId();
      if (retiring !== null) {
        const entry = this.entryById(retiring);
        await this.api.retire(tenantId, brandId, retiring, entry?.version ?? 0, reason);
      } else if (revising !== null) {
        const entry = this.entryById(revising);
        await this.api.publish(tenantId, brandId, revising, entry?.version ?? 0, {
          questionForm: question,
          answerBody: answer,
          reason,
        });
      } else {
        await this.api.create(tenantId, brandId, {
          // The branch is only ever named on the brand route; the company route has no such field.
          ...(brandId !== null ? { locationId: this.draftLocationId() || null } : {}),
          locale: this.draftLocale(),
          questionForm: question,
          answerBody: answer,
          reason,
        });
      }
      this.closeForms();
      this.histories.set(new Map());
      await this.load(tenantId, brandId);
      this.saved.announce(
        'published',
        this.i18n.t('settings.assistant.notes.title'),
        this.scope.targetFor(
          this.scope.level() === 'LOCATION' ? 'BRAND' : this.scope.level(),
          this.scope.brandId(),
          null,
        ),
      );
    } catch (error) {
      this.saveError.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  private entryById(entryId: string): KnowledgeEntry | undefined {
    return this.entries().find((entry) => entry.id === entryId);
  }

  // --------------------------------------------------------------- loading

  private async load(tenantId: string, brandId: string | null): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    this.closeForms();
    this.histories.set(new Map());
    try {
      this.entries.set(await this.api.list(tenantId, brandId));
      this.denied.set(false);
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

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      return describeApiError(error, (key, values) => this.i18n.t(key, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
