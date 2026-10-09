import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { BrandScope } from '../../core/api/catalog-paths';
import { ApiError } from '../../core/api/problem-details';
import { I18n } from '../../core/i18n/i18n';
import type { MessageKey } from '../../core/i18n/messages.en';
import { PlatformLocales } from '../../core/i18n/platform-locales';
import { TPipe } from '../../core/i18n/t.pipe';
import { describeApiError } from '../orders/order-errors';
import { VocabularyApi, VocabularyEntry, VocabularyKind } from './vocabulary-api';

/** The refusals a product's set is answered with, and the sentence for each. */
const REFUSALS: Readonly<Record<string, MessageKey>> = {
  VOCABULARY_PRODUCT_LIMIT_REACHED: 'catalog.editor.vocabulary.refusal.limit',
  VOCABULARY_ENTRY_ARCHIVED: 'catalog.editor.vocabulary.refusal.archived',
  VOCABULARY_ENTRY_UNKNOWN: 'catalog.editor.vocabulary.refusal.unknown',
  VOCABULARY_ENTRY_DUPLICATED: 'catalog.editor.vocabulary.refusal.unknown',
};

/**
 * One product's ordered tags or ingredients (ADR 0174): picked from the tenant's registry, never typed.
 *
 * Reads the registry (so the picker offers only active entries) and the product's own set (which may include an
 * archived entry the product still carries: it stays in the list, marked, and may stay in the set), shows the
 * set in the author's order and replaces it as a whole on save. The bound is the server's (twelve tags, forty
 * ingredients); the component also refuses to add past it so the author is not told only after typing.
 */
@Component({
  selector: 'q-product-vocabulary',
  imports: [TPipe],
  templateUrl: './product-vocabulary.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ProductVocabulary implements OnInit {
  private readonly api = inject(VocabularyApi);
  private readonly i18n = inject(I18n);
  private readonly registryOfLocales = inject(PlatformLocales);

  readonly scope = input.required<BrandScope>();
  readonly productId = input.required<string>();
  readonly kind = input.required<VocabularyKind>();

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal(false);
  protected readonly registry = signal<readonly VocabularyEntry[]>([]);
  protected readonly selected = signal<readonly VocabularyEntry[]>([]);
  private readonly loadedIds = signal<readonly string[]>([]);

  protected readonly maximum = computed(() => (this.kind() === 'TAG' ? 12 : 40));
  protected readonly dirty = computed(
    () =>
      this.selected()
        .map((entry) => entry.id)
        .join(',') !== this.loadedIds().join(','),
  );
  /** Active registry entries not already in the set: what the picker offers. */
  protected readonly available = computed(() => {
    const chosen = new Set(this.selected().map((entry) => entry.id));
    return this.registry().filter((entry) => entry.status === 'ACTIVE' && !chosen.has(entry.id));
  });
  protected readonly full = computed(() => this.selected().length >= this.maximum());

  async ngOnInit(): Promise<void> {
    const scope = this.scope();
    try {
      const [registry, current] = await Promise.all([
        this.api.list(scope.tenantId, this.kind()),
        this.api.forProduct(scope, this.kind(), this.productId()),
      ]);
      this.registry.set(registry);
      this.selected.set(current);
      this.loadedIds.set(current.map((entry) => entry.id));
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.loading.set(false);
    }
  }

  /** The entry's wording in the console's own language first, else the brand's, else its code. */
  protected nameOf(entry: VocabularyEntry): string {
    const preference = [this.i18n.locale(), this.registryOfLocales.fallback()];
    for (const locale of preference) {
      const name = entry.names[locale];
      if (name !== undefined) {
        return name;
      }
    }
    return Object.values(entry.names)[0] ?? entry.code;
  }

  protected add(id: string): void {
    const entry = this.registry().find((candidate) => candidate.id === id);
    if (entry === undefined || this.full()) {
      return;
    }
    this.selected.update((current) => [...current, entry]);
    this.saved.set(false);
  }

  protected remove(index: number): void {
    this.selected.update((current) => current.filter((_, position) => position !== index));
    this.saved.set(false);
  }

  protected move(index: number, by: -1 | 1): void {
    const target = index + by;
    this.selected.update((current) => {
      if (target < 0 || target >= current.length) {
        return current;
      }
      const next = [...current];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    this.saved.set(false);
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    try {
      const saved = await this.api.setForProduct(
        this.scope(),
        this.kind(),
        this.productId(),
        this.selected().map((entry) => entry.id),
      );
      this.selected.set(saved);
      this.loadedIds.set(saved.map((entry) => entry.id));
      this.saved.set(true);
    } catch (error) {
      this.error.set(this.describe(error));
    } finally {
      this.saving.set(false);
    }
  }

  private describe(error: unknown): string {
    if (error instanceof ApiError) {
      const finding = error.problem?.['findingCode'];
      const key = typeof finding === 'string' ? REFUSALS[finding] : undefined;
      return key !== undefined
        ? this.i18n.t(key)
        : describeApiError(error, (k, values) => this.i18n.t(k, values));
    }
    return this.i18n.t('error.unknown.noReference');
  }
}
