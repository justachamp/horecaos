import { ComponentFixture } from '@angular/core/testing';

/** DOM helpers shared by the printing specs. No test framework is imported, so the app's own type check accepts the file. */

export async function flush(): Promise<void> {
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
  await new Promise<void>((resolve) => setTimeout(resolve, 0));
}

export function el<T extends HTMLElement>(fixture: ComponentFixture<unknown>, selector: string): T {
  return (fixture.nativeElement as HTMLElement).querySelector(selector) as T;
}

export function all<T extends HTMLElement>(
  fixture: ComponentFixture<unknown>,
  selector: string,
): T[] {
  return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll(selector)) as T[];
}

export async function click(fixture: ComponentFixture<unknown>, selector: string): Promise<void> {
  el<HTMLButtonElement>(fixture, selector).click();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
}

export function pick(fixture: ComponentFixture<unknown>, selector: string, value: string): void {
  const select = el<HTMLSelectElement>(fixture, selector);
  select.value = value;
  select.dispatchEvent(new Event('change'));
  fixture.detectChanges();
}

export function type(fixture: ComponentFixture<unknown>, selector: string, value: string): void {
  const input = el<HTMLInputElement>(fixture, selector);
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
}

export function check(fixture: ComponentFixture<unknown>, selector: string, on: boolean): void {
  const box = el<HTMLInputElement>(fixture, selector);
  box.checked = on;
  box.dispatchEvent(new Event('change'));
  fixture.detectChanges();
}
