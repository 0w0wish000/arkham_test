package com.arkham.engine.rng;

import java.util.List;
import java.util.Random;

/**
 * The single, deterministic random source for the whole engine (docs/05 §2:
 * "RNG must be server-side and seedable, the whole game's randomness centralised").
 *
 * <p>Wraps {@link java.util.Random}, whose algorithm is specified by the JDK, so the
 * same seed yields the same sequence on every JVM — enabling reproducible tests and
 * game replay. All chaos-bag draws (and any future shuffles) must go through here.
 */
public final class SeededRng {

    private final long seed;
    private final Random random;

    public SeededRng(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    /** The seed this generator was created with (for logging / replay). */
    public long seed() {
        return seed;
    }

    /** Uniform int in {@code [0, bound)}. */
    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    /** In-place Fisher-Yates shuffle (decks, encounter deck) — same seed ⇒ same order. */
    public <T> void shuffle(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}
