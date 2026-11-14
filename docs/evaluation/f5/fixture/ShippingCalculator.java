package demo;

public final class ShippingCalculator {
    private ShippingCalculator() {}

    /** Orders of at least 5,000 cents qualify for free shipping. */
    public static int shippingCents(int subtotalCents) {
        if (subtotalCents >= 5000) {
            return 0;
        }
        return 500;
    }
}
