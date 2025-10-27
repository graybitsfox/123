package com.example.intelligentnpc.network;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.CompletableFuture;

public class LLMClient {
    private final Gson gson;
    
    // Конфигурация LLM
    private String apiUrl = "http://localhost:11434/api/generate"; // Ollama по умолчанию
    private String apiKey = "";
    private String model = "llama3.1:8b";
    private boolean useOpenAI = false;
    private int maxTokens = 500;
    private double temperature = 0.7;
    private int timeout = 30; // секунды
    
    // Статистика
    private long totalRequests = 0;
    private long successfulRequests = 0;
    private long failedRequests = 0;
    private long averageResponseTime = 0;
    
    public LLMClient() {
        this.gson = new Gson();
        loadConfiguration();
        IntelligentNPCMod.LOGGER.info("LLMClient initialized (stub mode - HTTP client disabled)");
    }
    
    private void loadConfiguration() {
        // Проверка переменных окружения
        String envApiKey = System.getenv("OPENAI_API_KEY");
        if (envApiKey != null && !envApiKey.isEmpty()) {
            this.apiKey = envApiKey;
            this.useOpenAI = true;
            this.apiUrl = "https://api.openai.com/v1/chat/completions";
            this.model = "gpt-3.5-turbo";
            IntelligentNPCMod.LOGGER.info("Using OpenAI API with environment key (stub)");
        }
        
        String envOllamaUrl = System.getenv("OLLAMA_URL");
        if (envOllamaUrl != null && !envOllamaUrl.isEmpty()) {
            this.apiUrl = envOllamaUrl + "/api/generate";
            IntelligentNPCMod.LOGGER.info("Using custom Ollama URL: {} (stub)", envOllamaUrl);
        }
    }
    
    public CompletableFuture<JsonObject> sendRequest(JsonObject context) {
        return CompletableFuture.supplyAsync(() -> {
            totalRequests++;
            
            try {
                // Заглушка - возвращаем простой ответ
                JsonObject response = createStubResponse(context);
                successfulRequests++;
                return response;
                
            } catch (Exception e) {
                failedRequests++;
                IntelligentNPCMod.LOGGER.error("LLM request failed (stub): {}", e.getMessage());
                return createErrorResponse(e.getMessage());
            }
        });
    }
    
    private JsonObject createStubResponse(JsonObject context) {
        // Простой ответ-заглушка
        JsonObject response = new JsonObject();
        
        // Простая реакция на контекст
        String npcName = context.has("npc_name") ? context.get("npc_name").getAsString() : "Unknown";
        String speech = generateStubSpeech(context);
        
        response.addProperty("speech", speech);
        response.add("commands", new com.google.gson.JsonArray());
        response.add("emotions", new JsonObject());
        
        return response;
    }
    
    private String generateStubSpeech(JsonObject context) {
        // Генерация простых ответов без LLM
        String[] responses = {
            "Привет! Я пока работаю в тестовом режиме.",
            "Интересно... дайте мне подумать.",
            "Понял, попробую сделать что-то полезное.",
            "Хм, а что если попробовать по-другому?",
            "Отлично! Давайте работать вместе.",
            "Это интересная задача!",
        };
        
        return responses[(int)(Math.random() * responses.length)];
    }
    
    private JsonObject createErrorResponse(String errorMessage) {
        JsonObject response = new JsonObject();
        response.addProperty("speech", "");
        response.add("commands", new com.google.gson.JsonArray());
        response.add("emotions", new JsonObject());
        response.addProperty("error", errorMessage);
        return response;
    }
    
    // Методы конфигурации
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
        IntelligentNPCMod.LOGGER.info("LLM API URL changed to: {}", apiUrl);
    }
    
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        this.useOpenAI = apiKey != null && !apiKey.isEmpty();
        IntelligentNPCMod.LOGGER.info("LLM API key updated, OpenAI mode: {}", useOpenAI);
    }
    
    public void setModel(String model) {
        this.model = model;
        IntelligentNPCMod.LOGGER.info("LLM model changed to: {}", model);
    }
    
    public void setMaxTokens(int maxTokens) {
        this.maxTokens = Math.max(50, Math.min(2000, maxTokens));
    }
    
    public void setTemperature(double temperature) {
        this.temperature = Math.max(0.0, Math.min(2.0, temperature));
    }
    
    public void setTimeout(int timeout) {
        this.timeout = Math.max(5, Math.min(120, timeout));
    }
    
    // Геттеры для статистики
    public long getTotalRequests() { return totalRequests; }
    public long getSuccessfulRequests() { return successfulRequests; }
    public long getFailedRequests() { return failedRequests; }
    public long getAverageResponseTime() { return averageResponseTime; }
    public double getSuccessRate() {
        return totalRequests > 0 ? (double) successfulRequests / totalRequests : 0.0;
    }
    
    // Геттеры конфигурации
    public String getApiUrl() { return apiUrl; }
    public String getModel() { return model; }
    public boolean isUsingOpenAI() { return useOpenAI; }
    public int getMaxTokens() { return maxTokens; }
    public double getTemperature() { return temperature; }
    public int getTimeout() { return timeout; }
    
    // Проверка доступности API
    public CompletableFuture<Boolean> testConnection() {
        return CompletableFuture.supplyAsync(() -> {
            IntelligentNPCMod.LOGGER.info("LLM connection test (stub mode) - returning true");
            return true; // Заглушка всегда возвращает успех
        });
    }
    
    // Очистка ресурсов
    public void shutdown() {
        IntelligentNPCMod.LOGGER.info("LLMClient shut down successfully (stub)");
    }
    
    // Получение статистики в виде JSON
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("total_requests", totalRequests);
        stats.addProperty("successful_requests", successfulRequests);
        stats.addProperty("failed_requests", failedRequests);
        stats.addProperty("success_rate", getSuccessRate());
        stats.addProperty("average_response_time", averageResponseTime);
        stats.addProperty("api_url", apiUrl);
        stats.addProperty("model", model);
        stats.addProperty("using_openai", useOpenAI);
        stats.addProperty("mode", "stub");
        return stats;
    }
}