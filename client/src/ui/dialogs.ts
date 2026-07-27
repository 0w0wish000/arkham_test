/**
 * 共用對話框(HTML <dialog>,取代原生 alert/confirm):
 * 原生對話框會「阻塞整個 JS 執行緒」—— 卡 WebSocket 訊息處理、卡動畫,也擋自動化測試;
 * 這裡用 showModal + Promise:非阻塞、主題化樣式、Enter=確認、Esc=取消。
 * 一次只有一個(新開會頂掉舊的)。
 */

let current: HTMLDialogElement | null = null;

function closeCurrent() {
  if (current) { current.close(); current.remove(); current = null; }
}

function build(title: string | undefined, message: string): HTMLDialogElement {
  closeCurrent();
  const d = document.createElement("dialog");
  d.className = "app-dialog";
  if (title) {
    const h = document.createElement("div");
    h.className = "dlg-title";
    h.textContent = title;
    d.appendChild(h);
  }
  const body = document.createElement("div");
  body.className = "dlg-body";
  body.textContent = message;
  d.appendChild(body);
  document.body.appendChild(d);
  current = d;
  return d;
}

function buttonRow(d: HTMLDialogElement): HTMLDivElement {
  const row = document.createElement("div");
  row.className = "dlg-actions";
  d.appendChild(row);
  return row;
}

/** 確認框:resolve(true)=確認、resolve(false)=取消(按鈕 / Esc / 點背景)。 */
export function confirmDialog(message: string, opts?: { title?: string; okText?: string; cancelText?: string }): Promise<boolean> {
  return new Promise((resolve) => {
    const d = build(opts?.title, message);
    const row = buttonRow(d);
    const cancel = document.createElement("button");
    cancel.textContent = opts?.cancelText ?? "取消";
    cancel.onclick = () => { closeCurrent(); resolve(false); };
    const ok = document.createElement("button");
    ok.className = "dlg-ok";
    ok.textContent = opts?.okText ?? "確認";
    ok.onclick = () => { closeCurrent(); resolve(true); };
    row.append(cancel, ok);
    d.addEventListener("cancel", (e) => { e.preventDefault(); closeCurrent(); resolve(false); });   // Esc
    d.addEventListener("click", (e) => { if (e.target === d) { closeCurrent(); resolve(false); } }); // 點背景
    d.showModal();
    ok.focus();
  });
}

/** 訊息框(取代 alert):resolve 於關閉。 */
export function infoDialog(message: string, opts?: { title?: string; okText?: string }): Promise<void> {
  return new Promise((resolve) => {
    const d = build(opts?.title, message);
    const row = buttonRow(d);
    const ok = document.createElement("button");
    ok.className = "dlg-ok";
    ok.textContent = opts?.okText ?? "知道了";
    ok.onclick = () => { closeCurrent(); resolve(); };
    row.append(ok);
    d.addEventListener("cancel", (e) => { e.preventDefault(); closeCurrent(); resolve(); });
    d.addEventListener("click", (e) => { if (e.target === d) { closeCurrent(); resolve(); } });
    d.showModal();
    ok.focus();
  });
}
