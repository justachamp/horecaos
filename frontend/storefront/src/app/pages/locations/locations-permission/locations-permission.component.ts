import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { TranslatePipe } from '../../../shared/translate/translate.pipe';
import { BackDirective } from '../../../shared/back/back.directive';

@Component({
  selector: 'app-locations-permission',
  standalone: true,
  imports: [CommonModule, TranslatePipe, BackDirective],
  templateUrl: './locations-permission.component.html',
  styleUrl: './locations-permission.component.scss',
})
export class LocationsPermissionComponent {
  constructor(private router: Router) {}

  tryAgain(): void {
    this.router.navigate(['/locations/add']);
  }
}
