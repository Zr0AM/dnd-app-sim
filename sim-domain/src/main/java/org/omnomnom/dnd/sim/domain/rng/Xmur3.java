package org.omnomnom.dnd.sim.domain.rng;

/**
 * xmur3: hashes a string into a sequence of 32-bit seeds (paired with mulberry32). Bit-exact port of the
 * TypeScript implementation; all arithmetic is 32-bit wrapping {@code int}, and {@code charAt} yields the
 * same UTF-16 code units as JavaScript's {@code charCodeAt}.
 */
final class Xmur3 {

    private int h;

    Xmur3(String str) {
        h = 0x6A09E667 ^ str.length(); // 1779033703
        for (int i = 0; i < str.length(); i++) {
            h = (h ^ str.charAt(i)) * 0xCC9E2D51; // 3432918353
            h = Integer.rotateLeft(h, 13);
        }
    }

    /** The next hash as an unsigned 32-bit value. */
    long next() {
        h = (h ^ (h >>> 16)) * 0x85EBCA6B; // 2246822507
        h = (h ^ (h >>> 13)) * 0xC2B2AE35; // 3266489909
        h ^= h >>> 16;
        return h & 0xFFFFFFFFL;
    }
}
