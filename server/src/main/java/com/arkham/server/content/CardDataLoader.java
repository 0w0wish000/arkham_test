package com.arkham.server.content;

import com.arkham.engine.model.SkillIcon;
import com.arkham.engine.scenario.CardCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * G1(docs/11):啟動時把 content/cards/generated/(build_cards.py 產出的真卡資料)
 * 灌進引擎 {@link CardCatalog} 的外部登記層 —— 玩家用選卡器建的任何官方卡,
 * 進對局都有正確的型別/費用/技能圖示(可投入檢定、可打出)。
 * 資料不存在(新 clone 未跑 setup-content)→ 略過,退回內建目錄,不擋啟動。
 */
@Component
public class CardDataLoader implements CommandLineRunner {

    private final ObjectMapper mapper;

    public CardDataLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) {
        // bootRun 的 cwd 是 server/ → 先試 ../content;直接從根執行則是 ./content
        Path dir = Stream.of(Path.of("../content/cards/generated"), Path.of("content/cards/generated"))
                .filter(Files::isDirectory).findFirst().orElse(null);
        if (dir == null) {
            System.out.println("[content] 找不到 content/cards/generated/ —— 用內建卡目錄(可跑 setup-content 載入完整卡庫)");
            return;
        }
        int loaded = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode cards = mapper.readTree(Files.readString(f));
                for (JsonNode c : cards) {
                    String type = c.path("type").asText("");
                    if (!type.equals("asset") && !type.equals("event") && !type.equals("skill")) {
                        continue;
                    }
                    List<SkillIcon> icons = new ArrayList<>();
                    for (JsonNode i : c.path("skillIcons")) {
                        try { icons.add(SkillIcon.valueOf(i.asText())); } catch (IllegalArgumentException ignored) { }
                    }
                    CardCatalog.register(c.path("name").asText(), type, c.path("cost").asInt(0), icons);
                    loaded++;
                }
            }
        } catch (Exception ex) {
            System.out.println("[content] 卡資料載入失敗(" + ex.getMessage() + ")—— 用內建卡目錄");
            return;
        }
        System.out.println("[content] 已載入真卡資料 " + loaded + " 筆(目錄卡名 "
                + CardCatalog.externalCount() + " 種)→ 引擎 CardCatalog");
    }
}
