import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BackDirective } from '../../../shared/back/back.directive';

@Component({
  selector: 'app-profile-invite',
  imports: [CommonModule, BackDirective],
  templateUrl: './profile-invite.html',
  styleUrl: './profile-invite.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ProfileInviteComponent {}
