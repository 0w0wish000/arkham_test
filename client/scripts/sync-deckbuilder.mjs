// 選卡器單一來源:prototype/deckbuilder.html 為準,dev/build 前同步到 public/。
// (public/deckbuilder.html 不入 git;見根目錄 .gitignore)
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
const here = dirname(fileURLToPath(import.meta.url));
const src = resolve(here, "../../prototype/deckbuilder.html");
const dst = resolve(here, "../public/deckbuilder.html");
mkdirSync(dirname(dst), { recursive: true });
copyFileSync(src, dst);
console.log("[sync] prototype/deckbuilder.html → client/public/");
