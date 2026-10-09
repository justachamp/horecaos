import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';

import { rememberSelectedCategory, type ResolvedContentLink } from '../../utils/content-links';

/**
 * The tap target around a piece of content: an anchor when it goes somewhere, a plain `<figure>`
 * when it does not.
 *
 * The caller resolves the link (`resolveContentLink`) and hands over the result, so it can also
 * decide things that depend on it, such as whether to show a call to action at all. An external
 * link opens in a new tab with `rel="noopener noreferrer"`; a category link first leaves the
 * choice where the category page looks for it. {@link activated} fires on the tap, for the
 * click counter.
 */
@Component({
  selector: 'app-content-link',
  standalone: true,
  imports: [NgTemplateOutlet, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'contents' },
  template: `
    @if (target(); as link) {
      @if (link.kind === 'external') {
        <a
          [class]="anchorClass()"
          [href]="link.href"
          target="_blank"
          rel="noopener noreferrer"
          (click)="activate(link)"
        >
          <ng-container [ngTemplateOutlet]="body" />
        </a>
      } @else {
        <a [class]="anchorClass()" [routerLink]="link.commands" (click)="activate(link)">
          <ng-container [ngTemplateOutlet]="body" />
        </a>
      }
    } @else {
      <figure [class]="anchorClass()">
        <ng-container [ngTemplateOutlet]="body" />
      </figure>
    }
    <ng-template #body><ng-content /></ng-template>
  `,
})
export class ContentLinkComponent {
  readonly target = input.required<ResolvedContentLink | null>();
  /** Classes for the anchor, or for the figure that stands in for it, so both lay out the same. */
  readonly anchorClass = input('');
  readonly activated = output<void>();

  protected activate(link: ResolvedContentLink): void {
    if (link.kind === 'route' && link.category) {
      rememberSelectedCategory(link.category);
    }
    this.activated.emit();
  }
}
