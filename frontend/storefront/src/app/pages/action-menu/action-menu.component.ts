import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SectionHeaderComponent } from '../../shared/section-header/section-header.component';
import { TopBarComponent } from '../../shared/top-bar/top-bar.component';
import { TranslatePipe } from '../../shared/translate/translate.pipe';

@Component({
  selector: 'app-action-menu',
  standalone: true,
  imports: [CommonModule, SectionHeaderComponent, TopBarComponent, TranslatePipe],
  templateUrl: './action-menu.component.html',
  styleUrl: './action-menu.component.scss'
})
export class ActionMenuComponent {
  quickActions = [
    { titleKey: 'action.quickOrder', subtitleKey: 'action.quickOrderSubtitle', icon: '⚡' },
    { titleKey: 'action.orderHistory', subtitleKey: 'action.orderHistorySubtitle', icon: '🧾' },
    { titleKey: 'action.favorites', subtitleKey: 'action.favoritesSubtitle', icon: '❤️' },
    { titleKey: 'action.bonuses', subtitleKey: 'action.bonusesSubtitle', icon: '🎁' }
  ];

  menuTiles = [
    { title: 'Milliy taomlar', image: '/jizbiz/jizbiz_7.png' },
    { title: 'Salatlar', image: '/jizbiz/jizbiz_13.png' },
    { title: 'Shashlik', image: '/jizbiz/jizbiz_15.png' },
    { title: 'Yevropa', image: '/jizbiz/jizbiz_8.png' }
  ];
}
