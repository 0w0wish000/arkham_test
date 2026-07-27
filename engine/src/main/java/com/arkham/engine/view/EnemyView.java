package com.arkham.engine.view;

import com.arkham.engine.model.Keyword;

import java.util.List;

/**
 * An enemy as sent to clients. Mirrors {@code EnemyView} in protocol/messages.ts.
 * {@code engagedWith} is the engaged investigator id, or {@code null} if unengaged
 * (serialises to JSON {@code null}).
 */
public record EnemyView(
        String id,
        String name,
        int fight,
        int health,
        int damageOn,
        int evade,
        int damage,        // 攻擊造成的傷害(HUD 顯示「攻 X傷/Y懼」)
        int horror,        // 攻擊造成的恐懼
        List<Keyword> keywords,
        String engagedWith,
        boolean exhausted,
        String locationId,
        String text) {}    // 卡片文字(真卡資料/翻譯;自製敵人為空)—— hover 詳細視窗用
