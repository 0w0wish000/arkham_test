package com.arkham.engine.scenario;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F1-lite 牌組驗證(docs/11 §F)—— 只驗「與卡池資料無關」的官方構築規則:
 * 同名最多 2 張(官方 p16)、總張數防灌爆上限。
 *
 * <p>刻意不驗(待卡牌資料定案後的 F1 完整版/F2/F3):精確牌組張數(各調查員
 * 不同,如核心 30)、職業/等級存取、招牌與弱點強制 —— 這些都需要資料。
 * 未知卡名不擋(內容管線未跑的環境仍可玩),由呼叫端以警告提示。
 */
public final class DeckRules {

    private DeckRules() {}

    /** 防灌爆:再大的正式牌組也遠低於此(核心 30 + 弱點/招牌)。 */
    public static final int MAX_DECK_SIZE = 60;

    /** 同名上限(官方 p16:同名卡合計最多 2 張)。 */
    public static final int MAX_COPIES = 2;

    /** 驗證一份牌組(卡名清單)。合法 → null;違規 → 中文原因(第一個)。 */
    public static String validate(List<String> deck) {
        if (deck == null || deck.isEmpty()) {
            return null;   // 未提交 → 引擎用預設牌組;沙盒也允許空
        }
        if (deck.size() > MAX_DECK_SIZE) {
            return "牌組超過 " + MAX_DECK_SIZE + " 張上限(目前 " + deck.size() + " 張)。";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String name : deck) {
            int n = counts.merge(name, 1, Integer::sum);
            if (n > MAX_COPIES) {
                return "同名卡最多 " + MAX_COPIES + " 張:「" + name + "」放了第 " + n + " 張。";
            }
        }
        return null;
    }

    /** 目錄查無的卡名(去重、保序)—— 不擋牌組,供呼叫端警告。 */
    public static List<String> unknownCards(List<String> deck) {
        List<String> unknown = new ArrayList<>();
        if (deck == null) {
            return unknown;
        }
        for (String name : deck) {
            if (!CardCatalog.isKnown(name) && !unknown.contains(name)) {
                unknown.add(name);
            }
        }
        return unknown;
    }
}
