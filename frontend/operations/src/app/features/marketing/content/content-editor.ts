import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  booleanAttribute,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';

import { ApiError, ApiErrorCode } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { LocaleSet } from '../../../core/i18n/locale-set';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { DateRange, DateRangePicker } from '../../../shared/ui/date-range-picker';
import { LocalizedFieldGroup } from '../../../shared/ui/localized-field-group';
import { StatusPill, StatusTone } from '../../../shared/ui/status-pill';
import { describeApiError } from '../../orders/order-errors';
import {
  ContentApi,
  ContentCreateRequest,
  ContentDocument,
  ContentDraftBody,
  ContentItemView,
  ContentKind,
  ContentPlacement,
  ContentProblem,
  EDITORIAL_KINDS,
  PLACEMENTS_OF_KIND,
} from './content-api';
import { CardKind, ContentCardFields } from './content-card-fields';
import {
  documentForSave,
  emptyDocument,
  isLocaleComplete,
  localesMissingTitle,
} from './content-document';
import { ContentEditorialFields, EditorialKind } from './content-editorial-fields';
import { ContentGroupFields } from './content-group-fields';
import { ContentHistory } from './content-history';
import { ContentPreview } from './content-preview';
import { ContentReferences } from './content-references';
import {
  ScheduleMode,
  addDays,
  endDay,
  endInstant,
  scheduleModeOf,
  startDay,
  startInstant,
  today,
  windowIsBackwards,
} from './content-schedule';
import { ContentStatsPanel } from './content-stats';
import { ContentStoryFields } from './content-story-fields';
import {
  KIND_KEYS,
  PLACEMENT_KEYS,
  STATE_KEYS,
  VISIBILITY_KEYS,
  problemText,
} from './content-texts';

export type EditorTab = 'edit' | 'preview' | 'history' | 'stats';

/** The act the confirm dialog is asking about. */
export type EditorConfirm = 'publish' | 'withdraw' | 'archive' | 'discard';

const CONFIRM_KEYS: Readonly<
  Record<EditorConfirm, { title: MessageKey; body: MessageKey; action: MessageKey }>
> = {
  publish: {
    title: 'marketing.content.editor.confirm.publish.title',
    body: 'marketing.content.editor.confirm.publish.body',
    action: 'marketing.content.editor.publish',
  },
  withdraw: {
    title: 'marketing.content.editor.confirm.withdraw.title',
    body: 'marketing.content.editor.confirm.withdraw.body',
    action: 'marketing.content.editor.withdraw',
  },
  archive: {
    title: 'marketing.content.editor.confirm.archive.title',
    body: 'marketing.content.editor.confirm.archive.body',
    action: 'marketing.content.editor.archive',
  },
  discard: {
    title: 'marketing.content.editor.confirm.discard.title',
    body: 'marketing.content.editor.confirm.discard.body',
    action: 'marketing.content.editor.discard',
  },
};

const TAB_KEYS: Readonly<Record<EditorTab, MessageKey>> = {
  edit: 'marketing.content.editor.tab.edit',
  preview: 'marketing.content.editor.tab.preview',
  history: 'marketing.content.editor.tab.history',
  stats: 'marketing.content.editor.tab.stats',
};

/** What can be edited, as one comparable value. */
interface Snapshot {
  readonly internalName: string;
  readonly slug: string;
  readonly placement: ContentPlacement | null;
  readonly document: ContentDocument;
  readonly channelCodes: readonly string[];
  readonly startsAt: string | null;
  readonly endsAt: string | null;
  readonly promotionId: string | null;
  readonly locationId: string | null;
}

