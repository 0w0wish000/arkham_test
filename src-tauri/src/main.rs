#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// Arkham 桌面殼:讓玩家指定「卡圖資料夾」,直接讀本機檔(無瀏覽器沙箱)。
// 前端(deckbuilder.html)透過 window.__TAURI__.core.invoke(...) 呼叫下列指令。

use base64::Engine;
use std::path::PathBuf;

/// 開啟原生資料夾對話框,讓玩家選卡圖資料夾;回傳資料夾路徑。
#[tauri::command]
fn pick_card_folder() -> Option<String> {
    rfd::FileDialog::new()
        .set_title("選擇卡圖資料夾(檔名為 <code>.webp)")
        .pick_folder()
        .map(|p| p.to_string_lossy().into_owned())
}

/// 依 code 讀本機卡圖,回傳可直接塞進 <img src> 的 data URL;找不到回 None。
/// 依序嘗試 webp / png / jpg / jpeg。
#[tauri::command]
fn read_card_image(dir: String, code: String) -> Option<String> {
    for ext in ["webp", "png", "jpg", "jpeg"] {
        let path = PathBuf::from(&dir).join(format!("{code}.{ext}"));
        if let Ok(bytes) = std::fs::read(&path) {
            let mime = match ext {
                "png" => "image/png",
                "jpg" | "jpeg" => "image/jpeg",
                _ => "image/webp",
            };
            let b64 = base64::engine::general_purpose::STANDARD.encode(&bytes);
            return Some(format!("data:{mime};base64,{b64}"));
        }
    }
    None
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![pick_card_folder, read_card_image])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
