package com.astra.freyja.util;

import java.util.concurrent.ThreadLocalRandom;

/** Video seeds stay within JavaScript's exact integer range for gateway compatibility. */
public final class ShotSeedUtil {

    public static final long MAX_SEED = 9_007_199_254_740_991L;

    private ShotSeedUtil() {
    }

    public static long next() {
        return ThreadLocalRandom.current().nextLong(1, MAX_SEED + 1);
    }

    public static long nextDifferent(Long previous) {
        long seed;
        do {
            seed = next();
        } while (previous != null && seed == previous);
        return seed;
    }
}
