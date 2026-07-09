package com.arkham.engine.protocol;

/**
 * The payload of a {@code CHOICE_REQUEST}. Mirrors the {@code ChoiceOptions} union in
 * protocol/messages.ts. Deliberately <em>untagged</em>: the client disambiguates via
 * the request's {@code kind} field, so each variant serialises to just its own fields.
 */
public sealed interface ChoiceOptions
        permits CommitCardsOptions, ChooseTargetOptions, ChooseOptionOptions {}
