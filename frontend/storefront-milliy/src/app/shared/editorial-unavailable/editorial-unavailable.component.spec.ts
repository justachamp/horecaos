import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { TranslateService } from '../../services/translate.service';
import { EditorialUnavailableComponent } from './editorial-unavailable.component';

class FakeTranslateService {
  get = (key: string) => key;
  getWithParams = (key: string) => key;
  current = () => ({});
}

describe('EditorialUnavailableComponent: a section that is not there is a quiet page, not an error', () => {
  function render() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', children: [] }]),
        { provide: TranslateService, useClass: FakeTranslateService },
      ],
    });
    const fixture = TestBed.createComponent(EditorialUnavailableComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('says the section is not available, plainly', () => {
    const host = render();

    expect(host.querySelector('[data-testid="editorial-unavailable"]')).not.toBeNull();
    expect(host.querySelector('h1')?.textContent?.trim()).toBe('editorial.unavailableTitle');
    expect(host.textContent).toContain('editorial.unavailableBody');
  });

  it('is not an alert, and has no retry: nothing is broken', () => {
    const host = render();

    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector('button')).toBeNull();
  });

  it('leads the way home', () => {
    const host = render();

    expect(host.querySelector('a')?.getAttribute('href')).toBe('/home');
    expect(host.querySelector('a')?.textContent?.trim()).toBe('home.goHome');
  });
});
