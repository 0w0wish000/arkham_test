package com.arkham.engine.protocol;

import java.util.List;

/**
 * Options for a CHOOSE_TARGET choice. Mirrors {@code ChooseTargetOptions} in
 * protocol/messages.ts. Not produced by this lite scaffold, but present so the
 * contract is complete.
 */
public record ChooseTargetOptions(
        List<Candidate> candidates,
        int min,
        int max) implements ChoiceOptions {

    /** A selectable target: {@code { id, label }}. */
    public record Candidate(String id, String label) {}
}
