import { Directive, ElementRef, OnDestroy, OnInit, inject, input } from '@angular/core';

import { ContentEventsService } from '../../services/content-events.service';

/** What the counters file an impression under. */
export interface ImpressionTarget {
  readonly id: string;
  readonly revisionNo: number;
}

/**
 * Reports one impression of a piece of content, when it has actually been on screen.
 *
 * "On screen" is half of the element in view (`IntersectionObserver`, threshold 0.5), so a banner
 * scrolled out of sight to the right of the carousel is not counted as seen. A browser that cannot
 * tell reports on render instead. Once per element: the events service also keeps one per item and
 * revision for the page session, so a list that re-renders does not count twice.
 */
@Directive({ selector: '[appContentImpression]', standalone: true })
export class ContentImpressionDirective implements OnInit, OnDestroy {
  readonly appContentImpression = input.required<ImpressionTarget>();

  private readonly events = inject(ContentEventsService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private observer: IntersectionObserver | null = null;
  private done = false;

  ngOnInit(): void {
    if (typeof IntersectionObserver === 'undefined') {
      this.report();
      return;
    }
    this.observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          this.report();
        }
      },
      { threshold: 0.5 },
    );
    this.observer.observe(this.host.nativeElement);
  }

  ngOnDestroy(): void {
    this.done = true;
    this.stop();
  }

  private report(): void {
    if (this.done) {
      return;
    }
    this.done = true;
    const target = this.appContentImpression();
    this.events.report(target.id, target.revisionNo, 'IMPRESSION');
    this.stop();
  }

  private stop(): void {
    this.observer?.disconnect();
    this.observer = null;
  }
}
