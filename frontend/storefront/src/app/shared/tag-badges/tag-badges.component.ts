import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import type { MenuItemTag } from '../../types/home.types';
import { TAG_ICON_PATHS } from '../../utils/menu-tags';

/**
 * The badges of a product (ADR 0174): a small pictogram from the closed list, when the tag has one, and the
 * tag's name in the customer's language. Text is always there, so a badge never relies on a drawing alone.
 */
@Component({
  selector: 'app-tag-badges',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (badges().length > 0) {
      <ul class="m-0 flex list-none flex-wrap gap-1.5 p-0" data-testid="tag-badges">
        @for (tag of badges(); track tag.id) {
          <li
            class="inline-flex items-center gap-1 rounded-full bg-surface-raised-2 px-2 py-0.5 text-[11px] font-medium leading-none text-zinc-200"
            [attr.data-tag]="tag.code"
          >
            @if (pathOf(tag); as path) {
              <svg
                width="12"
                height="12"
                viewBox="0 0 24 24"
                fill="currentColor"
                aria-hidden="true"
              >
                <path [attr.d]="path" />
              </svg>
            }
            <span>{{ tag.name }}</span>
          </li>
        }
      </ul>
    }
  `,
})
export class TagBadgesComponent {
  readonly tags = input<readonly MenuItemTag[]>([]);

  protected readonly badges = computed(() => this.tags().filter((tag) => tag.showAsBadge));

  protected pathOf(tag: MenuItemTag): string | null {
    return tag.icon ? (TAG_ICON_PATHS[tag.icon] ?? null) : null;
  }
}
