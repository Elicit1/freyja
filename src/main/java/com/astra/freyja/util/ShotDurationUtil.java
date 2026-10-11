package com.astra.freyja.util;

/** Shared fallback for decomposition previews, statistics and persistence. */
public final class ShotDurationUtil {
    public static final double DEFAULT_SECONDS = 3.0;

    private ShotDurationUtil() {}

    public static double resolve(Double seconds) {
        return seconds != null && Double.isFinite(seconds) && seconds > 0
                ? seconds : DEFAULT_SECONDS;
    }
}
