package com.example.intelligentnpc.ui;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCManager;

public class WebDashboard {
    private final int port;
    private final NPCManager npcManager;
    private boolean isRunning = false;
    
    // Статистика запросов
    private long totalRequests = 0;
    private long lastRequestTime = 0;
    
    public WebDashboard(int port, NPCManager npcManager) {
        this.port = port;
        this.npcManager = npcManager;
    }
    
    public void start() throws Exception {
        if (isRunning) {
            IntelligentNPCMod.LOGGER.warn("WebDashboard уже запущен на порту {}", port);
            return;
        }
        
        // Заглушка - веб-панель отключена
        IntelligentNPCMod.LOGGER.info("WebDashboard заглушка - веб-панель отключена");
        IntelligentNPCMod.LOGGER.info("Для работы веб-панели добавьте зависимости Jetty в build.gradle.kts");
        
        isRunning = true;
    }
    
    public void stop() throws Exception {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        IntelligentNPCMod.LOGGER.info("WebDashboard остановлен");
    }
    
    // Геттеры для статистики
    public boolean isRunning() { return isRunning; }
    public long getTotalRequests() { return totalRequests; }
    public long getLastRequestTime() { return lastRequestTime; }
    public int getPort() { return port; }
}