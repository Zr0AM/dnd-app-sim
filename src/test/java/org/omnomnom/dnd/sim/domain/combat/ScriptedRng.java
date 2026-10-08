package org.omnomnom.dnd.sim.domain.combat;

import org.omnomnom.dnd.sim.domain.rng.Rng;

/** A controllable stream that yields exact d20 faces, cycling through {@code faces}. */
final class ScriptedRng implements Rng {

    private final int[] faces;
    private int i;

    private ScriptedRng(int... faces) {
        this.faces = faces;
    }

    static Rng faces(int... faces) {
        return new ScriptedRng(faces);
    }

    @Override
    public double next() {
        // rollDie maps next()*sides to a face; (f-1)/20 + tiny forces face f on a d20.
        int f = faces[i++ % faces.length];
        return (f - 1) / 20.0 + 1e-9;
    }
}
