package demo;

public final class ShippingCalculatorCheck {
    public static void main(String[] args) {
        check(4999, 500);
        check(5000, 0);
        check(5001, 0);
    }

    private static void check(int subtotal, int expected) {
        int actual = ShippingCalculator.shippingCents(subtotal);
        if (actual != expected) throw new AssertionError("subtotal=" + subtotal + ": expected " + expected + ", got " + actual);
    }
}
