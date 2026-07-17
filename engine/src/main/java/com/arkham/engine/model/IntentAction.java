package com.arkham.engine.model;

/**
 * Player intents accepted by {@link com.arkham.engine.RulesEngine#applyIntent}.
 * Mirrors {@code IntentAction} in protocol/messages.ts(官方行動清單,規則書 p11)。
 *
 * <p>DRAW…RESIGN 為調查階段行動;MULLIGAN(開局一次調整)、END_TURN(我打完了/force)、
 * ADVANCE_ACT(花線索推進幕)不耗行動。趁隙攻擊豁免:FIGHT / EVADE / PARLEY / RESIGN(p38)。
 */
public enum IntentAction {
    DRAW,
    GAIN_RESOURCE,
    PLAY_CARD,
    ACTIVATE,
    MOVE,
    INVESTIGATE,
    ENGAGE,
    FIGHT,
    EVADE,
    PARLEY,
    RESIGN,
    MULLIGAN,
    END_TURN,
    ADVANCE_ACT
}
