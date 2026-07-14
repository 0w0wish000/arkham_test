// ════════════════════════════════════════════════════════════════════
//  Arkham 端到端測試協調器(跨平台:Windows / macOS / Linux)
//  一鍵完成:
//    1) 建置並啟動 Java 伺服器(:8080),等到「WebSocket 真的能握手」才算就緒
//    2) 跑協定 / 遊戲流程 e2e(兩客戶端,protocol-e2e.mjs)
//    3) 前端型別檢查 + 打包(npm run build = tsc --noEmit && vite build)
//    4) 可靠收掉伺服器(等埠真的釋放,必要時硬殺),彙整結果
//
//  用法:  node e2e/run.mjs
//  環境變數:
//    E2E_NO_SERVER=1    跳過啟動/關閉伺服器(沿用已在跑的 :8080)
//    E2E_SKIP_CLIENT=1  跳過前端建置(只跑協定 e2e,迭代較快)
//    E2E_SKIP_PROTOCOL=1 跳過協定 e2e(只做前端建置)
//
//  註:伺服器以 --no-daemon 啟動 —— 否則 Gradle daemon 會把 Spring JVM
//  fork 成 daemon 的子行程,關不掉、殘留佔用 8080(半死狀態:TCP 通、
//  但 WebSocket 握手失敗 1006),導致下一次測試沿用到壞掉的伺服器。
// ════════════════════════════════════════════════════════════════════
import { spawn, spawnSync } from "node:child_process";
import net from "node:net";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const isWin = process.platform === "win32";
const gradlew = isWin ? "gradlew.bat" : "./gradlew";
const npm = isWin ? "npm.cmd" : "npm";
const node = process.execPath;
const WS_URL = "ws://localhost:8080/ws/game";

const NO_SERVER = process.env.E2E_NO_SERVER === "1";
const SKIP_CLIENT = process.env.E2E_SKIP_CLIENT === "1";
const SKIP_PROTOCOL = process.env.E2E_SKIP_PROTOCOL === "1";

const log = (m) => console.log(m);
const hr = () => log("─".repeat(64));
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** TCP 連得上就 true(判斷「埠是否被佔用」用)。 */
function portOccupied(port = 8080, host = "127.0.0.1", timeoutMs = 700) {
  return new Promise((resolve) => {
    const sock = net.connect({ port, host });
    const done = (v) => { sock.destroy(); resolve(v); };
    sock.setTimeout(timeoutMs);
    sock.on("connect", () => done(true));
    sock.on("timeout", () => done(false));
    sock.on("error", () => done(false));
  });
}

/** 開一個真的 WebSocket 到 /ws/game,能 open 才算「伺服器真的活著」。 */
function wsHandshakeOk(timeoutMs = 1500) {
  return new Promise((resolve) => {
    let ws, done = false;
    const finish = (ok) => { if (done) return; done = true; try { ws && ws.close(); } catch { /* */ } resolve(ok); };
    try { ws = new WebSocket(WS_URL); } catch { return finish(false); }
    const t = setTimeout(() => finish(false), timeoutMs);
    ws.addEventListener("open", () => { clearTimeout(t); finish(true); });
    ws.addEventListener("error", () => { clearTimeout(t); finish(false); });
    ws.addEventListener("close", () => { clearTimeout(t); finish(false); });
  });
}

/** 反覆做 WS 握手直到成功或逾時。這是唯一可信的「就緒」判準。 */
async function waitForWsReady(timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await wsHandshakeOk()) return true;
    await sleep(1000);
  }
  return false;
}

/** 硬殺佔用 8080 的行程(跨平台;收殘留 / 半死伺服器用)。 */
function killPort8080() {
  try {
    if (isWin) {
      const out = spawnSync("cmd", ["/c", "netstat -ano | findstr :8080"], { encoding: "utf8" }).stdout || "";
      const pids = new Set(out.split(/\r?\n/)
        .filter((l) => /LISTENING|ESTABLISHED/.test(l))
        .map((l) => l.trim().split(/\s+/).pop())
        .filter((p) => /^\d+$/.test(p)));
      for (const pid of pids) spawnSync("taskkill", ["/F", "/T", "/PID", pid]);
    } else {
      const out = spawnSync("bash", ["-lc", "lsof -ti tcp:8080 2>/dev/null || true"], { encoding: "utf8" }).stdout || "";
      const pids = out.split(/\s+/).filter(Boolean);
      if (pids.length) spawnSync("kill", ["-9", ...pids]);
    }
  } catch { /* best-effort */ }
}

let server = null;

async function startServer() {
  hr(); log("▶ 1/3  建置並啟動 Java 伺服器(:8080)…");

  // 已有健康的伺服器 → 直接沿用
  if (await wsHandshakeOk(900)) {
    log("  (偵測到 8080 有健康的伺服器 —— 直接沿用。)");
    return "reused";
  }
  // 埠被佔用但握手失敗 = 半死/殘留伺服器 → 先清掉再重啟
  if (await portOccupied()) {
    log("  ⚠ 8080 被佔用但 WebSocket 握手失敗(殘留/半死伺服器),先清除…");
    killPort8080();
    await sleep(1500);
  }

  server = spawn(gradlew, [":server:bootRun", "--console=plain", "--no-daemon"], {
    cwd: ROOT,
    stdio: ["ignore", "inherit", "inherit"],
    shell: isWin,
    detached: !isWin, // POSIX:自成行程群組,連 fork 出的 JVM 一起收掉
  });
  server.on("error", (e) => console.error(`啟動伺服器失敗:${e.message}`));

  log("  等待就緒(以真實 WebSocket 握手判定;首次會下載 Gradle/JDK/相依,最長 4 分鐘)…");
  const ready = await waitForWsReady(240000);
  if (!ready) { console.error("  ✗ 伺服器逾時未就緒。"); await stopServer(); process.exit(2); }
  log("  ✓ 伺服器已就緒(WebSocket 可握手)。");
  return "started";
}

