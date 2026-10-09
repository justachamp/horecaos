import type { CartResponseItem } from '../types/cart.types';
import { canStepDown, canStepUp } from './auto-add-line';

function item(quantity: number, autoAdd?: CartResponseItem['autoAdd']): CartResponseItem {
  return { quantity, ...(autoAdd ? { autoAdd, origin: 'AUTO_ADD' } : {}) } as CartResponseItem;
}

const BOX = (over: Partial<NonNullable<CartResponseItem['autoAdd']>> = {}) => ({
  ruleCode: 'BOX',
  text: null,
  mandatory: false,
  removable: true,
  minimumQuantity: 0,
  maximumQuantity: 5,
  ...over,
});

describe('a line’s stepper bounds (ADR 0158)', () => {
  it('lets a line the customer chose go down while it holds something, and up without limit', () => {
    expect(canStepDown(item(2), 1)).toBe(true);
    expect(canStepDown(item(0), 1)).toBe(false);
    expect(canStepUp(item(998), 1)).toBe(true);
  });

  it('lets a removable rule line go down to nothing, which removes it', () => {
    expect(canStepDown(item(1, BOX()), 1)).toBe(true);
  });

  it('stops a mandatory rule line at its computed quantity, and says no to going below it', () => {
    const mandatory = BOX({
      mandatory: true,
      removable: false,
      minimumQuantity: 2,
      maximumQuantity: 4,
    });

    expect(canStepDown(item(3, mandatory), 1)).toBe(true);
    expect(canStepDown(item(2, mandatory), 1)).toBe(false);
  });

  it('never lets a rule line that cannot be removed reach zero, whatever its floor says', () => {
    expect(canStepDown(item(1, BOX({ removable: false, minimumQuantity: 0 })), 1)).toBe(false);
  });

  it('stops a rule line at its ceiling', () => {
    expect(canStepUp(item(4, BOX({ maximumQuantity: 5 })), 1)).toBe(true);
    expect(canStepUp(item(5, BOX({ maximumQuantity: 5 })), 1)).toBe(false);
  });

  it('moves by the portion for a line sold by the portion, without float drift', () => {
    const portions = BOX({ minimumQuantity: 0.5, maximumQuantity: 1.5 });

    expect(canStepDown(item(1, portions), 0.5)).toBe(true);
    expect(canStepDown(item(0.5, portions), 0.5)).toBe(false);
    expect(canStepUp(item(1.2, portions), 0.1)).toBe(true);
    expect(canStepUp(item(1.4, portions), 0.2)).toBe(false);
  });
});
