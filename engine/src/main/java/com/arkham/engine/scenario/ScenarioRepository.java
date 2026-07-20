package com.arkham.engine.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2 場景資料載入器:自 classpath {@code /scenarios/<key>.json} 讀
 * {@link ScenarioData}(快取)。查無該鍵 → empty,由呼叫端決定後備
 * (ScenarioFactory 以 {@code core} 為預設劇本)。
 */
public final class ScenarioRepository {

    private ScenarioRepository() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, Optional<ScenarioData>> CACHE = new ConcurrentHashMap<>();

    public static Optional<ScenarioData> find(String key) {
        if (key == null || key.isBlank() || !key.matches("[a-z0-9_\\-]+")) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(key, ScenarioRepository::load);
    }

    private static Optional<ScenarioData> load(String key) {
        try (InputStream in = ScenarioRepository.class.getResourceAsStream("/scenarios/" + key + ".json")) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(MAPPER.readValue(in, ScenarioData.class));
        } catch (Exception e) {
            return Optional.empty();   // 壞資料視同不存在(後備到 core)
        }
    }
}
