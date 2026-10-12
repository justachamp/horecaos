import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import type { ContentLink } from '../../types/content.types';
import { resolveContentLink } from '../../utils/content-link';

/**
 * Wraps content in the link its `ContentLink` allows -- or in nothing.
 *
 * A link in the platform's own screens becomes a router link; an `EXTERNAL` one becomes an
 * `<a target="_blank" rel="noopener noreferrer">` only after `resolveContentLink` has checked, in
 * the client, that the address parses and its scheme is exactly `https:`. Anything else --
 * `NONE`, `PROMOTION` (this storefront has no promotion screen), an unknown type, an address that
 * fails the check -- leaves the content as plain, un-clickable markup. `activated` fires only when
 * a real link was followed.
 */
@Component({
  selector: 'app-content-link',
  standalone: true,
  imports: [NgTemplateOutlet, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    :host {
      display: block;
    }
    .link {
      display: block;
      color: inherit;
      text-decoration: none;
    }
    a.link:focus-visible {
      outline: 2px solid var(--brand-accent-deep);
      outline-offset: 2px;
    }
  `,
  template: `
    <ng-template #body><ng-content /></ng-template>
    @if (route(); as target) {
      <a
        class="link"
        [routerLink]="target.commands"
        [queryParams]="target.queryParams"
        (click)="activated.emit()"
      >
        <ng-container *ngTemplateOutlet="body" />
      </a>
    } @else if (externalUrl(); as url) {
      <a
        class="link"
        [href]="url"
        target="_blank"
        rel="noopener noreferrer"
        (click)="activated.emit()"
      >
        <ng-container *ngTemplateOutlet="body" />
      </a>
    } @else {
      <div class="link"><ng-container *ngTemplateOutlet="body" /></div>
    }
  `,
})
export class ContentLinkComponent {
  readonly link = input<ContentLink | null | undefined>(null);

  /** A real link was followed (a tap on content that is not a link does not fire it). */
  readonly activated = output<void>();

  private readonly resolved = computed(() => resolveContentLink(this.link()));

  protected readonly route = computed(() => {
    const target = this.resolved();
    return target?.kind === 'route' ? target : null;
  });

  protected readonly externalUrl = computed(() => {
    const target = this.resolved();
    return target?.kind === 'external' ? target.url : null;
  });
}
