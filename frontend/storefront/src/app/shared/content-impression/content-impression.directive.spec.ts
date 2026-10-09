import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ContentEventsService } from '../../services/content-events.service';
import { ContentImpressionDirective } from './content-impression.directive';

@Component({
  imports: [ContentImpressionDirective],
  template: `
    @if (shown()) {
      <div [appContentImpression]="{ id: 'item-1', revisionNo: 3 }" [impressionThreshold]="ratio()">
        tile
      </div>
    }
  `,
})
class HostComponent {
  readonly shown = signal(true);
  readonly ratio = signal(0.5);
}

class FakeEvents {
  impression = vi.fn();
}

type Callback = (entries: Partial<IntersectionObserverEntry>[]) => void;

class FakeObserver {
  static instances: FakeObserver[] = [];
  readonly disconnect = vi.fn();
  readonly observe = vi.fn();
  constructor(
    readonly callback: Callback,
    readonly options: IntersectionObserverInit,
  ) {
    FakeObserver.instances.push(this);
  }
}

function setUp() {
  const events = new FakeEvents();
  TestBed.configureTestingModule({
    providers: [{ provide: ContentEventsService, useValue: events }],
  });
  const fixture = TestBed.createComponent(HostComponent);
  return { fixture, events };
}

describe('ContentImpressionDirective without IntersectionObserver', () => {
  it('counts the impression as soon as the element exists', () => {
    const { fixture, events } = setUp();

    fixture.detectChanges();

    expect(events.impression).toHaveBeenCalledTimes(1);
    expect(events.impression).toHaveBeenCalledWith('item-1', 3);
  });
});

describe('ContentImpressionDirective with IntersectionObserver', () => {
  beforeEach(() => {
    FakeObserver.instances = [];
    vi.stubGlobal('IntersectionObserver', FakeObserver);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('counts nothing until the element is actually in view', () => {
    const { fixture, events } = setUp();

    fixture.detectChanges();

    expect(FakeObserver.instances).toHaveLength(1);
    expect(FakeObserver.instances[0].observe).toHaveBeenCalledTimes(1);
    expect(events.impression).not.toHaveBeenCalled();
  });

  it('asks to be told at the threshold it was given', () => {
    const { fixture } = setUp();
    fixture.componentInstance.ratio.set(0.1);

    fixture.detectChanges();

    expect(FakeObserver.instances[0].options.threshold).toEqual([0.1]);
  });

  it('ignores an entry below the threshold', () => {
    const { fixture, events } = setUp();
    fixture.detectChanges();

    FakeObserver.instances[0].callback([{ isIntersecting: true, intersectionRatio: 0.2 }]);

    expect(events.impression).not.toHaveBeenCalled();
  });

  it('counts once when it comes into view, then stops watching', () => {
    const { fixture, events } = setUp();
    fixture.detectChanges();
    const observer = FakeObserver.instances[0];

    observer.callback([{ isIntersecting: true, intersectionRatio: 0.9 }]);
    observer.callback([{ isIntersecting: true, intersectionRatio: 1 }]);

    expect(events.impression).toHaveBeenCalledTimes(1);
    expect(events.impression).toHaveBeenCalledWith('item-1', 3);
    expect(observer.disconnect).toHaveBeenCalled();
  });

  it('stops watching when the element goes away without ever being seen', () => {
    const { fixture, events } = setUp();
    fixture.detectChanges();
    const observer = FakeObserver.instances[0];

    fixture.componentInstance.shown.set(false);
    fixture.detectChanges();

    expect(observer.disconnect).toHaveBeenCalled();
    expect(events.impression).not.toHaveBeenCalled();
  });
});