async function stopServer() {
  if (server && !server.killed) {
    try {
      if (isWin) spawnSync("taskkill", ["/pid", String(server.pid), "/T", "/F"]);
      else process.kill(-server.pid, "SIGTERM"); // 收整個行程群組(含 bootRun 的 JVM)
    } catch { /* */ }
  }
  // 等埠真的釋放;還在就硬殺(避免殘留佔用 8080 害下次測試)
  for (let i = 0; i < 12; i++) {
    if (!(await portOccupied(8080, "127.0.0.1", 400))) return;
    await sleep(500);
  }
  killPort8080();
}

/** 跑一個子行程到結束,回傳 exit code。stdio 直接接到本行程。 */
function run(cmd, args, opts = {}) {
  return new Promise((resolve) => {
    const child = spawn(cmd, args, { cwd: ROOT, stdio: "inherit", shell: isWin, ...opts });
    child.on("exit", (code) => resolve(code ?? 1));
    child.on("error", (e) => { console.error(`啟動 ${cmd} 失敗:${e.message}`); resolve(1); });
  });
}

async function main() {
  let serverState = "reused";
  if (!NO_SERVER) serverState = await startServer();
  else log("(E2E_NO_SERVER=1:沿用已在跑的伺服器)");

  let protocolCode = 0, clientCode = 0;

  if (!SKIP_PROTOCOL) {
    hr(); log("▶ 2/3  協定 e2e:大廳交握 + 遊戲流程(兩客戶端)…");
    // node 是 .exe:不要用 shell(Windows 上 node.exe 路徑含空白會被 shell 拆開)。
    // gradlew.bat / npm.cmd 才需要 shell(見 run() 預設 shell:isWin)。
    const lobbyCode = await run(node, [path.join("e2e", "lobby-e2e.mjs"), "ws://localhost:8080"], { shell: false });
    const deckCode = await run(node, [path.join("e2e", "deckbuild-e2e.mjs"), "ws://localhost:8080"], { shell: false });
    const saveCode = await run(node, [path.join("e2e", "save-reload-e2e.mjs"), "ws://localhost:8080"], { shell: false });
    const rosterCode = await run(node, [path.join("e2e", "dynamic-roster-e2e.mjs"), "ws://localhost:8080"], { shell: false });
    const voteCode = await run(node, [path.join("e2e", "char-vote-e2e.mjs"), "ws://localhost:8080"], { shell: false });
    const sandboxCode = await run(node, [path.join("e2e", "sandbox-e2e.mjs"), "ws://localhost:8080"], { shell: false });
    const flowCode = await run(node, [path.join("e2e", "protocol-e2e.mjs"), "ws://localhost:8080"], { shell: false });
    protocolCode = lobbyCode || deckCode || saveCode || rosterCode || voteCode || sandboxCode || flowCode;   // 任一失敗即失敗
  } else log("(E2E_SKIP_PROTOCOL=1:跳過協定 e2e)");

  if (!SKIP_CLIENT) {
    hr(); log("▶ 3/3  前端型別檢查 + 打包(npm run build)…");
    const hasModules = spawnSync(isWin ? "cmd" : "test",
      isWin ? ["/c", "if exist client\\node_modules (exit 0) else (exit 1)"] : ["-d", "client/node_modules"],
      { cwd: ROOT }).status === 0;
    if (!hasModules) {
      log("  首次安裝前端相依(npm install)…");
      const inst = await run(npm, ["install"], { cwd: path.join(ROOT, "client") });
      if (inst !== 0) { console.error("  ✗ npm install 失敗。"); clientCode = inst; }
    }
    if (clientCode === 0) clientCode = await run(npm, ["run", "build"], { cwd: path.join(ROOT, "client") });
  } else log("(E2E_SKIP_CLIENT=1:跳過前端建置)");

  if (serverState === "started") { hr(); log("▶ 收掉伺服器…"); await stopServer(); }

  hr();
  const pOK = SKIP_PROTOCOL || protocolCode === 0;
  const cOK = SKIP_CLIENT || clientCode === 0;
  log(`結果:協定 e2e = ${SKIP_PROTOCOL ? "略過" : pOK ? "✓ 通過" : "✗ 失敗"}` +
      `  ·  前端建置 = ${SKIP_CLIENT ? "略過" : cOK ? "✓ 通過" : "✗ 失敗"}`);
  process.exit(pOK && cOK ? 0 : 1);
}

process.on("SIGINT", async () => { await stopServer(); process.exit(130); });
process.on("SIGTERM", async () => { await stopServer(); process.exit(143); });
main().catch(async (e) => { console.error(e); await stopServer(); process.exit(1); });
