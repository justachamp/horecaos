import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { MessageKey } from '../../core/i18n/messages.en';
import { TPipe } from '../../core/i18n/t.pipe';
import { MAX_SAVED_VIEWS, SavedView } from '../../core/preferences/preference-documents';
import {
  MAX_SAVED_VIEW_LABEL,
  SavedViewLabelProblem,
  normalisedSavedViewLabel,
  savedViewLabelProblem,
} from '../../core/preferences/preference-edits';
import { ORDER_TAB_DEFINITIONS } from './order-tabs';

const PROBLEM_KEYS: Readonly<Record<SavedViewLabelProblem, MessageKey>> = {
  empty: 'orders.queue.views.problem.empty',
  tooLong: 'orders.queue.views.problem.tooLong',
  control: 'orders.queue.views.problem.control',
  number: 'orders.queue.views.problem.number',
};

/**
 * «Мои виды»: the person's saved views of the order board (ADR 0160, gap map row `0.2d`).
 *
 * A view is a tab and a closed list of filters under a name the person typed. This component only lists
 * them, takes a name for a new one and says what was asked; reading the board's filters, building the
 * view, keeping it in the person's account and opening it belong to the queue. It shows the early answer
 * for a name the platform would refuse (empty, over forty characters, control characters, a number long
 * enough to be a phone) so the person is told before the save and not after it; the platform stays the
 * authority.
 *
 * Views are one person's: nothing here shares, copies or exports one.
 */
@Component({
  selector: 'q-order-saved-views',
  imports: [TPipe],
  templateUrl: './order-saved-views.html',
  styleUrl: './order-saved-views.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:pointerdown)': 'onDocumentPointerDown($event)',
    '(keydown.escape)': 'close()',
  },
})
export class OrderSavedViews {
  readonly views = input.required<readonly SavedView[]>();
  /** True when views are kept in the person's account; false when they live for this session only. */
  readonly persistent = input(true, { transform: booleanAttribute });
  /** The board has a filter on that a view cannot hold (a search, a courier, an aggregator, an exact period). */
  readonly leftOut = input(false, { transform: booleanAttribute });

  readonly applied = output<SavedView>();
  readonly saved = output<string>();
  readonly removed = output<string>();

  protected readonly open = signal(false);
  protected readonly naming = signal(false);
  protected readonly draft = signal('');
  /** Set only after the first try, so an empty box is not scolded before anyone has typed. */
  private readonly tried = signal(false);

  protected readonly maxLabel = MAX_SAVED_VIEW_LABEL;
  protected readonly atLimit = computed(() => this.views().length >= MAX_SAVED_VIEWS);
  protected readonly problemKey = computed<MessageKey | null>(() => {
    const draft = this.draft();
    if (!this.tried() && draft.length === 0) {
      return null;
    }
    const problem = savedViewLabelProblem(draft);
    return problem === null ? null : PROBLEM_KEYS[problem];
  });

  private readonly host = inject(ElementRef<HTMLElement>);

  protected tabLabelKey(view: SavedView): MessageKey {
    return ORDER_TAB_DEFINITIONS[view.tab].labelKey;
  }

  protected toggleMenu(): void {
    this.open.update((value) => !value);
    if (!this.open()) {
      this.stopNaming();
    }
  }

  protected close(): void {
    this.open.set(false);
    this.stopNaming();
  }

  protected onDocumentPointerDown(event: Event): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.close();
    }
  }

  protected startNaming(): void {
    this.draft.set('');
    this.tried.set(false);
    this.naming.set(true);
  }

  protected onDraft(value: string): void {
    this.draft.set(value);
  }

  protected apply(view: SavedView): void {
    this.applied.emit(view);
    this.close();
  }

  protected save(): void {
    this.tried.set(true);
    if (savedViewLabelProblem(this.draft()) !== null) {
      return;
    }
    this.saved.emit(normalisedSavedViewLabel(this.draft()));
    this.stopNaming();
  }

  protected remove(view: SavedView, event: Event): void {
    event.stopPropagation();
    this.removed.emit(view.id);
  }

  private stopNaming(): void {
    this.naming.set(false);
    this.draft.set('');
    this.tried.set(false);
  }
}
