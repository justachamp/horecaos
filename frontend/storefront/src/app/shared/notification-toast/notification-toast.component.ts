import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { NotificationService } from '../../services/notification.service';

@Component({
  selector: 'app-notification-toast',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (notification.currentMessage()) {
      <div
        class="fixed left-1/2 z-50 max-w-[90%] -translate-x-1/2 rounded-lg bg-surface-raised-2 px-4 py-3 text-sm text-text-primary shadow-lg"
        style="top: calc(1rem + var(--safe-top, 0px));"
        role="status"
      >
        {{ notification.currentMessage() }}
      </div>
    }
  `,
})
export class NotificationToastComponent {
  constructor(public notification: NotificationService) {}
}
