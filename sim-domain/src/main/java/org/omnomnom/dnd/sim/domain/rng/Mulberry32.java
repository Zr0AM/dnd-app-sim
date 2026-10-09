package org.omnomnom.dnd.sim.domain.rng;

/** mulberry32: a tiny, well-distributed 32-bit PRNG. Same seed, same sequence. Not thread-safe. */
final class Mulberry32 implements Rng {

    private int state;

    Mulberry32(long seed) {
        this.state = (int) seed;
    }

    @Override
    public double next() {
        state += 0x6D2B79F5;
        int t = (state ^ (state >>> 15)) * (1 | state);
        t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
        return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }
}
