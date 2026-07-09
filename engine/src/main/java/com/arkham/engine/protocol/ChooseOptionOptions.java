package com.arkham.engine.protocol;

import java.util.List;

/**
 * Options for a CHOOSE_OPTION choice. Mirrors {@code ChooseOptionOptions} in
 * protocol/messages.ts. Not produced by this lite scaffold, but present so the
 * contract is complete.
 */
public record ChooseOptionOptions(
        String prompt,
        List<Option> options) implements ChoiceOptions {

    /** A selectable option: {@code { id, label }}. */
    public record Option(String id, String label) {}
}
