import { ChangeDetectionStrategy, Component, OnInit, inject, output, signal } from '@angular/core';

import { ApiError } from '../../../core/api/problem-details';
import { CurrentBrand } from '../../../core/auth/current-brand';
import { I18n } from '../../../core/i18n/i18n';
import { MessageKey } from '../../../core/i18n/messages.en';
import { TPipe } from '../../../core/i18n/t.pipe';
import { describeApiError } from '../../orders/order-errors';
import { ContentApi, ContentProblem, UnhealthyItem } from './content-api';
import { KIND_KEYS, VISIBILITY_KEYS, problemText } from './content-texts';

/**
 * What is wrong with what customers are being shown (ADR 0157, Staff API: `GET /health`).
 *
 * Live items with a link whose target left the menu, a card whose promotion stopped, a link whose
 * site left the list of allowed links, a language that is missing, an image that has not rendered.
 * **The storefront has already degraded each of these quietly** -- a link becomes "nowhere", a card
 * is dropped -- so a quiet degradation is never a silent one: this is the operator's view of the same
 * problems. Choosing a row opens the item.
 */
@Component({
  selector: 'q-content-health',
  imports: [TPipe],
  templateUrl: './content-health-panel.html',
  styleUrl: './content-health-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ContentHealthPanel implements OnInit {
  private readonly api = inject(ContentApi);
  private readonly brand = inject(CurrentBrand);
  private readonly i18n = inject(I18n);

  readonly open = output<UnhealthyItem>();
  /** The count after each read, for the badge on the page. */
  readonly counted = output<number>();

  protected readonly items = signal<readonly UnhealthyItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  /** Reads again; the page calls this after an item changes. */
  async refresh(): Promise<void> {
    const scope = this.brand.scope();
    if (scope === null) {
      this.loading.set(false);
      return;
    }
    try {
      const items = await this.api.health(scope);
      this.items.set(items);
      this.error.set(null);
      this.counted.emit(items.length);
    } catch (failure) {
      this.error.set(
        failure instanceof ApiError
          ? describeApiError(failure, (key, values) => this.i18n.t(key, values))
          : this.i18n.t('marketing.content.health.failed'),
      );
    } finally {
      this.loading.set(false);
    }
  }

  protected kindKey(item: UnhealthyItem): MessageKey {
    return KIND_KEYS[item.kind];
  }

  protected visibilityKey(item: UnhealthyItem): MessageKey {
    return VISIBILITY_KEYS[item.visibility];
  }

  protected sentence(problem: ContentProblem): string {
    return problemText((key, values) => this.i18n.t(key, values), problem);
  }
}
