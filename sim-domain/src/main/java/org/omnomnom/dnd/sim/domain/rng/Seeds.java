package org.omnomnom.dnd.sim.domain.rng;

import java.security.SecureRandom;

/** Seed derivation helpers. All seeds are unsigned 32-bit values carried in a {@code long}. */
public final class Seeds {

    /** Largest valid seed (unsigned 32-bit). */
    public static final long MAX_SEED = 0xFFFFFFFFL;

    private static final SecureRandom SECURE = new SecureRandom();

    private Seeds() {}

    /**
     * Derives a 32-bit child seed from a root seed and a label, order-independently. The root seed is
     * folded into the label hash so the same label under different roots yields unrelated streams.
     */
    public static long deriveSeed(long rootSeed, String label) {
        Xmur3 hash = new Xmur3(label);
        int a = (int) hash.next();
        int b = (int) hash.next();
        int s = a ^ ((int) rootSeed * 0x9E3779B1) ^ (b >>> 1); // 2654435761
        s = (s ^ (s >>> 15)) * 0x85EBCA6B;
        return s & 0xFFFFFFFFL;
    }

    /**
     * A stable 32-bit seed from parts, for example {@code seedFrom("l3-pair-goblins", 4)}. Parts are joined
     * with {@code '|'}; only strings and integral numbers are accepted so the text matches the TypeScript
     * sim's {@code parts.join('|')}.
     */
    public static long seedFrom(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            Object p = parts[i];
            if (p instanceof String || p instanceof Integer || p instanceof Long || p instanceof Short
                    || p instanceof Byte) {
                sb.append(p);
            } else {
                throw new IllegalArgumentException("seed part must be a String or integral number, got: " + p);
            }
        }
        return deriveSeed(0, sb.toString());
    }

    /** A cryptographically-sourced unsigned 32-bit seed, for non-reproducible runs. */
    public static long randomSeed() {
        return SECURE.nextInt() & MAX_SEED;
    }
}
