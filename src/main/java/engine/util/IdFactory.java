package engine.util;

public class IdFactory {
    private static long currId = 1L;

    public static long getNextId() {
        return currId++;
    }

}
