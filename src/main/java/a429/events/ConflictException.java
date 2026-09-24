package a429.events;

/** Optimistic-lock conflict: the submitting client held an older revision. */
public class ConflictException extends RuntimeException {

    private final int expected;
    private final int actual;
    private final String eventType;

    public ConflictException(String eventType, int expected, int actual) {
        super("version conflict on " + eventType + ": expected revision " + expected
                + ", head is " + actual);
        this.eventType = eventType;
        this.expected = expected;
        this.actual = actual;
    }

    public int expected() {
        return expected;
    }

    public int actual() {
        return actual;
    }

    public String eventType() {
        return eventType;
    }
}
