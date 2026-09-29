import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { App } from './app';
import { BottomNavComponent } from './shared/bottom-nav/bottom-nav.component';

@Component({ selector: 'app-bottom-nav', standalone: true, template: '<nav data-testid="tabs"></nav>' })
class StubBottomNav {}

@Component({ standalone: true, template: '' })
class Blank {}

async function open(url: string): Promise<HTMLElement> {
  TestBed.configureTestingModule({
    imports: [App],
    providers: [provideRouter([{ path: '**', component: Blank }])],
  });
  TestBed.overrideComponent(App, {
    remove: { imports: [BottomNavComponent] },
    add: { imports: [StubBottomNav] },
  });
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  await TestBed.inject(Router).navigateByUrl(url);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('App shell: the tab bar', () => {
  it.each(['/home', '/cart', '/orders', '/profile', '/dine-in/table'])('shows on %s', async (url) => {
    const host = await open(url);

    expect(host.querySelector('[data-testid="tabs"]')).not.toBeNull();
  });

  it.each(['/checkout', '/auth/login', '/auth/code'])(
    'is hidden on %s, a screen that owns the whole viewport',
    async (url) => {
      const host = await open(url);

      expect(host.querySelector('[data-testid="tabs"]')).toBeNull();
    },
  );
});
