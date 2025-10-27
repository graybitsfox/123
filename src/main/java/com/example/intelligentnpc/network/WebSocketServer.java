package com.example.intelligentnpc.network;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

public class WebSocketServer {
    private final int port;
    private final Gson gson = new Gson();
    private boolean isRunning = false;
    
    // Статистика
    private static long totalMessages = 0;
    private static long totalConnections = 0;
    
    public WebSocketServer(int port) {
        this.port = port;
    }
    
    public void start() throws Exception {
        if (isRunning) {
            IntelligentNPCMod.LOGGER.warn("WebSocketServer is already running on port {}", port);
            return;
        }
        
        // Заглушка - WebSocket отключен
        IntelligentNPCMod.LOGGER.info("WebSocket server заглушка - отключен для совместимости");
        IntelligentNPCMod.LOGGER.info("Для работы WebSocket добавьте зависимости Jetty в build.gradle.kts");
        
        isRunning = true;
    }
    
    public void stop() throws Exception {
        if (!isRunning) {
            return;
        }
        
        isRunning = false;
        IntelligentNPCMod.LOGGER.info("WebSocket server stopped");
    }
    
    // Заглушки для методов
    public void broadcastNPCUpdate(String npcName, JsonObject updateData) {
        // Заглушка - ничего не делаем
    }
    
    public void broadcastNPCMessage(String npcName, String message) {
        // Заглушка - ничего не делаем
    }
    
    public CompletableFuture<JsonObject> requestLLMResponse(JsonObject context) {
        // Заглушка - возвращаем пустой ответ
        JsonObject emptyResponse = new JsonObject();
        emptyResponse.addProperty("type", "stub");
        emptyResponse.addProperty("message", "WebSocket disabled");
        return CompletableFuture.completedFuture(emptyResponse);
    }
    
    // Геттеры для статистики
    public static boolean isRunning() { return false; }
    public static long getTotalMessages() { return totalMessages; }
    public static long getTotalConnections() { return totalConnections; }
    public static int getActiveSessions() { return 0; }
    public static int getPendingRequests() { return 0; }
    
    // Очистка просроченных запросов
    public void cleanupExpiredRequests() {
        // Заглушка
    }
    
    // Получение состояния сервера
    public JsonObject getServerStatus() {
        JsonObject status = new JsonObject();
        status.addProperty("running", isRunning);
        status.addProperty("port", port);
        status.addProperty("active_sessions", 0);
        status.addProperty("pending_requests", 0);
        status.addProperty("total_messages", totalMessages);
        status.addProperty("total_connections", totalConnections);
        status.addProperty("note", "WebSocket server is disabled - stub implementation");
        return status;
    }
}