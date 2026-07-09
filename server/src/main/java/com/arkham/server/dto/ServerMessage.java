package com.arkham.server.dto;

import com.arkham.engine.model.ChoiceKind;
import com.arkham.engine.protocol.ChoiceOptions;
import com.arkham.engine.view.GameStateView;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Server → Client messages. Mirrors {@code ServerMessage} in protocol/messages.ts.
 * Serialising a variant writes {@code "type": "<NAME>"} plus that variant's fields
 * (e.g. STATE ⇒ {@code { type:"STATE", view:{…} }}). The engine's view / choice-option
 * records are reused directly as the payloads.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ServerMessage.State.class, name = "STATE"),
        @JsonSubTypes.Type(value = ServerMessage.Event.class, name = "EVENT"),
        @JsonSubTypes.Type(value = ServerMessage.ChoiceRequest.class, name = "CHOICE_REQUEST"),
        @JsonSubTypes.Type(value = ServerMessage.Error.class, name = "ERROR"),
        @JsonSubTypes.Type(value = ServerMessage.Pong.class, name = "PONG")
})
public sealed interface ServerMessage
        permits ServerMessage.State, ServerMessage.Event, ServerMessage.ChoiceRequest,
                ServerMessage.Error, ServerMessage.Pong {

    /** {@code { type:"STATE", view }} — the recipient's filtered snapshot. */
    record State(GameStateView view) implements ServerMessage {}

    /** {@code { type:"EVENT", event, message }} — narration / animation event. */
    record Event(String event, String message) implements ServerMessage {}

    /** {@code { type:"CHOICE_REQUEST", requestId, kind, options }} */
    record ChoiceRequest(String requestId, ChoiceKind kind, ChoiceOptions options) implements ServerMessage {}

    /** {@code { type:"ERROR", message }} */
    record Error(String message) implements ServerMessage {}

    /** {@code { type:"PONG" }} */
    record Pong() implements ServerMessage {}
}
