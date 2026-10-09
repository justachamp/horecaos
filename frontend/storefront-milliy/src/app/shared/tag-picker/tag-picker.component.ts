import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';

import { LangService } from '../../services/lang.service';
import type { OfferedTag } from '../../services/reviews.service';
import { tagLabel } from '../../utils/tag-label';

/**
 * The one-tap reasons offered with a rating (ADR 0159, Decision 3): a row of toggle chips in the
 * customer's own language. It holds no selection of its own and decides nothing about which tags
 * fit a rating or how many may be chosen; the page does, because the page knows the rating and
 * submits the result.
 */
@Component({
  selector: 'app-tag-picker',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="chips" role="group" [attr.aria-label]="label()">
      @for (tag of tags(); track tag.id) {
        <button
          type="button"
          class="tag-chip"
          [class.is-selected]="selected().includes(tag.id)"
          [attr.aria-pressed]="selected().includes(tag.id)"
          (click)="toggled.emit(tag)"
          data-testid="tag-chip"
        >
          {{ wording(tag) }}
        </button>
      }
    </div>
  `,
  styleUrl: './tag-picker.component.scss',
})
export class TagPickerComponent {
  private readonly lang = inject(LangService);

  readonly tags = input.required<readonly OfferedTag[]>();
  readonly selected = input<readonly string[]>([]);
  /** The group's accessible name, already translated. */
  readonly label = input('');

  readonly toggled = output<OfferedTag>();

  protected wording(tag: OfferedTag): string {
    return tagLabel(tag, this.lang.langId());
  }
}
