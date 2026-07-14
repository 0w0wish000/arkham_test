import type { CampaignSave } from "./protocol";

/**
 * 本機存檔庫(docs/09 §7):伺服器每次 CAMPAIGN_SNAPSHOT 就把整份戰役存檔複製到這裡,
 * 主選單「加載存檔」據此列出、可 OFFER_SAVE 載回。以 campaignId 為 key(同戰役覆蓋)。
 */
const KEY = "arkham_saves";

function readAll(): Record<string, CampaignSave> {
  try { return JSON.parse(localStorage.getItem(KEY) || "{}") as Record<string, CampaignSave>; }
  catch { return {}; }
}

export function storeSave(save: CampaignSave) {
  const all = readAll();
  all[save.campaignId] = save;
  localStorage.setItem(KEY, JSON.stringify(all));
}

export function listSaves(): CampaignSave[] {
  return Object.values(readAll()).sort((a, b) => a.name.localeCompare(b.name));
}
