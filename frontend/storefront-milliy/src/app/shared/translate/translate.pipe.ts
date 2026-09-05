import { Pipe, PipeTransform, inject } from '@angular/core';
import { TranslateService } from '../../services/translate.service';

@Pipe({
  name: 't',
  standalone: true,
  pure: false,
})
export class TranslatePipe implements PipeTransform {
  private readonly translate = inject(TranslateService);

  transform(key: string, params?: Record<string, string | number>): string {
    return params
      ? this.translate.getWithParams(key, params)
      : this.translate.get(key);
  }
}
