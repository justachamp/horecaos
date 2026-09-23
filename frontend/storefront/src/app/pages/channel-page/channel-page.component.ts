import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { BackDirective } from '../../shared/back/back.directive';
import { isNotFound } from '../../core/api/problem-details';
import { ChannelPageContentPipe } from '../../shared/pipes/channel-page-content.pipe';
import { TranslatePipe } from '../../shared/translate/translate.pipe';
import { ChannelPagesService, isChannelPageSlug } from '../../services/channel-pages.service';
import { LangService } from '../../services/lang.service';

type LoadState = 'loading' | 'ready' | 'not-found' | 'error';

/**
 * Row 10.5's storefront static pages — about, contacts, delivery terms,
 * privacy/offer — at `/pages/:slug`.
 *
 * Mirrors {@code TermsOfConditionsComponent}'s own load/state shape, minus
 * the accept flow: nobody accepts an "About" page. `slug` comes from the
 * route (`ActivatedRoute.snapshot`, read once — the route itself changes
 * identity on navigation to a different slug rather than this component
 * being reused in place) and an unrecognised one renders the same
 * `not-found` state as a slug the channel has never published, since a
 * customer who mistyped a URL and one who followed a stale link should see
 * the same honest page, not a stack trace.
 */
@Component({
  selector: 'app-channel-page',
  standalone: true,
  imports: [TranslatePipe, ChannelPageContentPipe, BackDirective],
  templateUrl: './channel-page.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChannelPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly pages = inject(ChannelPagesService);
  private readonly lang = inject(LangService);

  readonly selectedLangId = this.lang.langId;
  readonly state = signal<LoadState>('loading');
  readonly body = signal('');
  readonly slug = (this.route.snapshot.paramMap.get('slug') ?? '').toLowerCase();

  constructor() {
    // Refetch on a language switch, the same reason TermsOfConditionsComponent's own effect does.
    effect(() => {
      this.selectedLangId();
      void this.load();
    });
  }

  private async load(): Promise<void> {
    if (!isChannelPageSlug(this.slug)) {
      this.state.set('not-found');
      return;
    }
    this.state.set('loading');
    try {
      const page = await this.pages.page(this.slug);
      this.body.set(page.body);
      this.state.set('ready');
    } catch (error) {
      this.state.set(isNotFound(error) ? 'not-found' : 'error');
    }
  }

  protected titleKey(): string {
    return 'pages.slug.' + camelCase(this.slug);
  }
}

function camelCase(slug: string): string {
  return slug.replace(/-([a-z])/g, (_match, letter: string) => letter.toUpperCase());
}
