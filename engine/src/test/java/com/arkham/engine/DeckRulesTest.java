package com.arkham.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.arkham.engine.scenario.CardCatalog;
import com.arkham.engine.scenario.DeckRules;
import java.util.List;
import org.junit.jupiter.api.Test;

/** F1-lite 牌組驗證(docs/11 §F)—— 資料無關規則:同名 ≤2、張數上限、未知卡警告。 */
class DeckRulesTest {

    @Test
    void legalDecksPass() {
        assertNull(DeckRules.validate(null), "未提交 → 用預設牌組,不驗");
        assertNull(DeckRules.validate(List.of()), "空牌組(沙盒)合法");
        assertNull(DeckRules.validate(List.of(
                "Deduction", "Deduction", "Perception", "Emergency Cache", "Emergency Cache")),
                "同名各 2 張以內合法");
    }

    @Test
    void thirdCopyRejected() {
        String err = DeckRules.validate(List.of("Guts", "Deduction", "Guts", "Perception", "Guts"));
        assertTrue(err != null && err.contains("Guts") && err.contains("2"),
                "同名第 3 張要被擋且指名道姓:" + err);
    }

    @Test
    void oversizedDeckRejected() {
        List<String> huge = java.util.stream.IntStream.range(0, DeckRules.MAX_DECK_SIZE + 1)
                .mapToObj(i -> "卡" + i).toList();   // 61 張全不同名 → 只觸發張數上限
        String err = DeckRules.validate(huge);
        assertTrue(err != null && err.contains(String.valueOf(DeckRules.MAX_DECK_SIZE)), String.valueOf(err));
    }

    @Test
    void unknownCardsListedButNotBlocking() {
        assertTrue(CardCatalog.isKnown("Deduction"), "內建卡認得");
        List<String> deck = List.of("Deduction", "不存在的卡A", "不存在的卡A", "不存在的卡B");
        assertNull(DeckRules.validate(deck), "未知卡不擋牌組(同名 ≤2 仍要守)");
        assertEquals(List.of("不存在的卡A", "不存在的卡B"), DeckRules.unknownCards(deck), "未知卡去重保序列出");
    }
}
