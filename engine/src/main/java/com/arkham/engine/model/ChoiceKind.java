package com.arkham.engine.model;

/**
 * Kinds of decision the server can ask a client to make. Mirrors {@code ChoiceKind}
 * in protocol/messages.ts. COMMIT_CARDS is the multi-client "commit barrier"
 * (docs/05 §2.1).
 */
public enum ChoiceKind {
    COMMIT_CARDS,
    CHOOSE_TARGET,
    CHOOSE_OPTION
}
