package org.omnomnom.dnd.sim.domain.rng;

import java.util.HashMap;
import java.util.Map;

/**
 * A tree of independent random streams rooted at one seed (the TypeScript {@code Random}).
 *
 * <p>The simulator needs common random numbers: two builds in the same scenario must face identical enemy
 * rolls. A single draw-order generator cannot do that, because one extra spell shifts every later draw. Here
 * each label owns a generator seeded only from {@code (rootSeed, label)}, so a label's values do not depend
 * on whether, or how much, any other label was drawn. Within a label draws are sequential.
 *
 * <p>Instances cache stream generators and are <strong>not thread-safe</strong>. Create one per simulated run;
 * never share one across runs or threads.
 */
public final class LabeledRandom {

    private final long rootSeed;
    private final String prefix;
    private final Map<String, Rng> streams = new HashMap<>();

    public LabeledRandom(long rootSeed) {
        this(rootSeed, "");
    }

    private LabeledRandom(long rootSeed, String prefix) {
        this.rootSeed = rootSeed & Seeds.MAX_SEED;
        this.prefix = prefix;
    }

    private String qualify(String label) {
        return prefix.isEmpty() ? label : prefix + "/" + label;
    }

    /** The generator for {@code label}, created on first use and continued on later calls. */
    public Rng stream(String label) {
        return streams.computeIfAbsent(qualify(label), key -> new Mulberry32(Seeds.deriveSeed(rootSeed, key)));
    }

    /** The next double in [0, 1) from {@code label}'s stream. */
    public double next(String label) {
        return stream(label).next();
    }

    /**
     * A namespaced sub-tree sharing the same root seed. {@code root.child("enemy:goblin-1").next("attack")} and
     * {@code root.next("enemy:goblin-1/attack")} draw from the same stream.
     */
    public LabeledRandom child(String label) {
        return new LabeledRandom(rootSeed, qualify(label));
    }
}