/**
 * One content item, edited (ADR 0157, Front ends): its name, where it sits, which channels carry it,
 * its schedule, the words of every language the brand writes in, its picture and link -- and then
 * saved as a draft, previewed, published, withdrawn or archived.
 *
 * **A draft is in front of nobody; only a publication reaches customers.** Publishing saves what is
 * on screen first, so what goes live is what the author is looking at, then asks the platform, which
 * runs the gates in one transaction and answers with the closed list of reasons it refused -- each is
 * shown as its own sentence beside the fields it concerns. Publish, withdraw and archive wait behind
 * a confirmation, and every change presents the item's version, so a colleague's save is a
 * "reload" and never a silent overwrite.
 *
 * The fields of each kind live in their own component; this one owns what they share: the name, the
 * placement, the channels, the schedule, the promotion a card is bound to, the language tabs with
 * their completeness dots, and the lifecycle.
 */
@Component({
  selector: 'q-content-editor',
  imports: [
    Combobox,
    ConfirmDialog,
    ContentCardFields,
    ContentEditorialFields,
    ContentGroupFields,
    ContentHistory,
    ContentPreview,
    ContentStatsPanel,
    ContentStoryFields,
    DateRangePicker,
    LocalizedFieldGroup,
    StatusPill,
    TPipe,
  ],
  templateUrl: './content-editor.html',
  styleUrl: './content-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentEditor implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);
  private readonly localeSet = inject(LocaleSet);
  protected readonly refs = inject(ContentReferences);

  /** The item being edited, or null while a new one is being written. */
  readonly item = input<ContentItemView | null>(null);
  /** The kind of a new item. */
  readonly newKind = input<ContentKind | null>(null);
  /** Where a new item starts out (a promo card may sit in two places). */
  readonly newPlacement = input<ContentPlacement | null>(null);
  /** Whether the plan lets a video be uploaded (`content.video.enabled`). */
  readonly videoEntitled = input(false, { transform: booleanAttribute });

  /** The item as the platform last answered, after any save, publication, withdrawal or archive. */
  readonly saved = output<ContentItemView>();
  readonly closed = output<void>();
  /** Someone else changed the item: the host should read it again. */
  readonly reloadRequested = output<void>();
  readonly dirtyChange = output<boolean>();

  protected readonly tab = signal<EditorTab>('edit');
  protected readonly tabs: readonly EditorTab[] = ['edit', 'preview', 'history', 'stats'];
  protected readonly confirm = signal<EditorConfirm | null>(null);

  // ----------------------------------------------------------------- what is being edited

  /** The id of the item, or null while it has not been saved yet. */
  protected readonly itemId = signal<string | null>(null);
  protected readonly version = signal(0);
  protected readonly kind = signal<ContentKind>('BANNER');
  protected readonly internalName = signal('');
  protected readonly slug = signal('');
  protected readonly placement = signal<ContentPlacement | null>(null);
  protected readonly document = signal<ContentDocument>(emptyDocument('BANNER'));
  protected readonly channelCodes = signal<readonly string[]>([]);
  protected readonly startsAt = signal<string | null>(null);
  protected readonly endsAt = signal<string | null>(null);
  protected readonly promotionId = signal<string | null>(null);
  protected readonly locationId = signal<string | null>(null);
  protected readonly scheduleMode = signal<ScheduleMode>('always');
  protected readonly promotionQuery = signal('');

  protected readonly activeLocale = signal('ru');
  private baseline = '';

  // ------------------------------------------------------------------------- the outcome

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly stale = signal(false);
  protected readonly notice = signal<MessageKey | null>(null);
  protected readonly problems = signal<readonly ContentProblem[]>([]);
  /** The item as last read, for the lifecycle and the header. */
  protected readonly current = signal<ContentItemView | null>(null);

  // -------------------------------------------------------------------------- derived

  protected readonly creating = computed(() => this.itemId() === null);
  protected readonly editorial = computed(() => EDITORIAL_KINDS.includes(this.kind()));
  protected readonly archived = computed(() => this.current()?.state === 'ARCHIVED');
  protected readonly locales = computed(() => this.localeSet.locales());
  protected readonly defaultLocale = computed(() => this.localeSet.defaultLocale());
  protected readonly placements = computed(() => PLACEMENTS_OF_KIND[this.kind()]);
  protected readonly cardKind = computed<CardKind | null>(() => {
    const kind = this.kind();
    return kind === 'BANNER' || kind === 'PROMO_CARD' || kind === 'POPUP' ? kind : null;
  });
  protected readonly editorialKind = computed<EditorialKind | null>(() => {
    const kind = this.kind();
    return kind === 'NEWS' || kind === 'RECIPE' || kind === 'GALLERY_ALBUM' || kind === 'VACANCY'
      ? kind
      : null;
  });

  protected readonly completeness = computed<Readonly<Record<string, boolean>>>(() => {
    const doc = this.document();
    return Object.fromEntries(this.locales().map((l) => [l, isLocaleComplete(doc, l)]));
  });

  /** Languages with text but no title: held back from saving, named on screen. */
  protected readonly missingTitle = computed(() => localesMissingTitle(this.document()));
  protected readonly backwards = computed(() => windowIsBackwards(this.startsAt(), this.endsAt()));

  protected readonly dirty = computed(() => this.encode(this.snapshot()) !== this.baseline);
  protected readonly canSave = computed(
    () =>
      !this.busy() &&
      !this.archived() &&
      this.internalName().trim() !== '' &&
      this.missingTitle().length === 0 &&
      !this.backwards() &&
      (!this.creating() || !this.editorial() || this.slug().trim() !== ''),
  );

  protected readonly startDay = computed(() => startDay(this.startsAt()));
  protected readonly endDay = computed(() => endDay(this.endsAt()));

  protected readonly promotionOptions = computed<readonly ComboboxOption[]>(() =>
    this.refs.promotions(this.promotionQuery()),
  );
  protected readonly promotionName = computed(() => {
    const id = this.promotionId();
    return id === null ? null : this.refs.promotionLabel(id);
  });

  protected readonly confirmTexts = computed(() => {
    const action = this.confirm();
    return action === null ? null : CONFIRM_KEYS[action];
  });

  /** The state of the item as one pill: what a customer would see, not the stored word alone. */
  protected readonly statePill = computed<{ label: MessageKey; tone: StatusTone } | null>(() => {
    const item = this.current();
    if (item === null) {
      return null;
    }
    if (item.state === 'PUBLISHED' && item.visibility !== null) {
      return {
        label: VISIBILITY_KEYS[item.visibility],
        tone: item.visibility === 'LIVE' ? 'success' : 'warning',
      };
    }
    return {
      label: STATE_KEYS[item.state],
      tone: item.state === 'WITHDRAWN' ? 'warning' : 'none',
    };
  });

  protected readonly canWithdraw = computed(() => this.current()?.state === 'PUBLISHED');
  protected readonly hasDraft = computed(() => this.current()?.draft != null);

  constructor() {
    effect(() => {
      const item = this.item();
      const kind = this.newKind();
      const placement = this.newPlacement();
      untracked(() => this.load(item, kind, placement));
    });
    effect(() => {
      const dirty = this.dirty();
      untracked(() => this.dirtyChange.emit(dirty));
    });
  }

  async ngOnInit(): Promise<void> {
    await this.localeSet.ensureLoaded();
    this.activeLocale.set(this.defaultLocale());
  }

  // ------------------------------------------------------------------------------ loading

  private load(
    item: ContentItemView | null,
    kind: ContentKind | null,
    placement: ContentPlacement | null,
  ): void {
    this.tab.set('edit');
    this.confirm.set(null);
    this.error.set(null);
    this.stale.set(false);
    this.notice.set(null);
    if (item !== null) {
      this.apply(item);
    } else {
      const k = kind ?? 'BANNER';
      this.itemId.set(null);
      this.current.set(null);
      this.version.set(0);
      this.kind.set(k);
      this.internalName.set('');
      this.slug.set('');
      this.placement.set(placement ?? PLACEMENTS_OF_KIND[k][0] ?? null);
      this.document.set(emptyDocument(k));
      this.channelCodes.set([]);
      this.startsAt.set(null);
      this.endsAt.set(null);
      this.promotionId.set(null);
      this.locationId.set(null);
      this.scheduleMode.set('always');
      this.problems.set([]);
    }
    this.activeLocale.set(this.defaultLocale());
    this.baseline = this.encode(this.snapshot());
  }

  /** Puts what the platform answered on screen, as the new clean state. */
  private apply(item: ContentItemView): void {
    const source = item.draft ?? item.liveRevision;
    this.itemId.set(item.id);
    this.current.set(item);
    this.version.set(item.version);
    this.kind.set(item.kind);
    this.internalName.set(item.internalName);
    this.slug.set(item.slug ?? '');
    this.placement.set(item.placement);
    this.document.set(source?.document ?? emptyDocument(item.kind));
    this.channelCodes.set(source?.channelCodes ?? []);
    this.startsAt.set(source?.startsAt ?? null);
    this.endsAt.set(source?.endsAt ?? null);
    this.promotionId.set(source?.promotionId ?? null);
    this.locationId.set(source?.locationId ?? null);
    this.scheduleMode.set(scheduleModeOf(source?.startsAt ?? null, source?.endsAt ?? null));
    this.problems.set(item.problems);
    this.baseline = this.encode(this.snapshot());
  }

  private snapshot(): Snapshot {
    return {
      internalName: this.internalName(),
      slug: this.slug(),
      placement: this.placement(),
      document: this.document(),
      channelCodes: this.channelCodes(),
      startsAt: this.startsAt(),
      endsAt: this.endsAt(),
      promotionId: this.promotionId(),
      locationId: this.locationId(),
    };
  }

  private encode(snapshot: Snapshot): string {
    return JSON.stringify(snapshot);
  }

  // -------------------------------------------------------------------------------- fields

  protected kindLabel(kind: ContentKind): MessageKey {
    return KIND_KEYS[kind];
  }

  protected placementLabel(placement: ContentPlacement): MessageKey {
    return PLACEMENT_KEYS[placement];
  }

  protected tabLabel(tab: EditorTab): MessageKey {
    return TAB_KEYS[tab];
  }

  protected problemSentence(problem: ContentProblem): string {
    return problemText((key, values) => this.i18n.t(key, values), problem);
  }

  protected setPlacement(value: string): void {
    this.placement.set(this.placements().find((p) => p === value) ?? null);
  }

  protected toggleChannel(code: string): void {
    this.channelCodes.update((codes) =>
      codes.includes(code) ? codes.filter((c) => c !== code) : [...codes, code],
    );
  }

  protected setScheduleMode(mode: ScheduleMode): void {
    this.scheduleMode.set(mode);
    if (mode === 'always') {
      this.startsAt.set(null);
      this.endsAt.set(null);
    } else if (mode === 'from') {
      this.startsAt.set(this.startsAt() ?? startInstant(today()));
      this.endsAt.set(null);
    } else {
      const first = this.startDay() ?? today();
      this.startsAt.set(this.startsAt() ?? startInstant(first));
      this.endsAt.set(this.endsAt() ?? endInstant(addDays(first, 6)));
    }
  }

  protected setRange(range: DateRange): void {
    this.startsAt.set(startInstant(range.start));
    this.endsAt.set(endInstant(range.end));
  }

  protected setFrom(day: string): void {
    if (day !== '') {
      this.startsAt.set(startInstant(day));
    }
  }

  protected choosePromotion(option: ComboboxOption): void {
    this.promotionQuery.set('');
    this.promotionId.set(option.id);
  }

  protected clearPromotion(): void {
    this.promotionId.set(null);
  }

  protected setLocation(value: string): void {
    this.locationId.set(value === '' ? null : value);
  }

  // -------------------------------------------------------------------------- the lifecycle

  private body(): ContentDraftBody {
    return {
      document: documentForSave(this.document()),
      channelCodes: this.channelCodes(),
      startsAt: this.startsAt(),
      endsAt: this.endsAt(),
      promotionId: this.promotionId(),
      locationId: this.locationId(),
    };
  }

  /** Saves the draft; the platform answers with the item and the gates run as warnings. */
  protected async save(): Promise<ContentItemView | null> {
    return this.run(async (scope) => {
      const id = this.itemId();
      const view =
        id === null
          ? await this.api.create(scope, this.createRequest())
          : await this.api.replaceDraft(
              scope,
              id,
              this.version(),
              this.internalName().trim(),
              this.body(),
            );
      this.apply(view);
      this.notice.set('marketing.content.editor.saved');
      this.saved.emit(view);
      return view;
    });
  }

  private createRequest(): ContentCreateRequest {
    return {
      kind: this.kind(),
      placement: this.placement(),
      internalName: this.internalName().trim(),
      slug: this.editorial() ? this.slug().trim() : null,
      ...this.body(),
    };
  }

  protected ask(action: EditorConfirm): void {
    this.confirm.set(action);
  }

  protected cancelAsk(): void {
    this.confirm.set(null);
  }

  /** Runs the act the dialog was asked about. */
  protected async confirmed(): Promise<void> {
    const action = this.confirm();
    if (action === null) {
      return;
    }
    await this.perform(action);
    this.confirm.set(null);
  }

  private async perform(action: EditorConfirm): Promise<void> {
    if (action === 'publish') {
      await this.publish();
    } else if (action === 'withdraw') {
      await this.lifecycle((scope, id, version) => this.api.withdraw(scope, id, version));
    } else if (action === 'archive') {
      await this.lifecycle((scope, id, version) => this.api.archive(scope, id, version));
    } else {
      await this.lifecycle((scope, id, version) => this.api.discardDraft(scope, id, version));
    }
  }

  private async publish(): Promise<void> {
    // What goes live is what is on screen: save it first, then publish the version that save made.
    if (this.dirty() || this.creating()) {
      const view = await this.save();
      if (view === null) {
        return;
      }
    }
    await this.lifecycle((scope, id, version) => this.api.publish(scope, id, version), true);
  }

  private async lifecycle(
    act: (
      scope: NonNullable<ReturnType<CurrentBrand['scope']>>,
      id: string,
      version: number,
    ) => Promise<ContentItemView>,
    published = false,
  ): Promise<void> {
    await this.run(async (scope) => {
      const id = this.itemId();
      if (id === null) {
        return null;
      }
      const view = await act(scope, id, this.version());
      this.apply(view);
      this.notice.set(
        published ? 'marketing.content.editor.published' : 'marketing.content.editor.done',
      );
      this.saved.emit(view);
      return view;
    });
  }

  /** Restoring an older version fills the draft; the item comes back with it. */
  protected restored(view: ContentItemView): void {
    this.apply(view);
    this.tab.set('edit');
    this.notice.set('marketing.content.editor.restored');
    this.saved.emit(view);
  }

  private async run<T>(
    act: (scope: NonNullable<ReturnType<CurrentBrand['scope']>>) => Promise<T>,
  ): Promise<T | null> {
    const scope = this.brand.scope();
    if (scope === null || this.busy()) {
      return null;
    }
    this.busy.set(true);
    this.error.set(null);
    this.notice.set(null);
    try {
      return await act(scope);
    } catch (failure) {
      this.fail(failure);
      return null;
    } finally {
      this.busy.set(false);
    }
  }

  private fail(failure: unknown): void {
    if (!(failure instanceof ApiError)) {
      this.error.set(this.i18n.t('marketing.content.editor.failed'));
      return;
    }
    if (failure.code === ApiErrorCode.STALE_VERSION) {
      this.stale.set(true);
    }
    const fieldErrors = failure.fieldErrors;
    if (fieldErrors.length > 0 && failure.status >= 400 && failure.status < 500) {
      // The platform's closed list of reasons: each shown as its own sentence beside the fields.
      this.problems.set(
        fieldErrors.map((e) => ({ field: e.field, code: e.code, message: e.message ?? '' })),
      );
      this.error.set(this.i18n.t('marketing.content.editor.refused'));
      return;
    }
    this.error.set(describeApiError(failure, (key, values) => this.i18n.t(key, values)));
  }
}
