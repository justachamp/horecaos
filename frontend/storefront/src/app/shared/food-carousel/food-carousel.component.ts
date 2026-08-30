import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FoodCardComponent } from '../food-card/food-card.component';
import type { MenuItem } from '../../types/home.types';

@Component({
  selector: 'app-food-carousel',
  standalone: true,
  imports: [CommonModule, FoodCardComponent],
  templateUrl: './food-carousel.component.html',
  styleUrl: './food-carousel.component.scss'
})
export class FoodCarouselComponent {
  @Input() items: MenuItem[] = [];
}
