import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { inject } from '@angular/core';

import { ICONS, type IconName } from './icon-data';

/**
 * One glyph from the design's icon set, coloured by `currentColor`.
 *
 * The markup is trusted deliberately: it is compile-time constant data in
 * `icon-data.ts`, checked into this repository, never user input and never
 * fetched. `bypassSecurityTrustHtml` on a constant is the correct call;
 * the same call on anything arriving at runtime would not be.
 */
@Component({
  selector: 'app-icon',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<svg
    [attr.viewBox]="glyph().viewBox"
    [attr.width]="size()"
    [attr.height]="size()"
    fill="none"
    aria-hidden="true"
    focusable="false"
    [style.display]="'block'"
    [style.flexShrink]="0"
    [innerHTML]="body()"
  ></svg>`,
})
export class IconComponent {
  private readonly sanitizer = inject(DomSanitizer);

  readonly name = input.required<IconName>();
  readonly size = input(24);

  protected readonly glyph = computed(() => ICONS[this.name()]);
  protected readonly body = computed<SafeHtml>(() =>
    this.sanitizer.bypassSecurityTrustHtml(this.glyph().body),
  );
}
