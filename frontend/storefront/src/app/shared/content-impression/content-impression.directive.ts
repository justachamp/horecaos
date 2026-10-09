import { DestroyRef, Directive, ElementRef, OnInit, inject, input } from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';

/**
 * Reports an impression of a content item once the element showing it has actually been on screen.
 *
 * A carousel of ten banners has nine of them off to the side; counting them all on render would
 * tell the brand every banner was seen by everybody. So the element is watched with an
 * `IntersectionObserver` and counted when at least `impressionThreshold` of it (default half) is
 * visible. A tall element such as a product group, which never has half of itself on a phone
 * screen, passes a smaller threshold.
 *
 * Counting once per item and revision per page session is {@link ContentEventsService}'s job, not
 * this directive's, so a tile re-created by a re-render does not count twice. Where the browser has
 * no `IntersectionObserver` the element counts as seen when it appears: better a slightly generous
 * count than none.
 */
@Directive({ selector: '[appContentImpression]' })
export class ContentImpressionDirective implements OnInit {
  readonly target = input.required<{ id: string; revisionNo: number }>({
    alias: 'appContentImpression',
  });
  readonly threshold = input(0.5, { alias: 'impressionThreshold' });

  private readonly element = inject<ElementRef<Element>>(ElementRef).nativeElement;
  private readonly events = inject(ContentEventsService);
  private observer: IntersectionObserver | null = null;
  private reported = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.observer?.disconnect());
  }

  ngOnInit(): void {
    const { id, revisionNo } = this.target();
    if (typeof IntersectionObserver === 'undefined') {
      this.events.impression(id, revisionNo);
      return;
    }
    const threshold = this.threshold();
    this.observer = new IntersectionObserver(
      (entries) => {
        if (
          !this.reported &&
          entries.some((e) => e.isIntersecting && e.intersectionRatio >= threshold)
        ) {
          this.reported = true;
          this.events.impression(id, revisionNo);
          this.observer?.disconnect();
        }
      },
      { threshold: [threshold] },
    );
    this.observer.observe(this.element);
  }
}
