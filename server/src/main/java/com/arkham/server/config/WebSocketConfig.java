package com.arkham.server.config;

import com.arkham.server.net.GameSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the game WebSocket endpoint at {@code /ws/game} (protocol.md:
 * {@code ws://<host>:8080/ws/game}). Origins are open for local/LAN development.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameSocketHandler gameSocketHandler;

    public WebSocketConfig(GameSocketHandler gameSocketHandler) {
        this.gameSocketHandler = gameSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gameSocketHandler, "/ws/game")
                .setAllowedOrigins("*"); // dev: allow any origin
    }

    /**
     * 存檔載入是把整份存檔(狀態+事件記錄)一則 WS 訊息上行(docs/09 §7);
     * Tomcat 預設單則文字訊息上限 8KB,超過直接切線(close 1009)——
     * 戰役中期存檔必超,玩家只看到「載入中」卡死。放寬到 4MB。
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean webSocketContainer() {
        var c = new org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean();
        c.setMaxTextMessageBufferSize(4 * 1024 * 1024);
        c.setMaxBinaryMessageBufferSize(4 * 1024 * 1024);
        return c;
    }
}
