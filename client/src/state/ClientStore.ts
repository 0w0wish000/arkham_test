import type { GameStateView } from "../protocol";

/**
 * 客戶端狀態:只保存伺服器下發的「過濾後視圖」。
 * 不含任何規則邏輯 —— 規則判定全在 Java 引擎。
 */
export class ClientStore {
  view?: GameStateView;
  private subs: Array<(v: GameStateView) => void> = [];

  set(view: GameStateView) {
    this.view = view;
    for (const fn of this.subs) fn(view);
  }
  subscribe(fn: (v: GameStateView) => void) {
    this.subs.push(fn);
    if (this.view) fn(this.view);
  }
}
