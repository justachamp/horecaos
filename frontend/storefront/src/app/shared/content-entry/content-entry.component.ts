import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { EditorialService } from '../../services/editorial.service';
import { EDITORIAL_KINDS } from '../../utils/editorial-kinds';
import { TranslatePipe } from '../translate/translate.pipe';

/**
 * The home page's way into the editorial pages (ADR 0157, Decision 7): a small row of links, one
 * for each kind the brand turned on, and nothing at all when none is.
 *
 * Which kinds are on comes from `GET .../content/features`, which says "on" only when the tenant's
 * plan includes the editorial tier and the brand switched the kind on. When that read fails every
 * kind stays off, so this draws nothing and the home page is as it always was.
 */
@Component({
  selector: 'app-content-entry',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @if (links().length > 0) {
      <nav [attr.aria-label]="'content.editorialSection' | t">
        <ul class="flex flex-wrap gap-2">
          @for (link of links(); track link.kind) {
            <li>
              <a
                [routerLink]="'/' + link.kind"
                class="block rounded-full border border-zinc-700 px-4 py-2 text-xs font-semibold text-zinc-300 transition-colors hover:border-brand-accent hover:text-brand-accent"
                >{{ link.titleKey | t }}</a
              >
            </li>
          }
        </ul>
      </nav>
    }
  `,
})
export class ContentEntryComponent {
  private readonly editorial = inject(EditorialService);

  protected readonly links = computed(() => {
    const features = this.editorial.features();
    return EDITORIAL_KINDS.filter((entry) => features[entry.feature]);
  });
}
