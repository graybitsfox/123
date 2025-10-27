package com.example.intelligentnpc;

import com.example.intelligentnpc.commands.NPCCommand;
import com.example.intelligentnpc.commands.NPCChatCommand;
import com.example.intelligentnpc.network.WebSocketServer;
import com.example.intelligentnpc.npc.NPCManager;
import com.example.intelligentnpc.ui.WebDashboard;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class IntelligentNPCMod implements ModInitializer {
    public static final String MOD_ID = "intelligentnpc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static IntelligentNPCMod instance;
    private NPCManager npcManager;
    private WebSocketServer webSocketServer;
    private WebDashboard webDashboard;
    private MinecraftServer server;
    
    // Конфигурация
    private static final int WEB_DASHBOARD_PORT = 8123;
    private static final int WEBSOCKET_PORT = 8124;
    private static final String DATA_DIRECTORY = "intelligentnpc";
    private static final String MEMORY_DIRECTORY = DATA_DIRECTORY + "/memory";
    
    @Override
    public void onInitialize() {
        LOGGER.info("=== Initializing Intelligent NPCs mod (DEBUG MODE) ===");
        LOGGER.debug("Setting mod instance...");
        instance = this;
        
        try {
            LOGGER.debug("Creating data directories...");
            createDataDirectories();
            
            LOGGER.debug("Initializing NPC Manager...");
            npcManager = new NPCManager();
            LOGGER.info("NPC Manager initialized: {}", npcManager != null ? "SUCCESS" : "FAILED");
            
            LOGGER.debug("Registering commands...");
            registerCommands();
            
            LOGGER.debug("Registering events...");
            registerEvents();
            
            LOGGER.info("=== Intelligent NPCs mod initialized successfully! ===");
            
        } catch (Exception e) {
            LOGGER.error("=== FATAL ERROR during mod initialization ===", e);
            LOGGER.error("Stack trace: ", e);
            throw e; // Re-throw to fail initialization
        }
    }
    
    private void createDataDirectories() {
        LOGGER.debug("Starting data directories creation...");
        try {
            Path dataPath = Paths.get(DATA_DIRECTORY);
            Path memoryPath = Paths.get(MEMORY_DIRECTORY);
            
            LOGGER.debug("Data directory path: {}", dataPath.toAbsolutePath());
            LOGGER.debug("Memory directory path: {}", memoryPath.toAbsolutePath());
            
            if (!Files.exists(dataPath)) {
                LOGGER.debug("Data directory doesn't exist, creating...");
                Files.createDirectories(dataPath);
                LOGGER.info("✓ Created data directory: {}", dataPath.toAbsolutePath());
            } else {
                LOGGER.debug("Data directory already exists: {}", dataPath.toAbsolutePath());
            }
            
            if (!Files.exists(memoryPath)) {
                LOGGER.debug("Memory directory doesn't exist, creating...");
                Files.createDirectories(memoryPath);
                LOGGER.info("✓ Created memory directory: {}", memoryPath.toAbsolutePath());
            } else {
                LOGGER.debug("Memory directory already exists: {}", memoryPath.toAbsolutePath());
            }
            
            LOGGER.debug("Data directories creation completed successfully");
            
        } catch (Exception e) {
            LOGGER.error("=== FAILED to create data directories ===", e);
            LOGGER.error("Error details: {}", e.getMessage());
            LOGGER.error("Stack trace: ", e);
        }
    }
    
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            NPCCommand.register(dispatcher);
            NPCChatCommand.register(dispatcher);
        });
    }
    
    private void registerEvents() {
        // Событие старта сервера
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            this.server = server;
            onServerStarted(server);
        });
        
        // Событие остановки сервера
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            onServerStopping(server);
        });
        
        // Событие тика сервера для обновления NPC
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (npcManager != null) {
                npcManager.tick(server);
            }
        });
        
        // Обработка чата для взаимодействия с NPC
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            if (npcManager != null) {
                npcManager.handleChatMessage(Text.literal(message.getContent().getString()), sender);
            }
        });
    }
    
    private void onServerStarted(MinecraftServer server) {
        LOGGER.info("=== Server started, initializing services ===");
        LOGGER.debug("Server instance: {}", server);
        
        try {
            LOGGER.debug("Creating WebSocket server on port {}...", WEBSOCKET_PORT);
            webSocketServer = new WebSocketServer(WEBSOCKET_PORT);
            webSocketServer.start();
            LOGGER.info("✓ WebSocket server started on port {} (stub mode)", WEBSOCKET_PORT);
            
            LOGGER.debug("Creating web dashboard on port {}...", WEB_DASHBOARD_PORT);
            webDashboard = new WebDashboard(WEB_DASHBOARD_PORT, npcManager);
            webDashboard.start();
            LOGGER.info("✓ Web dashboard started on port {} (stub mode)", WEB_DASHBOARD_PORT);
            
            LOGGER.debug("Loading existing NPCs from memory...");
            if (npcManager != null) {
                npcManager.loadNPCsFromMemory(server);
                LOGGER.info("✓ NPC loading from memory completed");
            } else {
                LOGGER.error("NPC Manager is null! Cannot load NPCs");
            }
            
            LOGGER.info("=== All services started successfully ===");
            
        } catch (Exception e) {
            LOGGER.error("=== FAILED to start services ===", e);
            LOGGER.error("Error details: {}", e.getMessage());
            LOGGER.error("Stack trace: ", e);
            
            // Продолжаем работу даже если веб-сервисы не запустились
            LOGGER.warn("Continuing without web services...");
        }
    }
    
    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("Server stopping, shutting down services...");
        
        try {
            // Сохранение всех NPC в память
            if (npcManager != null) {
                npcManager.saveAllNPCsToMemory();
            }
            
            // Остановка веб-сервисов
            if (webDashboard != null) {
                webDashboard.stop();
            }
            
            if (webSocketServer != null) {
                webSocketServer.stop();
            }
            
            LOGGER.info("All services stopped successfully");
        } catch (Exception e) {
            LOGGER.error("Error while stopping services", e);
        }
    }
    
    // Геттеры для доступа к компонентам
    public static IntelligentNPCMod getInstance() {
        return instance;
    }
    
    public NPCManager getNpcManager() {
        return npcManager;
    }
    
    public WebSocketServer getWebSocketServer() {
        return webSocketServer;
    }
    
    public WebDashboard getWebDashboard() {
        return webDashboard;
    }
    
    public MinecraftServer getServer() {
        return server;
    }
    
    // Статические методы для получения путей к данным
    public static String getDataDirectory() {
        return DATA_DIRECTORY;
    }
    
    public static String getMemoryDirectory() {
        return MEMORY_DIRECTORY;
    }
    
    public static File getMemoryFile(String npcName) {
        return new File(MEMORY_DIRECTORY, npcName.toLowerCase() + ".json");
    }
}