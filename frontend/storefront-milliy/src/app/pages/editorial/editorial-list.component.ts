import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';

import { EditorialService } from '../../services/editorial.service';
import { LangService } from '../../services/lang.service';
import { ReducedMotionService } from '../../services/reduced-motion.service';
import { isEditorialKind, type EditorialSummary } from '../../types/editorial.types';
import { mediaAspectRatio, mediaPlan } from '../../utils/content-media';
import { formatDate, languageTag } from '../../utils/content-text';
import { ContentMediaComponent } from '../../shared/content-media/content-media.component';
import { EditorialUnavailableComponent } from '../../shared/editorial-unavailable/editorial-unavailable.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';

type LoadState = 'loading' | 'ready' | 'unavailable';

/**
 * The listing of one editorial kind -- news, gallery albums, recipes or vacancies -- newest first
 * (ADR 0157, Release 5). One component for the four routes; the kind comes from the route's data.
 *
 * <h2>"Not available" is not an error</h2>
 *
 * A 404, a kind the brand has switched off, any failure to read the first page, and a route whose
 * kind is not one of the four are all the same quiet page (`EditorialUnavailableComponent`). A failure
 * to read a *later* page leaves what is shown where it is and lets the visitor ask again.
 *
 * Nothing here is an application form: a vacancy is a listing with one outbound contact, which its
 * own page shows.
 */
@Component({
  selector: 'app-editorial-list',
  standalone: true,
  imports: [ContentMediaComponent, EditorialUnavailableComponent, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './editorial-list.component.html',
  styleUrl: './editorial-list.component.scss',
})
export class EditorialListComponent {
  private readonly editorial = inject(EditorialService);
  private readonly lang = inject(LangService);
  private readonly reducedMotion = inject(ReducedMotionService);

  /** From the route's `data`: `news`, `gallery`, `recipes` or `careers`. */
  readonly kind = input<string | undefined>(undefined);

  protected readonly valid = computed(() => {
    const kind = this.kind();
    return isEditorialKind(kind) ? kind : null;
  });
  protected readonly state = signal<LoadState>('loading');
  protected readonly loadingMore = signal(false);
  private readonly entries = signal<readonly EditorialSummary[]>([]);
  protected readonly nextCursor = signal<string | null>(null);
  private readonly brokenCovers = signal<ReadonlySet<string>>(new Set());
  /** Bumped by every first-page read, so an answer for a kind the route has left is ignored. */
  private generation = 0;

  protected readonly cards = computed(() =>
    this.entries().map((entry) => ({
      entry,
      plan:
        entry.cover && !this.brokenCovers().has(entry.slug)
          ? mediaPlan(entry.cover, this.reducedMotion.reduced())
          : null,
      aspect: entry.cover ? mediaAspectRatio(entry.cover) : null,
      lang: languageTag(entry.textLocale),
      date: formatDate(entry.publishedAt, this.lang.langId()),
    })),
  );

  constructor() {
    effect(() => {
      const kind = this.valid();
      untracked(() => void this.loadFirstPage(kind));
    });
  }

  private async loadFirstPage(kind: ReturnType<typeof this.valid>): Promise<void> {
    const generation = ++this.generation;
    this.entries.set([]);
    this.nextCursor.set(null);
    this.brokenCovers.set(new Set());
    if (kind === null) {
      this.state.set('unavailable');
      return;
    }
    this.state.set('loading');
    const result = await this.editorial.list(kind, {});
    if (generation !== this.generation) {
      return;
    }
    if (result.status === 'ok') {
      this.entries.set(result.value.items);
      this.nextCursor.set(result.value.nextCursor ?? null);
      this.state.set('ready');
    } else {
      this.state.set('unavailable');
    }
  }

  protected async more(): Promise<void> {
    const kind = this.valid();
    const cursor = this.nextCursor();
    if (kind === null || cursor === null || this.loadingMore()) {
      return;
    }
    const generation = this.generation;
    this.loadingMore.set(true);
    const result = await this.editorial.list(kind, { cursor });
    this.loadingMore.set(false);
    if (generation !== this.generation || result.status !== 'ok') {
      return;
    }
    this.entries.update((current) => [...current, ...result.value.items]);
    this.nextCursor.set(result.value.nextCursor ?? null);
  }

  protected coverFailed(slug: string): void {
    this.brokenCovers.update((slugs) => new Set(slugs).add(slug));
  }
}
