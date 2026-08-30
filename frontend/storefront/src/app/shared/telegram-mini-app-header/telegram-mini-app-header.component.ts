import { Component } from '@angular/core';

/**
 * Header shown when the app is launched from Telegram Mini App.
 * Includes safe area for Dynamic Island (iPhone) with project accent gradient.
 */
@Component({
  selector: 'app-telegram-mini-app-header',
  standalone: true,
  templateUrl: './telegram-mini-app-header.component.html',
  styleUrl: './telegram-mini-app-header.component.scss',
})
export class TelegramMiniAppHeaderComponent {}
