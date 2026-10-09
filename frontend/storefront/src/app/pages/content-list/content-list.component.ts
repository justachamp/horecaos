import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { BackDirective } from '../../shared/back/back.directive';
import { ContentMediaComponent } from '../../shared/content-media/content-media.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { EditorialService } from '../../services/editorial.service';
import { LangService, platformTag } from '../../services/lang.service';
import type { EditorialListItem } from '../../types/editorial.types';
import {
  EMPLOYMENT_LABEL_KEYS,
  editorialKindOf,
  editorialTitleKey,
} from '../../utils/editorial-kinds';

type ListState = 'loading' | 'ready' | 'unavailable';

/**
 * The list page of one editorial kind (`/news`, `/gallery`, `/recipes`, `/careers`; ADR 0157,
 * Decision 7): newest first, a page at a time, "show more" by the server's cursor.
 *
 * The kind comes from the route's `data`, so one component serves all four. Anything that goes
 * wrong with the read (the kind is off for this brand, the server says 404 or fails, the body is
 * not a page) ends in the same plain "not available" page with a way home, never in an error
 * screen. It reads again when the language changes.
 */
@Component({
  selector: 'app-content-list',
  standalone: true,
  imports: [BackDirective, ContentMediaComponent, RouterLink, TranslatePipe],
  templateUrl: './content-list.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentListComponent {
  private readonly editorial = inject(EditorialService);
  private readonly lang = inject(LangService);

  protected readonly kind = editorialKindOf(inject(ActivatedRoute).snapshot.data['kind']);
  protected readonly titleKey = () => editorialTitleKey(this.kind ?? 'news');

  protected readonly state = signal<ListState>('loading');
  protected readonly items = signal<readonly EditorialListItem[]>([]);
  protected readonly nextCursor = signal<string | undefined>(undefined);
  protected readonly loadingMore = signal(false);
  protected readonly coverFailed = signal<ReadonlySet<string>>(new Set());

  /** A read that has been overtaken (the language changed while it was on the wire) is dropped. */
  private generation = 0;

  constructor() {
    effect(() => {
      this.lang.langId();
      untracked(() => void this.reload());
    });
  }

  protected async reload(): Promise<void> {
    const generation = ++this.generation;
    if (this.kind === null) {
      this.state.set('unavailable');
      return;
    }
    this.state.set('loading');
    this.items.set([]);
    this.nextCursor.set(undefined);
    const result = await this.editorial.list(this.kind);
    if (generation !== this.generation) {
      return;
    }
    if (result.status === 'ok') {
      this.items.set(result.data.items);
      this.nextCursor.set(result.data.nextCursor);
      this.state.set('ready');
    } else {
      this.state.set('unavailable');
    }
  }

  protected async loadMore(): Promise<void> {
    const cursor = this.nextCursor();
    if (this.kind === null || cursor === undefined || this.loadingMore()) {
      return;
    }
    const generation = this.generation;
    this.loadingMore.set(true);
    const result = await this.editorial.list(this.kind, { cursor });
    this.loadingMore.set(false);
    if (generation !== this.generation || result.status !== 'ok') {
      // The list stays as it was and the button stays for another try.
      return;
    }
    const known = new Set(this.items().map((item) => item.slug));
    this.items.update((items) => [
      ...items,
      ...result.data.items.filter((item) => !known.has(item.slug)),
    ]);
    this.nextCursor.set(result.data.nextCursor);
  }

  protected markCoverFailed(slug: string): void {
    this.coverFailed.update((set) => new Set(set).add(slug));
  }

  protected employmentKey(item: EditorialListItem): string | null {
    return item.employmentType ? EMPLOYMENT_LABEL_KEYS[item.employmentType] : null;
  }

  /** The date as the customer's language writes it; the ISO day if the browser cannot. */
  protected dateLabel(iso: string): string {
    try {
      return new Intl.DateTimeFormat(platformTag(this.lang.langId()), {
        dateStyle: 'medium',
      }).format(new Date(iso));
    } catch {
      return iso.slice(0, 10);
    }
  }
}
