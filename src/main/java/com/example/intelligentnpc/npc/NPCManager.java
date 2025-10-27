package com.example.intelligentnpc.npc;

import com.example.intelligentnpc.IntelligentNPCMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NPCManager {
    // Реестр типов сущностей
    public static final EntityType<NPCEntity> NPC_ENTITY_TYPE = Registry.register(
        Registries.ENTITY_TYPE,
        Identifier.of(IntelligentNPCMod.MOD_ID, "intelligent_npc"),
        FabricEntityTypeBuilder.create(SpawnGroup.MISC, (EntityType<NPCEntity> type, World world) -> new NPCEntity(type, world))
            .dimensions(EntityDimensions.fixed(0.6f, 1.95f))
            .build()
    );
    
    // Хранение всех NPC
    private final Map<String, NPCEntity> npcByName = new ConcurrentHashMap<>();
    private final Map<UUID, NPCEntity> npcById = new ConcurrentHashMap<>();
    private final Set<NPCEntity> allNPCs = ConcurrentHashMap.newKeySet();
    
    // Счетчики и статистика
    private int totalNPCsCreated = 0;
    private long lastTickTime = 0;
    private final Map<String, Long> playerLastCommand = new ConcurrentHashMap<>();
    
    // Настройки
    private static final int MAX_NPCS_PER_PLAYER = 5;
    private static final int MAX_TOTAL_NPCS = 50;
    private static final long COMMAND_COOLDOWN = 3000; // 3 секунды
    private static final Pattern CHAT_COMMAND_PATTERN = Pattern.compile("^(\\w+),\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    
    public NPCManager() {
        registerNPCAttributes();
        IntelligentNPCMod.LOGGER.info("NPCManager initialized");
    }
    
    private void registerNPCAttributes() {
        FabricDefaultAttributeRegistry.register(NPC_ENTITY_TYPE, NPCEntity.createNPCAttributes());
    }
    
    // Создание NPC
    public NPCEntity createNPC(String name, String role, BlockPos position, ServerWorld world, PlayerEntity creator) {
        // Проверки ограничений
        if (npcByName.containsKey(name.toLowerCase())) {
            if (creator != null) {
                creator.sendMessage(Text.literal("§cNPC с именем '" + name + "' уже существует!"), false);
            }
            return null;
        }
        
        if (allNPCs.size() >= MAX_TOTAL_NPCS) {
            if (creator != null) {
                creator.sendMessage(Text.literal("§cДостигнуто максимальное количество NPC (" + MAX_TOTAL_NPCS + ")!"), false);
            }
            return null;
        }
        
        if (creator != null) {
            long playerNPCCount = allNPCs.stream()
                .filter(npc -> Objects.equals(npc.getOwnerId(), creator.getUuid()))
                .count();
                
            if (playerNPCCount >= MAX_NPCS_PER_PLAYER) {
                creator.sendMessage(Text.literal("§cВы можете создать максимум " + MAX_NPCS_PER_PLAYER + " NPC!"), false);
                return null;
            }
        }
        
        // Создание NPC
        NPCEntity npc = new NPCEntity(NPC_ENTITY_TYPE, world, name, role, 
            creator != null ? creator.getUuid() : null);
        
        // Установка позиции
        npc.setPosition(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        
        // Спавн в мире
        world.spawnEntity(npc);
        
        // Регистрация в менеджере
        registerNPC(npc);
        
        // Уведомление
        if (creator != null) {
            creator.sendMessage(Text.literal("§aСоздан NPC '" + name + "' с ролью '" + role + "'"), false);
        }
        
        // Логирование
        IntelligentNPCMod.LOGGER.info("Created NPC '{}' with role '{}' at {} by {}", 
            name, role, position, creator != null ? creator.getName().getString() : "system");
        
        totalNPCsCreated++;
        return npc;
    }
    
    private void registerNPC(NPCEntity npc) {
        String nameKey = npc.getNpcName().toLowerCase();
        npcByName.put(nameKey, npc);
        npcById.put(npc.getUuid(), npc);
        allNPCs.add(npc);
    }
    
    // Удаление NPC
    public boolean removeNPC(String name, PlayerEntity remover) {
        NPCEntity npc = getNPCByName(name);
        if (npc == null) {
            if (remover != null) {
                remover.sendMessage(Text.literal("§cNPC '" + name + "' не найден!"), false);
            }
            return false;
        }
        
        // Проверка прав (только создатель или админ может удалить)
        if (remover != null && !remover.hasPermissionLevel(2)) {
            if (!Objects.equals(npc.getOwnerId(), remover.getUuid())) {
                remover.sendMessage(Text.literal("§cВы можете удалить только своих NPC!"), false);
                return false;
            }
        }
        
        return removeNPC(npc, remover);
    }
    
    public boolean removeNPC(NPCEntity npc, PlayerEntity remover) {
        if (npc == null) return false;
        
        // Сохранение памяти перед удалением
        npc.saveToMemory();
        
        // Удаление из регистра
        unregisterNPC(npc);
        
        // Удаление из мира
        npc.discard();
        
        // Уведомление
        if (remover != null) {
            remover.sendMessage(Text.literal("§aNPC '" + npc.getNpcName() + "' удален"), false);
        }
        
        IntelligentNPCMod.LOGGER.info("Removed NPC '{}' by {}", 
            npc.getNpcName(), remover != null ? remover.getName().getString() : "system");
        
        return true;
    }
    
    private void unregisterNPC(NPCEntity npc) {
        npcByName.remove(npc.getNpcName().toLowerCase());
        npcById.remove(npc.getUuid());
        allNPCs.remove(npc);
    }
    
    // Получение NPC
    public NPCEntity getNPCByName(String name) {
        return npcByName.get(name.toLowerCase());
    }
    
    public NPCEntity getNPCById(UUID id) {
        return npcById.get(id);
    }
    
    public List<NPCEntity> getAllNPCs() {
        return new ArrayList<>(allNPCs);
    }
    
    public List<NPCEntity> getNPCsByOwner(UUID ownerId) {
        return allNPCs.stream()
            .filter(npc -> Objects.equals(npc.getOwnerId(), ownerId))
            .toList();
    }
    
    public List<NPCEntity> getNearbyNPCs(Vec3d position, double radius) {
        return allNPCs.stream()
            .filter(npc -> npc.getPos().squaredDistanceTo(position) <= radius * radius)
            .toList();
    }
    
    // Обработка тиков
    public void tick(MinecraftServer server) {
        long currentTime = System.currentTimeMillis();
        this.lastTickTime = currentTime;
        
        // Очистка недействительных NPC
        allNPCs.removeIf(npc -> !npc.isAlive() || npc.isRemoved());
        
        // Синхронизация реестров
        if (server.getTicks() % 200 == 0) { // Каждые 10 секунд
            synchronizeRegistries();
        }
        
        // Автосохранение
        if (server.getTicks() % 12000 == 0) { // Каждые 10 минут
            saveAllNPCsToMemory();
        }
    }
    
    private void synchronizeRegistries() {
        // Удаление мертвых NPC из реестров
        Set<String> deadNames = new HashSet<>();
        Set<UUID> deadIds = new HashSet<>();
        
        npcByName.forEach((name, npc) -> {
            if (!npc.isAlive() || npc.isRemoved()) {
                deadNames.add(name);
            }
        });
        
        npcById.forEach((id, npc) -> {
            if (!npc.isAlive() || npc.isRemoved()) {
                deadIds.add(id);
            }
        });
        
        deadNames.forEach(npcByName::remove);
        deadIds.forEach(npcById::remove);
        
        if (!deadNames.isEmpty() || !deadIds.isEmpty()) {
            IntelligentNPCMod.LOGGER.debug("Cleaned up {} dead NPCs from registries", 
                deadNames.size() + deadIds.size());
        }
    }
    
    // Обработка чата
    public void handleChatMessage(Text message, ServerPlayerEntity sender) {
        String messageText = message.getString();
        
        // Проверка на команды для NPC (формат: "ИмяNPC, команда")
        Matcher matcher = CHAT_COMMAND_PATTERN.matcher(messageText);
        if (matcher.matches()) {
            String npcName = matcher.group(1);
            String command = matcher.group(2);
            
            handleNPCChatCommand(npcName, command, sender);
        } else {
            // Обычное сообщение - все NPC поблизости могут отреагировать
            handleGeneralChatMessage(messageText, sender);
        }
    }
    
    private void handleNPCChatCommand(String npcName, String command, ServerPlayerEntity sender) {
        // Проверка кулдауна
        String playerKey = sender.getUuid().toString();
        Long lastCommand = playerLastCommand.get(playerKey);
        long currentTime = System.currentTimeMillis();
        
        if (lastCommand != null && (currentTime - lastCommand) < COMMAND_COOLDOWN) {
            sender.sendMessage(Text.literal("§cПожалуйста, подождите перед следующей командой"), true);
            return;
        }
        
        // Поиск NPC
        NPCEntity npc = getNPCByName(npcName);
        if (npc == null) {
            sender.sendMessage(Text.literal("§cNPC '" + npcName + "' не найден"), true);
            return;
        }
        
        // Проверка расстояния
        if (npc.squaredDistanceTo(sender) > 100) { // 10 блоков
            sender.sendMessage(Text.literal("§cВы слишком далеко от NPC '" + npcName + "'"), true);
            return;
        }
        
        // Обработка команды
        try {
            processNPCCommand(npc, command, sender);
            playerLastCommand.put(playerKey, currentTime);
        } catch (Exception e) {
            sender.sendMessage(Text.literal("§cОшибка при обработке команды: " + e.getMessage()), false);
            IntelligentNPCMod.LOGGER.error("Error processing NPC command from {}: {}", 
                sender.getName().getString(), e.getMessage());
        }
    }
    
    private void processNPCCommand(NPCEntity npc, String command, ServerPlayerEntity sender) {
        // Запись взаимодействия в память
        npc.getMemory().recordPlayerInteraction(sender, "chat_command", command);
        
        // Получение AI модуля для обработки
        if (command.toLowerCase().startsWith("построй") || command.toLowerCase().startsWith("build")) {
            npc.getBuilderAI().handleBuildCommand(command, sender);
        } else if (command.toLowerCase().startsWith("торгуй") || command.toLowerCase().startsWith("trade")) {
            npc.getTradeAI().handleTradeCommand(command, sender);
        } else if (command.toLowerCase().startsWith("следуй") || command.toLowerCase().startsWith("follow")) {
            handleFollowCommand(npc, sender);
        } else if (command.toLowerCase().startsWith("стой") || command.toLowerCase().startsWith("stay")) {
            handleStayCommand(npc, sender);
        } else if (command.toLowerCase().startsWith("иди") || command.toLowerCase().startsWith("go")) {
            handleGoCommand(npc, command, sender);
        } else {
            // Общая обработка через ChatAI
            npc.getChatAI().handleChatCommand(command, sender);
        }
    }
    
    private void handleFollowCommand(NPCEntity npc, ServerPlayerEntity sender) {
        npc.getNPCBrain().setGoal("follow_player");
        npc.getNPCBrain().addTask(new NPCBrain.MoveToTask(sender.getBlockPos()));
        sender.sendMessage(Text.literal("§a" + npc.getNpcName() + " теперь следует за вами"), true);
    }
    
    private void handleStayCommand(NPCEntity npc, ServerPlayerEntity sender) {
        npc.getNPCBrain().setGoal("stay");
        npc.getNPCBrain().clearTasks();
        sender.sendMessage(Text.literal("§a" + npc.getNpcName() + " остается на месте"), true);
    }
    
    private void handleGoCommand(NPCEntity npc, String command, ServerPlayerEntity sender) {
        // Простая обработка команды "иди к точке"
        npc.getNPCBrain().setGoal("move_to_location");
        sender.sendMessage(Text.literal("§a" + npc.getNpcName() + " идет к указанному месту"), true);
    }
    
    private void handleGeneralChatMessage(String message, ServerPlayerEntity sender) {
        // Поиск NPC поблизости для реакции на общий чат
        List<NPCEntity> nearbyNPCs = getNearbyNPCs(sender.getPos(), 15.0);
        
        for (NPCEntity npc : nearbyNPCs) {
            try {
                // Случайная вероятность реакции на общий чат
                if (Math.random() < 0.1) { // 10% шанс
                    npc.getChatAI().handleGeneralChat(message, sender);
                }
            } catch (Exception e) {
                IntelligentNPCMod.LOGGER.error("Error in NPC {} general chat response: {}", 
                    npc.getNpcName(), e.getMessage());
            }
        }
    }
    
    // Сохранение и загрузка
    public void saveAllNPCsToMemory() {
        int savedCount = 0;
        for (NPCEntity npc : allNPCs) {
            try {
                npc.saveToMemory();
                savedCount++;
            } catch (Exception e) {
                IntelligentNPCMod.LOGGER.error("Failed to save memory for NPC {}: {}", 
                    npc.getNpcName(), e.getMessage());
            }
        }
        
        if (savedCount > 0) {
            IntelligentNPCMod.LOGGER.debug("Saved memory for {} NPCs", savedCount);
        }
    }
    
    public void loadNPCsFromMemory(MinecraftServer server) {
        IntelligentNPCMod.LOGGER.info("Loading NPCs from memory files...");
        File memoryDir = new File(IntelligentNPCMod.getMemoryDirectory());
        File[] memoryFiles = memoryDir.listFiles((dir, name) -> name.endsWith(".json"));

        if (memoryFiles == null || memoryFiles.length == 0) {
            IntelligentNPCMod.LOGGER.info("No NPC memory files found to load.");
            return;
        }

        int loadedCount = 0;
        for (File memoryFile : memoryFiles) {
            try {
                com.google.gson.JsonObject npcData = NPCMemory.loadNpcData(memoryFile);
                if (npcData == null) continue;

                String name = npcData.get("npc_name").getAsString();
                String role = npcData.get("role").getAsString();
                UUID ownerId = npcData.has("owner_id") ? UUID.fromString(npcData.get("owner_id").getAsString()) : null;

                Identifier worldId = Identifier.of(npcData.get("world").getAsString());
                ServerWorld world = server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, worldId));

                if (world == null) {
                    IntelligentNPCMod.LOGGER.warn("World {} not found for NPC {}, skipping.", worldId, name);
                    continue;
                }

                double x = npcData.get("x").getAsDouble();
                double y = npcData.get("y").getAsDouble();
                double z = npcData.get("z").getAsDouble();
                float yaw = npcData.get("yaw").getAsFloat();
                float pitch = npcData.get("pitch").getAsFloat();
                float health = npcData.get("health").getAsFloat();

                NPCEntity npc = new NPCEntity(NPC_ENTITY_TYPE, world, name, role, ownerId);
                npc.setPosition(x, y, z);
                npc.setYaw(yaw);
                npc.setPitch(pitch);
                npc.setHealth(health);

                // Спавн в мире
                world.spawnEntity(npc);

                // Регистрация в менеджере
                registerNPC(npc);

                IntelligentNPCMod.LOGGER.info("Loaded NPC '{}' in world '{}' at ({}, {}, {})", name, worldId, x, y, z);
                loadedCount++;

            } catch (Exception e) {
                IntelligentNPCMod.LOGGER.error("Failed to load NPC from file {}: {}", memoryFile.getName(), e.getMessage(), e);
            }
        }
        IntelligentNPCMod.LOGGER.info("Finished loading {} NPCs from memory.", loadedCount);
    }
    
    // Статистика и информация
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        stats.put("total_npcs", allNPCs.size());
        stats.put("total_created", totalNPCsCreated);
        stats.put("last_tick_time", lastTickTime);
        
        // Статистика по ролям
        Map<String, Integer> roleStats = new HashMap<>();
        for (NPCEntity npc : allNPCs) {
            roleStats.merge(npc.getRole(), 1, Integer::sum);
        }
        stats.put("roles", roleStats);
        
        // Статистика по владельцам
        Map<String, Integer> ownerStats = new HashMap<>();
        for (NPCEntity npc : allNPCs) {
            if (npc.getOwnerId() != null) {
                ownerStats.merge(npc.getOwnerId().toString(), 1, Integer::sum);
            }
        }
        stats.put("owners", ownerStats);
        
        return stats;
    }
    
    public void printStatistics(PlayerEntity player) {
        Map<String, Object> stats = getStatistics();
        
        player.sendMessage(Text.literal("§6=== Статистика NPC ==="), false);
        player.sendMessage(Text.literal("§eВсего NPC: §f" + stats.get("total_npcs")), false);
        player.sendMessage(Text.literal("§eСоздано за сессию: §f" + stats.get("total_created")), false);
        
        @SuppressWarnings("unchecked")
        Map<String, Long> roleStats = (Map<String, Long>) stats.get("roles");
        if (!roleStats.isEmpty()) {
            player.sendMessage(Text.literal("§eПо ролям:"), false);
            roleStats.forEach((role, count) -> 
                player.sendMessage(Text.literal("  §7" + role + ": §f" + count), false));
        }
        
        if (player.hasPermissionLevel(2)) {
            @SuppressWarnings("unchecked")
            Map<String, Long> ownerStats = (Map<String, Long>) stats.get("owners");
            if (!ownerStats.isEmpty()) {
                player.sendMessage(Text.literal("§eПо владельцам: §f" + ownerStats.size() + " игроков"), false);
            }
        }
    }
    
    // Очистка ресурсов
    public void shutdown() {
        IntelligentNPCMod.LOGGER.info("Shutting down NPCManager...");
        
        // Сохранение всех NPC
        saveAllNPCsToMemory();
        
        // Очистка коллекций
        npcByName.clear();
        npcById.clear();
        allNPCs.clear();
        playerLastCommand.clear();
        
        IntelligentNPCMod.LOGGER.info("NPCManager shut down complete");
    }
}