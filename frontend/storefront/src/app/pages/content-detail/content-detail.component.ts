import { toSignal } from '@angular/core/rxjs-interop';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';

import { BackDirective } from '../../shared/back/back.directive';
import { ContentBlocksComponent } from '../../shared/content-blocks/content-blocks.component';
import { ContentMediaComponent } from '../../shared/content-media/content-media.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { EditorialService } from '../../services/editorial.service';
import { LangService, platformTag } from '../../services/lang.service';
import type { EditorialDetail, VacancyBranch } from '../../types/editorial.types';
import { EMPLOYMENT_LABEL_KEYS, editorialKindOf } from '../../utils/editorial-kinds';
import { vacancyContact } from '../../utils/vacancy-contact';

type DetailState = 'loading' | 'ready' | 'unavailable';

/**
 * One editorial document (`/news/:slug`, `/gallery/:slug`, `/recipes/:slug`, `/careers/:slug`;
 * ADR 0157, Decisions 6 and 7): its head, its body through the block renderer, an album's photos
 * and a vacancy's details.
 *
 * <h2>A vacancy is a way to reach the employer, nothing more</h2>
 * It shows the employment type, the branch and the one contact the brand gave, as a link when the
 * contact passes its own rule (`vacancyContact`) and as plain text when it does not. The platform
 * stores no candidate data, so there is no application form and no field to type into.
 *
 * Anything that goes wrong with the read ends in the same plain "not available" page with a way
 * home, never an error screen. The address can change under the component (another slug of the
 * same kind), and so can the language; a read that has been overtaken is dropped.
 */
@Component({
  selector: 'app-content-detail',
  standalone: true,
  imports: [
    BackDirective,
    ContentBlocksComponent,
    ContentMediaComponent,
    RouterLink,
    TranslatePipe,
  ],
  templateUrl: './content-detail.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentDetailComponent {
  private readonly editorial = inject(EditorialService);
  private readonly lang = inject(LangService);
  private readonly route = inject(ActivatedRoute);

  protected readonly kind = editorialKindOf(this.route.snapshot.data['kind']);
  /** Where "back" goes: the list of this kind. */
  protected readonly backTo = this.kind ? `/${this.kind}` : '/home';
  protected readonly employmentKeys = EMPLOYMENT_LABEL_KEYS;

  private readonly slug = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('slug') ?? '')),
    { initialValue: this.route.snapshot.paramMap.get('slug') ?? '' },
  );

  protected readonly state = signal<DetailState>('loading');
  protected readonly doc = signal<EditorialDetail | null>(null);
  protected readonly coverFailed = signal(false);
  private readonly failedImages = signal<ReadonlySet<number>>(new Set());

  protected readonly contact = computed(() => {
    const apply = this.doc()?.vacancy?.apply;
    return apply ? vacancyContact(apply) : null;
  });
  protected readonly visibleImages = computed(() => {
    const failed = this.failedImages();
    return (this.doc()?.images ?? [])
      .map((image, index) => ({ image, index }))
      .filter((entry) => !failed.has(entry.index));
  });

  private generation = 0;

  constructor() {
    effect(() => {
      const slug = this.slug();
      this.lang.langId();
      untracked(() => void this.load(slug));
    });
  }

  private async load(slug: string): Promise<void> {
    const generation = ++this.generation;
    if (this.kind === null || slug === '') {
      this.state.set('unavailable');
      return;
    }
    this.state.set('loading');
    this.doc.set(null);
    this.coverFailed.set(false);
    this.failedImages.set(new Set());
    const result = await this.editorial.detail(this.kind, slug);
    if (generation !== this.generation) {
      return;
    }
    if (result.status === 'ok') {
      this.doc.set(result.data);
      this.state.set('ready');
    } else {
      this.state.set('unavailable');
    }
  }

  protected markImageFailed(index: number): void {
    this.failedImages.update((set) => new Set(set).add(index));
  }

  protected branchLabel(branch: VacancyBranch): string {
    return [branch.name, branch.addressLine, branch.city].filter(Boolean).join(', ');
  }

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
