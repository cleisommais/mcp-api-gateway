package demo;

public final class ShippingCalculator {
    private ShippingCalculator() {}

    public static int shippingFeeInCents(int subtotalInCents) {
        if (subtotalInCents < 0) throw new IllegalArgumentException("Negative subtotal");
        return subtotalInCents >= 5000 ? 0 : 500;
    }
}
