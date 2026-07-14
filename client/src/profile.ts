/**
 * 玩家身分(docs/09 §2)。存在本機 localStorage:
 *   playerId  —— 「人」的永久識別(UUID),首次產生後不變。
 *   displayName —— 顯示名稱,可改。
 * server 用 playerId 判斷此人在哪些存檔有記錄;displayName 只是給人看的。
 */
export interface Profile {
  playerId: string;
  displayName: string;
}

const KEY = "arkham_profile";

function uuid(): string {
  // crypto.randomUUID 在現代瀏覽器都有;保底用時間+亂數(僅本機識別用途)。
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return "p-" + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

export function loadProfile(): Profile | null {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const p = JSON.parse(raw) as Profile;
    return p.playerId && p.displayName ? p : null;
  } catch {
    return null;
  }
}

/** 首次建立身分(輸入名稱時呼叫);產生固定 playerId。 */
export function createProfile(displayName: string): Profile {
  const p: Profile = { playerId: uuid(), displayName: displayName.trim() };
  localStorage.setItem(KEY, JSON.stringify(p));
  return p;
}

/** 改名:playerId 不變,記錄不斷。 */
export function renameProfile(displayName: string): Profile {
  const cur = loadProfile();
  const p: Profile = { playerId: cur?.playerId ?? uuid(), displayName: displayName.trim() };
  localStorage.setItem(KEY, JSON.stringify(p));
  return p;
}
