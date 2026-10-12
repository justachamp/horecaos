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
import { isEditorialKind, type EditorialDetail } from '../../types/editorial.types';
import { vacancyContact } from '../../utils/content-href';
import { mediaAspectRatio, mediaPlan } from '../../utils/content-media';
import { formatDate, languageTag } from '../../utils/content-text';
import { ContentBlocksComponent } from '../../shared/content-blocks/content-blocks.component';
import { ContentMediaComponent } from '../../shared/content-media/content-media.component';
import { EditorialUnavailableComponent } from '../../shared/editorial-unavailable/editorial-unavailable.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';

type LoadState = 'loading' | 'ready' | 'unavailable';

/**
 * One editorial entry: a news article, a gallery album, a recipe or a vacancy (ADR 0157, Release 5).
 * One component for the four routes; the kind comes from the route's data and the slug from its path.
 *
 * The body is drawn by `ContentBlocksComponent`, the one renderer; an album's pictures are a grid.
 * A vacancy shows its employment type, its branch and its single contact -- a call link, a mail
 * link, or an `https` link in a new tab, and plain text when the value fails the scheme rule -- and
 * **no application form**: the platform stores no applicant data.
 *
 * A 404, a kind that is off, a slug that was never published, and a route that is not one of the
 * four kinds are the same quiet "not available" page, never an error screen.
 */
@Component({
  selector: 'app-editorial-detail',
  standalone: true,
  imports: [
    ContentBlocksComponent,
    ContentMediaComponent,
    EditorialUnavailableComponent,
    RouterLink,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './editorial-detail.component.html',
  styleUrl: './editorial-detail.component.scss',
})
export class EditorialDetailComponent {
  private readonly editorial = inject(EditorialService);
  private readonly lang = inject(LangService);
  private readonly reducedMotion = inject(ReducedMotionService);

  /** From the route's `data`: `news`, `gallery`, `recipes` or `careers`. */
  readonly kind = input<string | undefined>(undefined);
  /** From the route's path. */
  readonly slug = input<string | undefined>(undefined);

  protected readonly valid = computed(() => {
    const kind = this.kind();
    return isEditorialKind(kind) ? kind : null;
  });
  protected readonly state = signal<LoadState>('loading');
  protected readonly entry = signal<EditorialDetail | null>(null);
  private readonly coverBroken = signal(false);
  private readonly brokenImages = signal<ReadonlySet<number>>(new Set());
  /** Bumped by every read, so an answer for an entry the route has left is ignored. */
  private generation = 0;

  protected readonly coverPlan = computed(() => {
    const cover = this.entry()?.cover;
    return cover && !this.coverBroken() ? mediaPlan(cover, this.reducedMotion.reduced()) : null;
  });
  protected readonly coverAspect = computed(() => {
    const cover = this.entry()?.cover;
    return cover ? mediaAspectRatio(cover) : null;
  });
  protected readonly images = computed(() =>
    (this.entry()?.images ?? [])
      .map((image, index) => ({
        index,
        caption: image.caption,
        alt: image.media.alt ?? '',
        aspect: mediaAspectRatio(image.media),
        plan: this.brokenImages().has(index) ? null : mediaPlan(image.media, false),
      }))
      .filter((image) => image.plan !== null),
  );
  protected readonly date = computed(() => {
    const entry = this.entry();
    return entry ? formatDate(entry.publishedAt, this.lang.langId()) : null;
  });
  protected readonly titleLang = computed(() => languageTag(this.entry()?.textLocale));
  protected readonly bodyLang = computed(() => {
    const entry = this.entry();
    return entry ? languageTag(entry.bodyLocale ?? entry.textLocale) : null;
  });
  protected readonly contact = computed(() => vacancyContact(this.entry()?.vacancy?.apply));

  constructor() {
    effect(() => {
      const kind = this.valid();
      const slug = this.slug();
      untracked(() => void this.load(kind, slug));
    });
  }

  private async load(kind: ReturnType<typeof this.valid>, slug: string | undefined): Promise<void> {
    const generation = ++this.generation;
    this.entry.set(null);
    this.coverBroken.set(false);
    this.brokenImages.set(new Set());
    if (kind === null || !slug) {
      this.state.set('unavailable');
      return;
    }
    this.state.set('loading');
    const result = await this.editorial.detail(kind, slug);
    if (generation !== this.generation) {
      return;
    }
    if (result.status === 'ok') {
      this.entry.set(result.value);
      this.state.set('ready');
    } else {
      this.state.set('unavailable');
    }
  }

  protected coverFailed(): void {
    this.coverBroken.set(true);
  }

  protected imageFailed(index: number): void {
    this.brokenImages.update((indexes) => new Set(indexes).add(index));
  }
}
