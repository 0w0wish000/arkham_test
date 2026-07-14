package com.arkham.engine;

import com.arkham.engine.model.GameState;
import com.arkham.engine.scenario.ScenarioFactory;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 驗證整包 {@link GameState} 能序列化 → 反序列化(存檔/續玩的地基,docs/08)。
 * 用「欄位可見 + 建構子參數名」的 mapper,免逐類別加 @JsonCreator。
 */
class SaveRoundTripTest {

    static ObjectMapper saveMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new ParameterNamesModule(com.fasterxml.jackson.annotation.JsonCreator.Mode.PROPERTIES));
        m.setVisibility(m.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(Visibility.ANY)
                .withGetterVisibility(Visibility.NONE)
                .withIsGetterVisibility(Visibility.NONE)
                .withSetterVisibility(Visibility.NONE)
                .withCreatorVisibility(Visibility.ANY));
        m.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return m;
    }

    @Test
    void gameStateRoundTrips() throws Exception {
        ObjectMapper m = saveMapper();
        GameState state = ScenarioFactory.createState();
        String json = m.writeValueAsString(state);
        GameState back = m.readValue(json, GameState.class);

        assertEquals(state.getRound(), back.getRound());
        assertEquals(state.getPhase(), back.getPhase());
        assertEquals(state.getLocations().size(), back.getLocations().size(), "locations");
        assertEquals(state.getInvestigators().size(), back.getInvestigators().size(), "investigators");
        assertEquals(state.getChaosBag().size(), back.getChaosBag().size(), "chaos bag");
        assertEquals(state.getEncounterDeck().size(), back.getEncounterDeck().size(), "encounter deck");
        assertEquals(state.getAct().getName(), back.getAct().getName(), "act");
        assertEquals(state.getAgenda().getThreshold(), back.getAgenda().getThreshold(), "agenda");
    }
}
