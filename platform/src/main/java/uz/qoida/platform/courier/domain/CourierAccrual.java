package uz.qoida.platform.courier.domain;

/**
 * What one delivery, or one closed shift, earned (ADR 0042).
 *
 * <p>Every figure is integer minor units. For UZS a minor unit is a whole som:
 * a formatter that asks ISO 4217 for the decimal places divides by a hundred and
 * shows a courier a hundredth of what he is owed.
 */
public record CourierAccrual(
        long fixedMinor,
        long perOrderMinor,
        long perKmMinor,
        long minimumTopUpMinor) {

    public static final CourierAccrual NOTHING = new CourierAccrual(0, 0, 0, 0);

    public CourierAccrual {
        if (fixedMinor < 0 || perOrderMinor < 0 || perKmMinor < 0 || minimumTopUpMinor < 0) {
            throw new IllegalArgumentException("An accrual component is never negative");
        }
    }

    public long totalMinor() {
        return fixedMinor + perOrderMinor + perKmMinor + minimumTopUpMinor;
    }
}
