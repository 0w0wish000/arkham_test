import { defineConfig } from "vite";
import { resolve } from "path";

// 前端(TypeScript + PixiJS)。共享協定型別放在 ../protocol,由 @protocol 別名匯入。
export default defineConfig({
  resolve: {
    alias: { "@protocol": resolve(__dirname, "../protocol") },
  },
  server: {
    port: 5173,
    fs: { allow: [".."] }, // 允許 import 專案根的 ../protocol/*
    proxy: {
      // 轉發 WebSocket 到 Java 遊戲伺服器
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
});
