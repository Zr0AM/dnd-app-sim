package org.omnomnom.dnd.sim.domain.opt;

import java.util.List;

/**
 * Statistics for evaluation: confidence intervals, so a result reports not just a mean but how tightly it is known.
 * Win rate is a proportion and uses a Wilson score interval (well behaved near 0 and 1); continuous means use a
 * normal-approximation interval on the sample standard error.
 */
public final class Stats {

    private Stats() {}

    /** z for a two-sided 95% interval. */
    public static final double Z_95 = 1.959964;

    /** z for a two-sided 90% interval. */
    public static final double Z_90 = 1.644854;

    public static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** Sample standard deviation (Bessel-corrected, n-1). */
    public static double sampleStdDev(List<Double> values) {
        int n = values.size();
        if (n < 2) {
            return 0;
        }
        double m = mean(values);
        double ss = 0;
        for (double v : values) {
            ss += (v - m) * (v - m);
        }
        return Math.sqrt(ss / (n - 1));
    }

    public static Interval wilsonInterval(int successes, int n) {
        return wilsonInterval(successes, n, Z_95);
    }

    /** Wilson score interval for a proportion, clamped to [0, 1]. */
    public static Interval wilsonInterval(int successes, int n, double z) {
        if (n == 0) {
            return new Interval(0, 0, 1, 0.5);
        }
        double p = (double) successes / n;
        double z2 = z * z;
        double denom = 1 + z2 / n;
        double center = (p + z2 / (2 * n)) / denom;
        double margin = (z * Math.sqrt((p * (1 - p)) / n + z2 / (4.0 * n * n))) / denom;
        double lo = Math.max(0, center - margin);
        double hi = Math.min(1, center + margin);
        return new Interval(p, lo, hi, (hi - lo) / 2);
    }

    public static Interval meanInterval(List<Double> values) {
        return meanInterval(values, Z_95);
    }

    /** Normal-approximation confidence interval for the mean of a sample. */
    public static Interval meanInterval(List<Double> values, double z) {
        int n = values.size();
        double m = mean(values);
        if (n < 2) {
            return new Interval(m, m, m, 0);
        }
        double se = sampleStdDev(values) / Math.sqrt(n);
        double half = z * se;
        return new Interval(m, m - half, m + half, half);
    }
}
