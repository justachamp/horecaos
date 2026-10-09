import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { EditorialService } from '../../services/editorial.service';
import type { EditorialKind } from '../../types/editorial.types';
import { TranslatePipe } from '../translate/translate.pipe';

/** The four kinds in the order the home screen lists them, with the feature each one needs. */
const ENTRIES: readonly {
  readonly kind: EditorialKind;
  readonly feature: 'news' | 'gallery' | 'recipes' | 'vacancies';
}[] = [
  { kind: 'news', feature: 'news' },
  { kind: 'gallery', feature: 'gallery' },
  { kind: 'recipes', feature: 'recipes' },
  { kind: 'careers', feature: 'vacancies' },
];

/**
 * The home screen's way into the editorial pages (ADR 0157, Release 5): one link for each kind the
 * brand runs, and nothing at all when it runs none -- which is also what a failed `features` read
 * looks like, so the home screen never shows a link to a page that would be "not available".
 */
@Component({
  selector: 'app-editorial-links',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './editorial-links.component.html',
  styleUrl: './editorial-links.component.scss',
})
export class EditorialLinksComponent {
  private readonly editorial = inject(EditorialService);

  protected readonly kinds = computed(() => {
    const features = this.editorial.features();
    return ENTRIES.filter((entry) => features[entry.feature]).map((entry) => entry.kind);
  });
}
