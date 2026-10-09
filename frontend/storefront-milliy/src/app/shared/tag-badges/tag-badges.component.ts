import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import type { MenuItemTag } from '../../types/home.types';
import { TAG_ICON_PATHS } from '../../utils/menu-tags';

/**
 * The badges of a dish (ADR 0174): a small pictogram from the closed list, when the tag has one, and the
 * tag's name in the customer's language. Text is always there, so a badge never relies on a drawing alone.
 */
@Component({
  selector: 'app-tag-badges',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (badges().length > 0) {
      <ul class="badges" data-testid="tag-badges">
        @for (tag of badges(); track tag.id) {
          <li class="badge" [attr.data-tag]="tag.code">
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
  styles: `
    .badges {
      display: flex;
      flex-wrap: wrap;
      gap: 6px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      border-radius: var(--radius-pill);
      padding: 3px 8px;
      background: var(--surface-sunken);
      color: var(--text-secondary);
      font: 600 11px/1 var(--font-ui);
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
