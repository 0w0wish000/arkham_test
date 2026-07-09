package com.arkham.server.dto;

import com.arkham.engine.model.IntentAction;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

/**
 * Client → Server messages. Mirrors {@code ClientMessage} in protocol/messages.ts.
 * The {@code "type"} field selects the concrete record; Jackson reads it as the type
 * discriminator (it is not bound to a record component).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ClientMessage.Join.class, name = "JOIN"),
        @JsonSubTypes.Type(value = ClientMessage.Intent.class, name = "INTENT"),
        @JsonSubTypes.Type(value = ClientMessage.ChoiceResponse.class, name = "CHOICE_RESPONSE"),
        @JsonSubTypes.Type(value = ClientMessage.Ping.class, name = "PING")
})
public sealed interface ClientMessage
        permits ClientMessage.Join, ClientMessage.Intent, ClientMessage.ChoiceResponse, ClientMessage.Ping {

    /** {@code { type:"JOIN", sessionId, investigatorId }} */
    record Join(String sessionId, String investigatorId) implements ClientMessage {}

    /** {@code { type:"INTENT", action, payload? }} */
    record Intent(IntentAction action, Map<String, Object> payload) implements ClientMessage {}

    /** {@code { type:"CHOICE_RESPONSE", requestId, choice }} */
    record ChoiceResponse(String requestId, ChoiceResponseBody choice) implements ClientMessage {}

    /** {@code { type:"PING" }} */
    record Ping() implements ClientMessage {}

    /**
     * The {@code choice} union in protocol/messages.ts. All fields optional; only the
     * one matching the request's kind is populated (COMMIT_CARDS ⇒ committedCardIds).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChoiceResponseBody(List<String> committedCardIds, List<String> targetIds, String optionId) {}
}
