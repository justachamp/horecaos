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
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialog } from '../../../shared/ui/confirm-dialog';
import { DeniedState } from '../../../shared/ui/denied-state';
import { RuleList, RuleListItem, RuleReorder } from '../../../shared/ui/rule-list';
import { StatusPill, StatusTone } from '../../../shared/ui/status-pill';
import { describeApiError } from '../../orders/order-errors';
import {
  ContentApi,
  ContentItemSummary,
  ContentItemView,
  ContentKind,
  ContentPlacement,
  PLACEMENT_LIMITS,
} from './content-api';
import { ContentEditor } from './content-editor';
import { windowLabel } from './content-schedule';
import { KIND_KEYS, PLACEMENT_KEYS, STATE_KEYS, VISIBILITY_KEYS } from './content-texts';

/** Items listed at once in an ordered placement: more than the platform allows live, by a wide margin. */
const PLACEMENT_PAGE = 200;
const EDITORIAL_PAGE = 30;

/**
 * A list of content items and the editor beside it (ADR 0157, Front ends).
 *
 * Two shapes of list, because two things are true of content. A **placement** (the home carousel,
 * the home groups, the story rail, the promo grid, the entry pop-up) has an order that matters, so it
 * is the flat sortable list `q-rule-list` with its enable switch off -- publication and a schedule
 * decide what is live, so a switch would promise a second, competing way to hide an item -- and a
 * reorder takes effect at once, audited, and is not a revision. An **editorial kind** has no
 * placement: it is a list of entries, newest first, paged.
 *
 * Each row says, in one line, what a customer would see now (live, scheduled, ended, draft,
 * withdrawn), the window, whether there are unpublished changes and how many things need fixing.
 * Choosing a row opens the editor; unsaved edits are never discarded without asking.
 */
