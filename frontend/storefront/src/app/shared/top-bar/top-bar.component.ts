import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '../translate/translate.pipe';

@Component({
  selector: 'app-top-bar',
  imports: [RouterLink, TranslatePipe],
  templateUrl: './top-bar.component.html',
  styleUrl: './top-bar.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class TopBarComponent {
  readonly addressLabel = input('');
  readonly addressValue = input('');
}
