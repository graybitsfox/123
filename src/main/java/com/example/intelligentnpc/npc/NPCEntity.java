package com.example.intelligentnpc.npc;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.ai.*;
import com.google.gson.JsonObject;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NPCEntity extends PathAwareEntity {
    // Данные для синхронизации с клиентом
    private static final TrackedData<String> NPC_NAME = DataTracker.registerData(NPCEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> NPC_ROLE = DataTracker.registerData(NPCEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<String> CURRENT_TASK = DataTracker.registerData(NPCEntity.class, TrackedDataHandlerRegistry.STRING);
    
    // Основные компоненты NPC
    private NPCBrain brain;
    private NPCMemory memory;
    private String npcName;
    private String role;
    private UUID ownerId; // Игрок, создавший NPC
    
    // AI модули
    private final Map<Class<?>, Object> aiModules = new ConcurrentHashMap<>();
    private BuilderAI builderAI;
    private TradeAI tradeAI;
    private SurvivalAI survivalAI;
    private CombatAI combatAI;
    private ChatAI chatAI;
    private LearningAI learningAI;
    private TeamAI teamAI;
    private TaskPlanner taskPlanner;
    
    // Состояние и статистика
    private long lastLLMUpdate = 0;
    private static final long LLM_UPDATE_INTERVAL = 3000; // 3 секунды
    private int ticksSinceLastAction = 0;
    private boolean isProcessingLLMResponse = false;
    
    // Конструкторы
    public NPCEntity(EntityType<? extends PathAwareEntity> entityType, World world) {
        super(entityType, world);
        IntelligentNPCMod.LOGGER.debug("=== Creating NPCEntity (basic constructor) ===");
        IntelligentNPCMod.LOGGER.debug("EntityType: {}", entityType);
        IntelligentNPCMod.LOGGER.debug("World: {}", world);
        
        this.npcName = "Unknown";
        this.role = "generic";
        IntelligentNPCMod.LOGGER.debug("Set default name: {}, role: {}", npcName, role);
        
        try {
            // Инициализация памяти и мозга даже для базового конструктора
            IntelligentNPCMod.LOGGER.debug("Creating NPCMemory for '{}'...", npcName);
            this.memory = new NPCMemory(npcName);
            IntelligentNPCMod.LOGGER.debug("✓ NPCMemory created successfully");
            
            IntelligentNPCMod.LOGGER.debug("Creating NPCBrain...");
            this.brain = new NPCBrain(this, memory);
            IntelligentNPCMod.LOGGER.debug("✓ NPCBrain created successfully");
            
            IntelligentNPCMod.LOGGER.debug("Initializing AI modules...");
            initializeAIModules();
            IntelligentNPCMod.LOGGER.debug("✓ AI modules initialized");
            
            IntelligentNPCMod.LOGGER.debug("Setting up custom AI...");
            setupCustomAI();
            IntelligentNPCMod.LOGGER.debug("✓ Custom AI setup completed");
            
            IntelligentNPCMod.LOGGER.info("✓ NPCEntity '{}' created successfully (basic)", npcName);
            
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("=== FAILED to create NPCEntity (basic) ===", e);
            IntelligentNPCMod.LOGGER.error("Name: {}, Role: {}", npcName, role);
            IntelligentNPCMod.LOGGER.error("Error: {}", e.getMessage());
            IntelligentNPCMod.LOGGER.error("Stack trace:", e);
            throw e; // Re-throw to prevent broken NPCs
        }
    }
    
    public NPCEntity(EntityType<? extends PathAwareEntity> entityType, World world, String name, String role, UUID ownerId) {
        super(entityType, world);
        IntelligentNPCMod.LOGGER.info("=== Creating NPCEntity '{}' with role '{}' ===", name, role);
        IntelligentNPCMod.LOGGER.debug("EntityType: {}", entityType);
        IntelligentNPCMod.LOGGER.debug("World: {}", world);
        IntelligentNPCMod.LOGGER.debug("Owner ID: {}", ownerId);
        
        this.npcName = name;
        this.role = role;
        this.ownerId = ownerId;
        
        try {
            // ВАЖНО: Сначала инициализируем память, потом AI модули
            IntelligentNPCMod.LOGGER.debug("Creating NPCMemory for '{}'...", name);
            this.memory = new NPCMemory(name);
            if (memory == null) {
                throw new IllegalStateException("NPCMemory creation failed for " + name);
            }
            IntelligentNPCMod.LOGGER.debug("✓ NPCMemory created successfully for '{}'", name);
            
            IntelligentNPCMod.LOGGER.debug("Creating NPCBrain for '{}'...", name);
            this.brain = new NPCBrain(this, memory);
            if (brain == null) {
                throw new IllegalStateException("NPCBrain creation failed for " + name);
            }
            IntelligentNPCMod.LOGGER.debug("✓ NPCBrain created successfully for '{}'", name);
            
            // Теперь можно безопасно инициализировать AI модули
            IntelligentNPCMod.LOGGER.debug("Initializing AI modules for '{}'...", name);
            initializeAIModules();
            IntelligentNPCMod.LOGGER.debug("✓ AI modules initialized for '{}'", name);
            
            IntelligentNPCMod.LOGGER.debug("Setting up custom AI for '{}'...", name);
            setupCustomAI();
            IntelligentNPCMod.LOGGER.debug("✓ Custom AI setup completed for '{}'", name);
            
            // Загрузка данных из памяти
            IntelligentNPCMod.LOGGER.debug("Loading memory data for '{}'...", name);
            loadFromMemory();
            IntelligentNPCMod.LOGGER.debug("✓ Memory data loaded for '{}'", name);
            
            IntelligentNPCMod.LOGGER.info("✓ NPCEntity '{}' created successfully!", name);
            
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("=== FAILED to create NPCEntity '{}' ===", name, e);
            IntelligentNPCMod.LOGGER.error("Role: {}, Owner: {}", role, ownerId);
            IntelligentNPCMod.LOGGER.error("Error: {}", e.getMessage());
            IntelligentNPCMod.LOGGER.error("Stack trace:", e);
            throw e; // Re-throw to prevent broken NPCs
        }
    }
    
    private void initializeAIModules() {
        IntelligentNPCMod.LOGGER.debug("=== initializeAIModules() START for '{}' ===", npcName);
        IntelligentNPCMod.LOGGER.debug("Memory state: {}", memory != null ? "EXISTS" : "NULL");
        IntelligentNPCMod.LOGGER.debug("Brain state: {}", brain != null ? "EXISTS" : "NULL");
        IntelligentNPCMod.LOGGER.debug("NPC name: '{}', role: '{}'", npcName, role);
        
        if (memory == null) {
            IntelligentNPCMod.LOGGER.error("CRITICAL: Memory is null during AI modules initialization!");
            throw new IllegalStateException("Cannot initialize AI modules: memory is null for NPC '" + npcName + "'");
        }
        
        try {
            IntelligentNPCMod.LOGGER.debug("Creating BuilderAI for '{}'...", npcName);
            this.builderAI = new BuilderAI(this);
            IntelligentNPCMod.LOGGER.debug("✓ BuilderAI created for '{}'", npcName);
            
            IntelligentNPCMod.LOGGER.debug("Creating TradeAI for '{}'...", npcName);
            this.tradeAI = new TradeAI(this);
            IntelligentNPCMod.LOGGER.debug("✓ TradeAI created for '{}'", npcName);
            
            IntelligentNPCMod.LOGGER.debug("Creating SurvivalAI for '{}'...", npcName);
            this.survivalAI = new SurvivalAI(this);
            IntelligentNPCMod.LOGGER.debug("✓ SurvivalAI created for '{}'", npcName);
            
            IntelligentNPCMod.LOGGER.debug("Creating CombatAI for '{}'...", npcName);
            this.combatAI = new CombatAI(this);
            IntelligentNPCMod.LOGGER.debug("✓ CombatAI created for '{}'", npcName);
            
            IntelligentNPCMod.LOGGER.debug("Creating ChatAI for '{}'...", npcName);
            this.chatAI = new ChatAI(this);
            IntelligentNPCMod.LOGGER.debug("✓ ChatAI created for '{}'", npcName);
            
            IntelligentNPCMod.LOGGER.debug("Creating LearningAI for '{}'...", npcName);
            this.learningAI = new LearningAI(this);
            IntelligentNPCMod.LOGGER.debug("✓ LearningAI created for '{}'", npcName);
            
            IntelligentNPCMod.LOGGER.debug("Creating TeamAI for '{}'...", npcName);
            this.teamAI = new TeamAI(this);
            IntelligentNPCMod.LOGGER.debug("✓ TeamAI created for '{}'", npcName);
            
            IntelligentNPCMod.LOGGER.debug("Creating TaskPlanner for '{}'...", npcName);
            this.taskPlanner = new TaskPlanner(this);
            IntelligentNPCMod.LOGGER.debug("✓ TaskPlanner created for '{}'", npcName);
            
            // Регистрация в мапе для быстрого доступа
            IntelligentNPCMod.LOGGER.debug("Registering AI modules in map for '{}'...", npcName);
            aiModules.put(BuilderAI.class, builderAI);
            aiModules.put(TradeAI.class, tradeAI);
            aiModules.put(SurvivalAI.class, survivalAI);
            aiModules.put(CombatAI.class, combatAI);
            aiModules.put(ChatAI.class, chatAI);
            aiModules.put(LearningAI.class, learningAI);
            aiModules.put(TeamAI.class, teamAI);
            aiModules.put(TaskPlanner.class, taskPlanner);
            
            IntelligentNPCMod.LOGGER.debug("=== initializeAIModules() COMPLETED for '{}' ===", npcName);
            
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("=== FAILED initializeAIModules() for '{}' ===", npcName, e);
            IntelligentNPCMod.LOGGER.error("Memory: {}, Brain: {}", memory != null, brain != null);
            IntelligentNPCMod.LOGGER.error("Exception: {}", e.getMessage());
            IntelligentNPCMod.LOGGER.error("Stack trace:", e);
            throw e; // Re-throw to fail creation
        }
    }
    
    private void setupCustomAI() {
        // Очистка стандартных целей AI
        this.goalSelector.clear(goal -> true);
        this.targetSelector.clear(goal -> true);
        
        // Добавление базовых целей выживания
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(1, new EscapeDangerGoal(this, 2.0));
        
        // Кастомные AI цели будут добавлены через модули
        setupModuleAIGoals();
    }
    
    private void setupModuleAIGoals() {
        // Каждый AI модуль может добавить свои цели
        survivalAI.registerGoals(this.goalSelector);
        combatAI.registerGoals(this.goalSelector, this.targetSelector);
        builderAI.registerGoals(this.goalSelector);
        tradeAI.registerGoals(this.goalSelector);
        learningAI.registerGoals(this.goalSelector);
        teamAI.registerGoals(this.goalSelector);
    }
    
    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(NPC_NAME, "");
        builder.add(NPC_ROLE, "generic");
        builder.add(CURRENT_TASK, "idle");
    }
    
    @Override
    public void tick() {
        super.tick();
        
        if (!getWorld().isClient) {
            ticksSinceLastAction++;
            
            // Обновление мозга NPC
            if (brain != null) {
                brain.tick();
            }
            
            // Обновление AI модулей
            tickAIModules();
            
            // Периодическое обновление от LLM
            if (shouldUpdateFromLLM()) {
                requestLLMUpdate();
            }
            
            // Сохранение в память каждые 5 минут
            if (ticksSinceLastAction % 6000 == 0) {
                saveToMemory();
            }
        }
    }
    
    private void tickAIModules() {
        try {
            survivalAI.tick();
            learningAI.tick();
            taskPlanner.tick();
            
            // Другие модули тикают по необходимости
            if (ticksSinceLastAction % 20 == 0) { // Раз в секунду
                chatAI.tick();
                teamAI.tick();
            }
            
            if (ticksSinceLastAction % 40 == 0) { // Раз в 2 секунды
                tradeAI.tick();
                builderAI.tick();
            }
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("Error ticking AI modules for NPC {}: {}", npcName, e.getMessage());
        }
    }
    
    private boolean shouldUpdateFromLLM() {
        long currentTime = System.currentTimeMillis();
        return !isProcessingLLMResponse && 
               (currentTime - lastLLMUpdate) > LLM_UPDATE_INTERVAL &&
               brain != null;
    }
    
    private void requestLLMUpdate() {
        if (brain == null) return;
        
        isProcessingLLMResponse = true;
        lastLLMUpdate = System.currentTimeMillis();
        
        // Создание контекста для LLM
        JsonObject context = createLLMContext();
        
        // Асинхронный запрос к LLM через мозг
        brain.processLLMContext(context, this::handleLLMResponse);
    }
    
    private JsonObject createLLMContext() {
        JsonObject context = new JsonObject();
        
        // Базовая информация об NPC
        context.addProperty("npc_name", npcName);
        context.addProperty("role", role);
        context.addProperty("current_task", getCurrentTask());
        
        // Позиция и окружение
        BlockPos pos = getBlockPos();
        context.addProperty("x", pos.getX());
        context.addProperty("y", pos.getY());
        context.addProperty("z", pos.getZ());
        context.addProperty("biome", getWorld().getBiome(pos).getIdAsString());
        context.addProperty("time_of_day", getWorld().getTimeOfDay());
        context.addProperty("weather", getWorld().isRaining() ? "rain" : "clear");
        
        // Здоровье и статус
        context.addProperty("health", getHealth());
        context.addProperty("max_health", getMaxHealth());
        context.addProperty("food_level", 20); // TODO: Добавить систему голода для NPC
        
        // Инвентарь
        context.add("inventory", getInventoryAsJson());
        
        // Ближайшие игроки
        context.add("nearby_players", getNearbyPlayersAsJson());
        
        // Ближайшие существа
        context.add("nearby_entities", getNearbyEntitiesAsJson());
        
        // Память и история
        if (memory != null) {
            context.add("recent_memories", memory.getRecentMemoriesAsJson());
        }
        
        return context;
    }
    
    private void handleLLMResponse(JsonObject response) {
        isProcessingLLMResponse = false;
        
        if (response == null || brain == null) {
            return;
        }
        
        try {
            // Обработка ответа от LLM через мозг
            brain.processLLMResponse(response);
            ticksSinceLastAction = 0;
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("Error processing LLM response for NPC {}: {}", npcName, e.getMessage());
        }
    }
    
    private JsonObject getInventoryAsJson() {
        // TODO: Реализовать инвентарь для NPC
        JsonObject inventory = new JsonObject();
        return inventory;
    }
    
    private JsonObject getNearbyPlayersAsJson() {
        // TODO: Получить информацию о ближайших игроках
        JsonObject players = new JsonObject();
        return players;
    }
    
    private JsonObject getNearbyEntitiesAsJson() {
        // TODO: Получить информацию о ближайших существах
        JsonObject entities = new JsonObject();
        return entities;
    }
    
    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        if (!getWorld().isClient) {
            // Обработка взаимодействия с игроком
            if (chatAI != null) {
                chatAI.handlePlayerInteraction(player, hand);
            }
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }
    
    // Методы для работы с именем и ролью
    public String getNpcName() {
        return npcName;
    }
    
    public void setNpcName(String name) {
        this.npcName = name;
        this.dataTracker.set(NPC_NAME, name);
    }
    
    public String getRole() {
        return role;
    }
    
    public void setRole(String role) {
        this.role = role;
        this.dataTracker.set(NPC_ROLE, role);
    }
    
    public String getCurrentTask() {
        return this.dataTracker.get(CURRENT_TASK);
    }
    
    public void setCurrentTask(String task) {
        this.dataTracker.set(CURRENT_TASK, task);
    }
    
    // Геттеры для AI модулей
    @SuppressWarnings("unchecked")
    public <T> T getAIModule(Class<T> moduleClass) {
        return (T) aiModules.get(moduleClass);
    }
    
    public BuilderAI getBuilderAI() { return builderAI; }
    public TradeAI getTradeAI() { return tradeAI; }
    public SurvivalAI getSurvivalAI() { return survivalAI; }
    public CombatAI getCombatAI() { return combatAI; }
    public ChatAI getChatAI() { return chatAI; }
    public LearningAI getLearningAI() { return learningAI; }
    public TeamAI getTeamAI() { return teamAI; }
    public TaskPlanner getTaskPlanner() { return taskPlanner; }
    public NPCBrain getNPCBrain() { return brain; }
    public NPCMemory getMemory() { return memory; }
    public UUID getOwnerId() { return ownerId; }
    
    // Сохранение и загрузка
    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putString("npc_name", npcName);
        nbt.putString("role", role);
        if (ownerId != null) {
            nbt.putUuid("owner_id", ownerId);
        }
        
        // Сохранение данных AI модулей
        NbtCompound aiData = new NbtCompound();
        saveAIModulesToNbt(aiData);
        nbt.put("ai_modules", aiData);
    }
    
    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        this.npcName = nbt.getString("npc_name");
        this.role = nbt.getString("role");
        
        if (nbt.containsUuid("owner_id")) {
            this.ownerId = nbt.getUuid("owner_id");
        }
        
        // Инициализация компонентов если они не были созданы
        if (memory == null) {
            this.memory = new NPCMemory(npcName);
        }
        if (brain == null) {
            this.brain = new NPCBrain(this, memory);
        }
        
        // Загрузка данных AI модулей
        if (nbt.contains("ai_modules")) {
            loadAIModulesFromNbt(nbt.getCompound("ai_modules"));
        }
        
        // Обновление tracked data
        setNpcName(npcName);
        setRole(role);
    }
    
    private void saveAIModulesToNbt(NbtCompound aiData) {
        // TODO: Реализовать сохранение состояния AI модулей
    }
    
    private void loadAIModulesFromNbt(NbtCompound aiData) {
        // TODO: Реализовать загрузку состояния AI модулей
    }
    
    public void saveToMemory() {
        if (memory != null) {
            memory.saveToFile(this);
        }
    }
    
    public void loadFromMemory() {
        if (memory != null) {
            memory.loadFromFile();
        }
    }
    
    // Атрибуты существа
    public static DefaultAttributeContainer.Builder createNPCAttributes() {
        return createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.25)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 35.0)
                .add(EntityAttributes.GENERIC_ARMOR, 0.0)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0)
                .add(EntityAttributes.GENERIC_ATTACK_KNOCKBACK, 0.0);
    }
    
    @Override
    protected Text getDefaultName() {
        return Text.literal(npcName);
    }
    
    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false; // NPC не должны исчезать
    }
    
    @Override
    public boolean isPersistent() {
        return true; // NPC должны сохраняться
    }
}