@Component({
  selector: 'q-content-workbench',
  imports: [ConfirmDialog, ContentEditor, DeniedState, RuleList, StatusPill, TPipe],
  templateUrl: './content-workbench.html',
  styleUrl: './content-workbench.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentWorkbench implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  /** The kinds this list shows and may create. */
  readonly kinds = input.required<readonly ContentKind[]>();
  /** The placement it orders; null for an editorial kind. */
  readonly placement = input<ContentPlacement | null>(null);
  /** An item to open as soon as the list is read (from the health panel). */
  readonly focusItemId = input<string | null>(null);
  /** Whether the plan lets a video be uploaded. */
  readonly videoEntitled = input(false, { transform: booleanAttribute });
  /** A list that only orders and opens: no `New` button (the storefront page's carousel). */
  readonly canCreate = input(true, { transform: booleanAttribute });

  /** An item was saved, published, withdrawn, archived or reordered: the health read may have changed. */
  readonly changed = output<void>();

  protected readonly loading = signal(true);
  protected readonly denied = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly items = signal<readonly ContentItemSummary[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  protected readonly orderToken = signal<string | null>(null);
  protected readonly showArchived = signal(false);

  protected readonly selected = signal<ContentItemView | null>(null);
  protected readonly creatingKind = signal<ContentKind | null>(null);
  protected readonly editorDirty = signal(false);
  /** Where to go once the author has agreed to discard unsaved edits. */
  private readonly pendingMove = signal<(() => void) | null>(null);
  protected readonly discardOpen = computed(() => this.pendingMove() !== null);

  protected readonly ordered = computed(() => this.placement() !== null);
  protected readonly limit = computed(() => {
    const placement = this.placement();
    return placement === null ? null : PLACEMENT_LIMITS[placement];
  });
  protected readonly live = computed(
    () => this.items().filter((item) => item.visibility === 'LIVE').length,
  );
  protected readonly editorOpen = computed(
    () => this.selected() !== null || this.creatingKind() !== null,
  );

  protected readonly rows = computed<readonly RuleListItem[]>(() => {
    this.i18n.locale();
    return this.items().map((item) => ({
      id: item.id,
      label: item.displayTitle || item.internalName,
      description: this.describe(item),
      enabled: true,
    }));
  });

  protected readonly selectedId = computed(() => this.selected()?.id ?? null);

  constructor() {
    effect(() => {
      const id = this.focusItemId();
      if (id !== null) {
        untracked(() => void this.open(id));
      }
    });
  }

  async ngOnInit(): Promise<void> {
    await this.brand.ensureLoaded();
    await this.reload();
  }

  // ----------------------------------------------------------------------------- reading

  protected async reload(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      this.loading.set(false);
      this.denied.set(true);
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const placement = this.placement();
      const kind = this.kinds().length === 1 ? this.kinds()[0] : undefined;
      const page = await this.api.list(scope, {
        placement: placement ?? undefined,
        kind: placement === null ? kind : undefined,
        state: this.showArchived() ? 'ARCHIVED' : undefined,
        limit: placement === null ? EDITORIAL_PAGE : PLACEMENT_PAGE,
      });
      this.items.set(page.items.filter((item) => this.kinds().includes(item.kind)));
      this.nextCursor.set(page.nextCursor);
      this.orderToken.set(page.orderToken);
    } catch (failure) {
      this.fail(failure, 'list');
    } finally {
      this.loading.set(false);
    }
  }

  protected async more(): Promise<void> {
    const scope = this.brand.scope();
    const cursor = this.nextCursor();
    if (scope === null || cursor === null) {
      return;
    }
    try {
      const page = await this.api.list(scope, {
        kind: this.kinds().length === 1 ? this.kinds()[0] : undefined,
        state: this.showArchived() ? 'ARCHIVED' : undefined,
        cursor,
        limit: EDITORIAL_PAGE,
      });
      this.items.update((current) => [...current, ...page.items]);
      this.nextCursor.set(page.nextCursor);
    } catch (failure) {
      this.fail(failure, 'list');
    }
  }

  protected async toggleArchived(): Promise<void> {
    this.guard(() => {
      this.showArchived.update((value) => !value);
      this.closeEditor();
      void this.reload();
    });
  }

  // ----------------------------------------------------------------------------- opening

  protected choose(id: string): void {
    if (id === this.selectedId()) {
      return;
    }
    this.guard(() => void this.open(id));
  }

  protected async open(id: string): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      return;
    }
    try {
      const item = await this.api.get(scope, id);
      this.creatingKind.set(null);
      this.selected.set(item);
      this.error.set(null);
    } catch (failure) {
      this.fail(failure, 'open');
    }
  }

  protected create(kind: ContentKind): void {
    this.guard(() => {
      this.selected.set(null);
      this.creatingKind.set(kind);
    });
  }

  /** The editor's own close button: asks first if there is anything unsaved. */
  protected requestClose(): void {
    this.guard(() => this.closeEditor());
  }

  protected closeEditor(): void {
    this.selected.set(null);
    this.creatingKind.set(null);
    this.editorDirty.set(false);
  }

  /** Runs `move` now, or after the author has agreed to lose what they have not saved. */
  private guard(move: () => void): void {
    if (this.editorOpen() && this.editorDirty()) {
      this.pendingMove.set(move);
    } else {
      move();
    }
  }

  protected confirmDiscard(): void {
    const move = this.pendingMove();
    this.pendingMove.set(null);
    this.editorDirty.set(false);
    move?.();
  }

  protected keepEditing(): void {
    this.pendingMove.set(null);
  }

  // ---------------------------------------------------------------------- after the editor

  /** The editor saved, published, withdrew or archived: show the platform's answer and refresh the list. */
  protected async onSaved(item: ContentItemView): Promise<void> {
    if (this.creatingKind() !== null) {
      // The item now exists: keep editing it, not a new one.
      this.creatingKind.set(null);
      this.selected.set(item);
    }
    await this.reload();
    this.changed.emit();
  }

  protected async onReload(): Promise<void> {
    const id = this.selectedId();
    if (id !== null) {
      this.editorDirty.set(false);
      await this.open(id);
    }
  }

  // ------------------------------------------------------------------------------ ordering

  protected async reorder(ids: RuleReorder): Promise<void> {
    const scope = this.brand.scope();
    const placement = this.placement();
    const token = this.orderToken();
    if (scope === null || placement === null || token === null) {
      return;
    }
    const before = this.items();
    const byId = new Map(before.map((item) => [item.id, item]));
    // Optimistic: the list moves at once; a refusal puts it back by reading the truth.
    this.items.set(
      ids.map((id) => byId.get(id)).filter((item): item is ContentItemSummary => !!item),
    );
    try {
      const answered = await this.api.order(scope, placement, ids, token);
      this.orderToken.set(answered.orderToken);
      this.error.set(null);
      this.changed.emit();
    } catch (failure) {
      this.items.set(before);
      // Read the truth first (a read clears the message), then say what happened.
      await this.reload();
      this.fail(failure, 'order');
    }
  }

  // --------------------------------------------------------------------------------- text

  protected kindLabel(kind: ContentKind): MessageKey {
    return KIND_KEYS[kind];
  }

  protected placementLabel(): MessageKey | null {
    const placement = this.placement();
    return placement === null ? null : PLACEMENT_KEYS[placement];
  }

  protected pill(item: ContentItemSummary): { label: MessageKey; tone: StatusTone } {
    if (item.state === 'PUBLISHED' && item.visibility !== null) {
      return {
        label: VISIBILITY_KEYS[item.visibility],
        tone: item.visibility === 'LIVE' ? 'success' : 'warning',
      };
    }
    return { label: STATE_KEYS[item.state], tone: item.state === 'WITHDRAWN' ? 'warning' : 'none' };
  }

  /** One line under the title: what it is, when it runs, what is open. */
  private describe(item: ContentItemSummary): string {
    const t = (key: MessageKey, values?: Readonly<Record<string, string | number>>): string =>
      this.i18n.t(key, values);
    const pill = this.pill(item);
    const parts: string[] = [t(KIND_KEYS[item.kind]), t(pill.label)];
    const window = windowLabel(item.startsAt, item.endsAt, (day) =>
      t('marketing.content.list.from', { day }),
    );
    if (window !== null) {
      parts.push(window);
    }
    if (item.hasUnpublishedChanges) {
      parts.push(t('marketing.content.unpublishedChanges'));
    }
    if (item.problems.length > 0) {
      parts.push(t('marketing.content.problemsCount', { count: item.problems.length }));
    }
    return parts.join(' · ');
  }

  private fail(failure: unknown, step: 'list' | 'open' | 'order'): void {
    if (failure instanceof ApiError) {
      if (failure.code === ApiErrorCode.INSUFFICIENT_CAPABILITY && step === 'list') {
        this.denied.set(true);
        return;
      }
      this.error.set(
        failure.code === ApiErrorCode.STALE_VERSION && step === 'order'
          ? this.i18n.t('marketing.content.list.orderMoved')
          : describeApiError(failure, (key, values) => this.i18n.t(key, values)),
      );
      return;
    }
    this.error.set(this.i18n.t('marketing.content.editor.failed'));
  }
}
