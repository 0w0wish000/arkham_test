package com.arkham.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 玩家自帶卡圖(FFG 版權,不入包,docs/06 §12):把工作目錄的 {@code cardimg/}
 * 以 {@code /cardimg/**} 服務 —— 發行包用戶把圖檔資料夾放在啟動器旁即可,
 * 檔名 {@code <slug>.webp|png|jpg}。沒有資料夾 → 404,前端自動退回色塊占位。
 */
@Configuration
public class StaticContentConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/cardimg/**")
                .addResourceLocations("file:cardimg/");
    }
}
