package com.arkham.engine.ability;

import com.arkham.engine.RulesEngine;
import com.arkham.engine.event.GameEvent;

import java.util.List;

/**
 * 能力/時機引擎原型(docs/11 §B 垂直切片)。
 * 一條已登記的能力 = 「在某個時機點(Timing),對其擁有者觸發的效果」。
 *
 * <p>型別對應官方規則書 p45 / p41:
 * <ul>
 *   <li>{@link Type#FORCED} 強制 —— 時機一到必定結算(優先於反應,p41)。</li>
 *   <li>{@link Type#REACTION} 反應(↺)—— 永遠是<b>可選</b>觸發(p33):引擎暫停,
 *       問擁有者「要不要用?」(CHOOSE_OPTION 決策)。</li>
 * </ul>
 * 常駐(constant)修正暫仍以欄位加值表達(如武器/放大鏡),完整修正層見 §B5。
 *
 * @param id            穩定識別(每回合限一次的記帳用)
 * @param ownerId       擁有者調查員 id
 * @param sourceName    來源卡/調查員名(播報用)
 * @param timing        觸發時機點
 * @param type          FORCED / REACTION
 * @param oncePerRound  每回合限一次(官方 "Limit once per round.")
 * @param prompt        REACTION 詢問文字(FORCED 不用)
 * @param effect        效果本體
 */
public record Ability(
        String id,
        String ownerId,
        String sourceName,
        Timing timing,
        Type type,
        boolean oncePerRound,
        String prompt,
        Effect effect) {

    public enum Type { FORCED, REACTION }

    /** 觸發當下的脈絡(誰觸發、涉及哪個敵人)。 */
    public record Ctx(String investigatorId, String enemyId) {
        public static Ctx of(String investigatorId) { return new Ctx(investigatorId, null); }
    }

    /** 效果:拿到引擎與觸發脈絡,把播報事件寫進 events。 */
    @FunctionalInterface
    public interface Effect {
        void resolve(RulesEngine engine, Ctx ctx, List<GameEvent> events);
    }
}
