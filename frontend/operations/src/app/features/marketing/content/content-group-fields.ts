import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';

import { TPipe } from '../../../core/i18n/t.pipe';
import { Combobox, ComboboxOption } from '../../../shared/ui/combobox';
import { NumberStepper } from '../../../shared/ui/number-stepper';
import { RuleList, RuleListItem, RuleReorder } from '../../../shared/ui/rule-list';
import { ContentDocument } from './content-api';
import {
  GroupMode,
  GroupOrder,
  GroupValue,
  groupOf,
  textOf,
  withGroup,
  withText,
} from './content-document';
import { ContentReferences } from './content-references';

/** The most dishes a manual group may name (the grammar's own limit). */
export const MAX_GROUP_MEMBERS = 40;

/**
 * A home group (ADR 0157, Decision 5): a titled rail of dishes the brand picks, either by hand --
 * an ordered list of dishes -- or by category, with an order and a limit.
 *
 * Members are dish and category ids chosen from the brand's own lists, never typed. The storefront
 * joins them against the menu it already holds and **skips an id the menu lacks**, so a dish that was
 * since removed disappears from the group without an error and without the author having to edit it;
 * availability (a stopped dish) stays the menu's business. The order of a manual group is the order
 * it is shown in, set on `q-rule-list` (the flat sortable list, with its enable switch off: what is
 * live is decided by publication, not by a switch).
 */
@Component({
  selector: 'q-content-group-fields',
  imports: [Combobox, NumberStepper, RuleList, TPipe],
  templateUrl: './content-group-fields.html',
  styleUrl: './content-group-fields.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentGroupFields {
  protected readonly refs = inject(ContentReferences);

  readonly document = input.required<ContentDocument>();
  readonly locale = input.required<string>();
  readonly disabled = input(false, { transform: booleanAttribute });

  readonly documentChange = output<ContentDocument>();

  protected readonly memberQuery = signal('');
  protected readonly categoryQuery = signal('');
  protected readonly maxMembers = MAX_GROUP_MEMBERS;

  protected readonly group = computed(() => groupOf(this.document()));
  protected readonly title = computed(() => textOf(this.document(), this.locale(), 'title'));

  protected readonly memberOptions = computed<readonly ComboboxOption[]>(() => {
    const chosen = new Set(this.group().members);
    return this.refs.products(this.memberQuery()).filter((option) => !chosen.has(option.id));
  });

  protected readonly categoryOptions = computed<readonly ComboboxOption[]>(() =>
    this.refs.categories(this.categoryQuery()),
  );

  /** The members as chips, in the order they are shown. */
  protected readonly selectedMembers = computed<readonly ComboboxOption[]>(() =>
    this.group().members.map((id) => ({ id, label: this.refs.productLabel(id) })),
  );

  protected readonly orderItems = computed<readonly RuleListItem[]>(() =>
    this.group().members.map((id) => ({ id, label: this.refs.productLabel(id), enabled: true })),
  );

  protected readonly categoryName = computed(() => {
    const id = this.group().categoryId;
    return id === null ? null : this.refs.categoryLabel(id);
  });

  protected setTitle(value: string): void {
    this.documentChange.emit(withText(this.document(), this.locale(), 'title', value));
  }

  protected setMode(value: string): void {
    const mode: GroupMode = value === 'BY_CATEGORY' ? 'BY_CATEGORY' : 'MANUAL';
    this.change({ mode });
  }

  /** The chips as the combobox reports them (an add, or a removal); a group holds at most 40 dishes. */
  protected setMembers(chips: readonly ComboboxOption[]): void {
    this.memberQuery.set('');
    this.change({ members: chips.slice(0, MAX_GROUP_MEMBERS).map((chip) => chip.id) });
  }

  protected reorder(ids: RuleReorder): void {
    this.change({ members: ids });
  }

  protected chooseCategory(option: ComboboxOption): void {
    this.categoryQuery.set('');
    this.change({ categoryId: option.id });
  }

  protected clearCategory(): void {
    this.change({ categoryId: null });
  }

  protected setOrder(value: string): void {
    const order: GroupOrder = value === 'NEWEST' ? 'NEWEST' : 'MENU_ORDER';
    this.change({ order });
  }

  protected setLimit(limit: number): void {
    this.change({ limit });
  }

  private change(patch: Partial<GroupValue>): void {
    this.documentChange.emit(withGroup(this.document(), { ...this.group(), ...patch }));
  }
}
