package com.arkham.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot entry point for the authoritative Arkham game server. Wires up the
 * WebSocket endpoint (see {@code config.WebSocketConfig}) over which clients exchange
 * the JSON messages defined in {@code protocol/messages.ts}.
 */
@SpringBootApplication
public class GameServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GameServerApplication.class, args);
    }
}
