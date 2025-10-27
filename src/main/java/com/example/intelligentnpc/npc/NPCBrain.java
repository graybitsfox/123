
package com.example.intelligentnpc.npc;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.network.LLMClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class NPCBrain {
    private final NPCEntity npc;
    private final NPCMemory memory;
    private final LLMClient llmClient;
    
    // Текущее состояние мозга
    private String currentGoal = "idle";
    private String currentMood = "neutral";
    private double energy = 1.0;
    private double motivation = 0.8;
    private final Map<String, Double> emotions = new ConcurrentHashMap<>();
    
    // Очередь задач и команд
    private final Queue<BrainTask> taskQueue = new LinkedList<>();
    private BrainTask currentTask;
    private final Map<String, Object> currentContext = new ConcurrentHashMap<>();
    
    // Настройки поведения
    private static final int MAX_TASK_QUEUE_SIZE = 10;
    private static final double ENERGY_DECAY_RATE = 0.001;
    private static final double ENERGY_RECOVERY_RATE = 0.002;
    
    public NPCBrain(NPCEntity npc, NPCMemory memory) {
        this.npc = npc;
        this.memory = memory;
        this.llmClient = new LLMClient();
        
        initializeEmotions();
        loadTasksFromMemory();
        if (taskQueue.isEmpty()) {
            initializeDefaultBehavior();
        }
    }

    private void loadTasksFromMemory() {
        List<String> savedTasks = memory.getTaskQueue();
        for (String taskType : savedTasks) {
            // This is a simplified version. A real implementation would need a factory
            // to reconstruct task objects from their type and parameters.
            if ("rest".equals(taskType)) addTask(new RestTask());
            if ("explore".equals(taskType)) addTask(new ExploreTask());
        }
    }

    public void saveTasksToMemory() {
        List<String> tasksToSave = new ArrayList<>();
        for (BrainTask task : taskQueue) {
            tasksToSave.add(task.getType());
        }
        if (currentTask != null) {
            tasksToSave.add(currentTask.getType());
        }
        memory.setTaskQueue(tasksToSave);
    }
    
    private void initializeEmotions() {
        emotions.put("happiness", 0.5);
        emotions.put("sadness", 0.2);
        emotions.put("anger", 0.1);
        emotions.put("fear", 0.3);
        emotions.put("curiosity", 0.7);
        emotions.put("confidence", 0.6);
    }
    
    private void initializeDefaultBehavior() {
        // Базовое поведение при старте
        setGoal("explore_surroundings");
        setMood("curious");
    }
    
    public void tick() {
        // Обновление энергии и эмоций
        updateEnergyAndEmotions();
        
        // Обработка текущей задачи
        if (currentTask != null) {
            processCurrentTask();
        } else if (!taskQueue.isEmpty()) {
            currentTask = taskQueue.poll();
        }
        
        // Периодическая проверка окружения
        if (npc.age % 40 == 0) { // Каждые 2 секунды
            analyzeEnvironment();
        }
        
        // Обновление контекста для LLM
        updateContext();
    }
    
    private void updateEnergyAndEmotions() {
        // Снижение энергии со временем
        energy = Math.max(0.0, energy - ENERGY_DECAY_RATE);
        
        // Восстановление энергии при отдыхе
        if (currentGoal.equals("rest") || currentGoal.equals("idle")) {
            energy = Math.min(1.0, energy + ENERGY_RECOVERY_RATE);
        }
        
        // Обновление эмоций на основе событий
        updateEmotionalState();
        
        // Влияние энергии на мотивацию
        motivation = 0.5 + (energy * 0.5);
    }
    
    private void updateEmotionalState() {
        // Логика обновления эмоций на основе последних событий
        List<NPCMemory.MemoryEntry> recentMemories = memory.getRecentExperiences(5);
        
        for (NPCMemory.MemoryEntry memory : recentMemories) {
            adjustEmotionsBasedOnMemory(memory);
        }
        
        // Естественное затухание эмоций
        emotions.replaceAll((emotion, value) -> Math.max(0.0, value * 0.99));
    }
    
    private void adjustEmotionsBasedOnMemory(NPCMemory.MemoryEntry memory) {
        String category = memory.getCategory();
        String description = memory.getDescription().toLowerCase();
        
        switch (category) {
            case "player_interaction" -> {
                if (description.contains("positive") || description.contains("help")) {
                    adjustEmotion("happiness", 0.1);
                    adjustEmotion("confidence", 0.05);
                } else if (description.contains("negative") || description.contains("attack")) {
                    adjustEmotion("fear", 0.1);
                    adjustEmotion("anger", 0.05);
                }
            }
            case "building" -> {
                if (description.contains("completed")) {
                    adjustEmotion("happiness", 0.15);
                    adjustEmotion("confidence", 0.1);
                } else if (description.contains("failed")) {
                    adjustEmotion("sadness", 0.1);
                }
            }
            case "survival" -> {
                if (description.contains("survived")) {
                    adjustEmotion("confidence", 0.1);
                } else if (description.contains("danger")) {
                    adjustEmotion("fear", 0.15);
                }
            }
            case "trading" -> {
                adjustEmotion("happiness", 0.05);
                adjustEmotion("confidence", 0.05);
            }
            case "learning" -> {
                adjustEmotion("curiosity", 0.1);
                adjustEmotion("happiness", 0.05);
            }
        }
    }
    
    public void adjustEmotion(String emotion, double amount) {
        double currentValue = emotions.getOrDefault(emotion, 0.5);
        emotions.put(emotion, Math.max(0.0, Math.min(1.0, currentValue + amount)));
    }
    
    private void processCurrentTask() {
        if (currentTask == null) return;
        
        try {
            boolean completed = currentTask.execute();
            if (completed || currentTask.isExpired()) {
                memory.addExperience("task_completion", 
                    String.format("Task '%s' completed: %s", currentTask.getType(), completed));
                currentTask = null;
            }
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("Error executing task for NPC {}: {}", 
                npc.getNpcName(), e.getMessage());
            currentTask = null;
        }
    }
    
    private void analyzeEnvironment() {
        // Анализ ближайших игроков
        List<PlayerEntity> nearbyPlayers = npc.getWorld().getEntitiesByClass(
            PlayerEntity.class, npc.getBoundingBox().expand(10.0), p -> true);
        
        for (PlayerEntity player : nearbyPlayers) {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                analyzePlayer(serverPlayer);
            }
        }
        
        // Анализ опасностей
        analyzeDangers();
        
        // Анализ возможностей
        analyzeOpportunities();
    }
    
    private void analyzePlayer(ServerPlayerEntity player) {
        String playerName = player.getName().getString();
        NPCMemory.PlayerRelationship relationship = memory.getPlayerRelationship(playerName);
        
        if (relationship == null) {
            // Новый игрок - проявляем любопытство
            adjustEmotion("curiosity", 0.1);
            memory.recordPlayerInteraction(player, "first_encounter", "Met for the first time");
            
            // Добавляем задачу приветствия
            addTask(new GreetingTask(player));
        } else {
            // Знакомый игрок - обновляем отношения
            relationship.updateLastSeen();
            
            // Реакция в зависимости от отношений
            double relationshipLevel = relationship.getRelationshipLevel();
            if (relationshipLevel > 0.5) {
                adjustEmotion("happiness", 0.05);
            } else if (relationshipLevel < -0.5) {
                adjustEmotion("fear", 0.05);
            }
        }
    }
    
    private void analyzeDangers() {
        // Проверка на лаву
        BlockPos pos = npc.getBlockPos();
        if (npc.getWorld().getBlockState(pos.down()).getBlock().toString().contains("lava")) {
            adjustEmotion("fear", 0.3);
            addTask(new AvoidDangerTask("lava", pos));
        }
        
        // Проверка на высоту
        if (pos.getY() > 100 && !npc.isOnGround()) {
            adjustEmotion("fear", 0.2);
            addTask(new AvoidDangerTask("height", pos));
        }
        
        // Проверка времени суток
        long timeOfDay = npc.getWorld().getTimeOfDay() % 24000;
        if (timeOfDay > 13000 && timeOfDay < 23000) { // Ночь
            adjustEmotion("fear", 0.05);
            if (currentGoal.equals("explore_surroundings")) {
                setGoal("find_shelter");
            }
        }
    }
    
    private void analyzeOpportunities() {
        // Анализ блоков для строительства
        // Анализ ресурсов для торговли
        // Анализ возможностей для обучения
        
        // Пример: поиск ресурсов
        if (memory.getSkillLevel("building") > 3 && currentGoal.equals("idle")) {
            setGoal("gather_resources");
            addTask(new GatherResourcesTask());
        }
    }
    
    private void updateContext() {
        currentContext.put("current_goal", currentGoal);
        currentContext.put("current_mood", currentMood);
        currentContext.put("energy", energy);
        currentContext.put("motivation", motivation);
        currentContext.put("dominant_emotion", getDominantEmotion());
    }
    
    private String getDominantEmotion() {
        return emotions.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("neutral");
    }
    
    // Интеграция с LLM
    public void processLLMContext(JsonObject context, Consumer<JsonObject> responseHandler) {
        // Добавляем внутреннее состояние к контексту
        JsonObject brainState = new JsonObject();
        brainState.addProperty("current_goal", currentGoal);
        brainState.addProperty("current_mood", currentMood);
        brainState.addProperty("energy", energy);
        brainState.addProperty("motivation", motivation);
        brainState.addProperty("dominant_emotion", getDominantEmotion());
        
        JsonObject emotionsJson = new JsonObject();
        emotions.forEach(emotionsJson::addProperty);
        brainState.add("emotions", emotionsJson);
        
        JsonArray taskQueueJson = new JsonArray();
        taskQueue.forEach(task -> taskQueueJson.add(task.getType()));
        brainState.add("task_queue", taskQueueJson);
        
        context.add("brain_state", brainState);
        
        // Асинхронный запрос к LLM
        llmClient.sendRequest(context)
            .thenAccept(responseHandler)
            .exceptionally(throwable -> {
                IntelligentNPCMod.LOGGER.error("LLM request failed for NPC {}: {}",
                    npc.getNpcName(), throwable.getMessage());
                return null;
            });
    }
    
    public void processLLMResponse(JsonObject response) {
        try {
            // Обработка команд от LLM
            if (response.has("commands")) {
                JsonArray commands = response.getAsJsonArray("commands");
                processLLMCommands(commands);
            }
            
            // Обновление эмоционального состояния
            if (response.has("emotions")) {
                JsonObject emotionUpdates = response.getAsJsonObject("emotions");
                emotionUpdates.entrySet().forEach(entry -> {
                    adjustEmotion(entry.getKey(), entry.getValue().getAsDouble());
                });
            }
            
            // Обновление целей
            if (response.has("goal")) {
                setGoal(response.get("goal").getAsString());
            }
            
            // Обновление настроения
            if (response.has("mood")) {
                setMood(response.get("mood").getAsString());
            }
            
            // Речь NPC
            if (response.has("speech")) {
                String speech = response.get("speech").getAsString();
                npc.getWorld().getPlayers().forEach(player -> {
                    if (npc.squaredDistanceTo(player) < 100) { // В радиусе 10 блоков
                        player.sendMessage(Text.literal("<" + npc.getNpcName() + "> " + speech), false);
                    }
                });
                memory.addExperience("speech", "Said: " + speech);
            }
            
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("Error processing LLM response for NPC {}: {}", 
                npc.getNpcName(), e.getMessage());
        }
    }
    
    private void processLLMCommands(JsonArray commands) {
        commands.forEach(commandElement -> {
            JsonObject command = commandElement.getAsJsonObject();
            String action = command.get("action").getAsString();
            
            switch (action) {
                case "move_to" -> {
                    if (command.has("x") && command.has("y") && command.has("z")) {
                        BlockPos target = new BlockPos(
                            command.get("x").getAsInt(),
                            command.get("y").getAsInt(),
                            command.get("z").getAsInt()
                        );
                        addTask(new MoveToTask(target));
                    }
                }
                case "build" -> {
                    if (command.has("structure") && command.has("location")) {
                        String structure = command.get("structure").getAsString();
                        JsonObject loc = command.getAsJsonObject("location");
                        BlockPos location = new BlockPos(
                            loc.get("x").getAsInt(),
                            loc.get("y").getAsInt(),
                            loc.get("z").getAsInt()
                        );
                        addTask(new BuildTask(structure, location));
                    }
                }
                case "trade" -> {
                    if (command.has("partner")) {
                        String partner = command.get("partner").getAsString();
                        addTask(new TradeTask(partner));
                    }
                }
                case "learn" -> {
                    if (command.has("skill")) {
                        String skill = command.get("skill").getAsString();
                        addTask(new LearningTask(skill));
                    }
                }
                case "rest" -> {
                    setGoal("rest");
                    addTask(new RestTask());
                }
                case "explore" -> {
                    setGoal("explore");
                    addTask(new ExploreTask());
                }
            }
        });
    }
    
    // Управление задачами
    public void addTask(BrainTask task) {
        if (taskQueue.size() < MAX_TASK_QUEUE_SIZE) {
            taskQueue.offer(task);
        }
    }
    
    public void clearTasks() {
        taskQueue.clear();
        currentTask = null;
    }
    
    public void setHighPriorityTask(BrainTask task) {
        clearTasks();
        currentTask = task;
    }
    
    // Геттеры и сеттеры
    public String getCurrentGoal() { return currentGoal; }
    public void setGoal(String goal) { 
        this.currentGoal = goal;
        npc.setCurrentTask(goal);
        memory.addExperience("goal_change", "Changed goal to: " + goal);
    }
    
    public String getCurrentMood() { return currentMood; }
    public void setMood(String mood) { 
        this.currentMood = mood;
        memory.addExperience("mood_change", "Mood changed to: " + mood);
    }
    
    public double getEnergy() { return energy; }
    public void setEnergy(double energy) { this.energy = Math.max(0.0, Math.min(1.0, energy)); }
    
    public double getMotivation() { return motivation; }
    public Map<String, Double> getEmotions() { return new HashMap<>(emotions); }
    public double getEmotion(String emotion) { return emotions.getOrDefault(emotion, 0.5); }
    
    // Обработка событий
    public void onPlayerInteraction(PlayerEntity player, String interactionType) {
        memory.recordPlayerInteraction(player, interactionType, "Direct interaction");
        
        // Эмоциональная реакция
        NPCMemory.PlayerRelationship relationship = memory.getPlayerRelationship(player.getName().getString());
        if (relationship != null) {
            double relationshipLevel = relationship.getRelationshipLevel();
            if (relationshipLevel > 0) {
                adjustEmotion("happiness", 0.1);
            } else if (relationshipLevel < 0) {
                adjustEmotion("fear", 0.05);
            }
        }
        
        // Добавление соответствующей задачи
        switch (interactionType) {
            case "greeting" -> addTask(new GreetingTask(player));
            case "trade_request" -> addTask(new TradeTask(player.getName().getString()));
            case "help_request" -> addTask(new HelpTask(player));
        }
    }
    
    public void onDangerDetected(String dangerType, BlockPos location) {
        adjustEmotion("fear", 0.2);
        memory.recordDangerEncounter(dangerType, location, "detected", true);
        addTask(new AvoidDangerTask(dangerType, location));
    }
    
    public void onTaskCompleted(String taskType, boolean success) {
        if (success) {
            adjustEmotion("happiness", 0.1);
            adjustEmotion("confidence", 0.05);
        } else {
            adjustEmotion("sadness", 0.1);
        }
        memory.addExperience("task_result", String.format("Task %s: %s", taskType, success ? "success" : "failure"));
    }
    
    // Внутренние классы задач
    public abstract static class BrainTask {
        protected final long creationTime;
        protected final long maxDuration;
        protected final String type;
        
        public BrainTask(String type, long maxDuration) {
            this.type = type;
            this.creationTime = System.currentTimeMillis();
            this.maxDuration = maxDuration;
        }
        
        public abstract boolean execute();
        
        public boolean isExpired() {
            return (System.currentTimeMillis() - creationTime) > maxDuration;
        }
        
        public String getType() { return type; }
    }
    
    public static class GreetingTask extends BrainTask {
        private final PlayerEntity target;
        
        public GreetingTask(PlayerEntity target) {
            super("greeting", 10000); // 10 секунд
            this.target = target;
        }
        
        @Override
        public boolean execute() {
            // Логика приветствия будет реализована в ChatAI
            return true;
        }
    }
    
    public static class MoveToTask extends BrainTask {
        private final BlockPos target;
        
        public MoveToTask(BlockPos target) {
            super("move_to", 30000); // 30 секунд
            this.target = target;
        }
        
        @Override
        public boolean execute() {
            // Логика движения будет реализована через навигацию
            return true;
        }
    }
    
    public static class BuildTask extends BrainTask {
        private final String structure;
        private final BlockPos location;
        
        public BuildTask(String structure, BlockPos location) {
            super("build", 300000); // 5 минут
            this.structure = structure;
            this.location = location;
        }
        
        @Override
        public boolean execute() {
            // Логика строительства будет реализована в BuilderAI
            return true;
        }
    }
    
    public static class TradeTask extends BrainTask {
        private final String partner;
        
        public TradeTask(String partner) {
            super("trade", 60000); // 1 минута
            this.partner = partner;
        }
        
        @Override
        public boolean execute() {
            // Логика торговли будет реализована в TradeAI
            return true;
        }
    }
    
    public static class LearningTask extends BrainTask {
        private final String skill;
        
        public LearningTask(String skill) {
            super("learning", 120000); // 2 минуты
            this.skill = skill;
        }
        
        @Override
        public boolean execute() {
            // Логика обучения будет реализована в LearningAI
            return true;
        }
    }
    
    public static class RestTask extends BrainTask {
        public RestTask() {
            super("rest", 60000); // 1 минута
        }
        
        @Override
        public boolean execute() {
            // Логика отдыха
            return true;
        }
    }
    
    public static class ExploreTask extends BrainTask {
        public ExploreTask() {
            super("explore", 120000); // 2 минуты
        }
        
        @Override
        public boolean execute() {
            // Логика исследования
            return true;
        }
    }
    
    public static class AvoidDangerTask extends BrainTask {
        private final String dangerType;
        private final BlockPos dangerLocation;
        
        public AvoidDangerTask(String dangerType, BlockPos dangerLocation) {
            super("avoid_danger", 30000); // 30 секунд
            this.dangerType = dangerType;
            this.dangerLocation = dangerLocation;
        }
        
        @Override
        public boolean execute() {
            // Логика избегания опасности будет реализована в SurvivalAI
            return true;
        }
    }
    
    public static class GatherResourcesTask extends BrainTask {
        public GatherResourcesTask() {
            super("gather_resources", 300000); // 5 минут
        }
        
        @Override
        public boolean execute() {
            // Логика сбора ресурсов
            return true;
        }
    }
    
    public static class HelpTask extends BrainTask {
        private final PlayerEntity player;
        
        public HelpTask(PlayerEntity player) {
            super("help", 120000); // 2 минуты
            this.player = player;
        }
        
        @Override
        public boolean execute() {
            // Логика помощи игроку
            return true;
        }
    }
}