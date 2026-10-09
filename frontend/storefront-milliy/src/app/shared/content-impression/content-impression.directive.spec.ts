import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ContentEventsService } from '../../services/content-events.service';
import { ContentImpressionDirective } from './content-impression.directive';

@Component({
  standalone: true,
  imports: [ContentImpressionDirective],
  template: `<div class="seen" [appContentImpression]="item()">item</div>`,
})
class HostComponent {
  readonly item = signal({ id: 'item-1', revisionNo: 4 });
}

class FakeEvents {
  report = vi.fn();
}

type ObserverCallback = (entries: { isIntersecting: boolean }[]) => void;

class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = [];
  readonly observe = vi.fn();
  readonly disconnect = vi.fn();
  constructor(
    readonly callback: ObserverCallback,
    readonly options?: IntersectionObserverInit,
  ) {
    FakeIntersectionObserver.instances.push(this);
  }
  fire(isIntersecting: boolean): void {
    this.callback([{ isIntersecting }]);
  }
}

function render() {
  const events = new FakeEvents();
  TestBed.configureTestingModule({
    providers: [{ provide: ContentEventsService, useValue: events }],
  });
  const fixture = TestBed.createComponent(HostComponent);
  fixture.detectChanges();
  return { fixture, events };
}

afterEach(() => {
  vi.unstubAllGlobals();
  FakeIntersectionObserver.instances = [];
});

describe('ContentImpressionDirective: an impression is a piece of content actually on screen', () => {
  it('reports nothing until at least half of the element has been in view', () => {
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    const { events } = render();
    const [observer] = FakeIntersectionObserver.instances;

    expect(observer.observe).toHaveBeenCalledTimes(1);
    expect(observer.options?.threshold).toBe(0.5);
    observer.fire(false);

    expect(events.report).not.toHaveBeenCalled();
  });

  it('reports one impression when it comes into view, then stops watching', () => {
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    const { events } = render();
    const [observer] = FakeIntersectionObserver.instances;

    observer.fire(true);
    observer.fire(true);

    expect(events.report).toHaveBeenCalledTimes(1);
    expect(events.report).toHaveBeenCalledWith('item-1', 4, 'IMPRESSION');
    expect(observer.disconnect).toHaveBeenCalled();
  });

  it('reports on render when the browser cannot tell what is in view', () => {
    vi.stubGlobal('IntersectionObserver', undefined);
    const { events } = render();

    expect(events.report).toHaveBeenCalledWith('item-1', 4, 'IMPRESSION');
    expect(events.report).toHaveBeenCalledTimes(1);
  });

  it('stops watching when the element goes away', () => {
    vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver);
    const { fixture, events } = render();
    const [observer] = FakeIntersectionObserver.instances;

    fixture.destroy();
    observer.fire(true);

    expect(observer.disconnect).toHaveBeenCalled();
    expect(events.report).not.toHaveBeenCalled();
  });
});
