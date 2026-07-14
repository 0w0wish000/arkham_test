package com.arkham.engine.ability;

/**
 * 能力視窗的時機點(docs/11 §B1)。每個遊戲動作在關鍵節點 emit 一個時機,
 * 讓登記在該時機的能力(強制/反應)有機會結算 —— 對應官方「ability window」。
 * 原型先開垂直切片需要的幾個;之後隨卡池擴充。
 */
public enum Timing {
    /** 你成功調查之後(官方 Joe Diamond:After you successfully investigate)。 */
    AFTER_INVESTIGATE_SUCCESS,
    /** 敵人的攻擊命中你之後(官方 Daniela Reyes)。 */
    AFTER_ENEMY_ATTACKS_YOU,
    /** 你擊敗一個敵人之後。 */
    AFTER_YOU_DEFEAT_ENEMY,
    /** 技能檢定:投入完成、抽混沌標記之前(官方 p16 第二個玩家窗口;B7)。 */
    SKILL_TEST_BEFORE_REVEAL
}
