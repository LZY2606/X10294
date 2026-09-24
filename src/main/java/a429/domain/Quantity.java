package a429.domain;

/** Every engineering result carries its unit / coordinate basis. */
public record Quantity(double value, String unit, String basis, boolean valid, String diagnostic) {

    public static Quantity of(double value, String unit, String basis) {
        return new Quantity(value, unit, basis, true, null);
    }

    public static Quantity invalid(String unit, String basis, String diagnostic) {
        return new Quantity(Double.NaN, unit, basis, false, diagnostic);
    }
}
