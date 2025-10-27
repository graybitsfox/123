package com.example.intelligentnpc.npc;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.google.gson.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class NPCMemory {
    private final String npcName;
    private final File memoryFile;
    private final Gson gson;
    
    // Основные категории памяти
    private final Map<String, PlayerRelationship> knownPlayers = new ConcurrentHashMap<>();
    private final List<MemoryEntry> experiences = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, BuildingProject> buildingProjects = new ConcurrentHashMap<>();
    private final List<TradeRecord> tradeHistory = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> skillLevels = new ConcurrentHashMap<>();
    private final List<String> learnedPatterns = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> personalityTraits = new ConcurrentHashMap<>();
    private final List<DangerRecord> dangerEncounters = Collections.synchronizedList(new ArrayList<>());
    private final List<String> taskQueue = Collections.synchronizedList(new ArrayList<>());
    
    // Настройки памяти
    private static final int MAX_EXPERIENCES = 1000;
    private static final int MAX_TRADE_HISTORY = 500;
    private static final int MAX_DANGER_RECORDS = 200;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    public NPCMemory(String npcName) {
        this.npcName = npcName;
        this.memoryFile = IntelligentNPCMod.getMemoryFile(npcName);
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .enableComplexMapKeySerialization()
                .create();
        
        initializeDefaultMemory();
    }
    
    private void initializeDefaultMemory() {
        // Инициализация базовых навыков
        skillLevels.put("building", 1);
        skillLevels.put("trading", 1);
        skillLevels.put("combat", 1);
        skillLevels.put("survival", 1);
        skillLevels.put("communication", 1);
        skillLevels.put("learning", 1);
        
        // Базовые черты личности
        personalityTraits.put("friendliness", 0.5);
        personalityTraits.put("curiosity", 0.7);
        personalityTraits.put("cautiousness", 0.6);
        personalityTraits.put("humor", 0.4);
        personalityTraits.put("patience", 0.5);
        personalityTraits.put("creativity", 0.6);
    }
    
    // Работа с игроками
    public void recordPlayerInteraction(PlayerEntity player, String interaction, String context) {
        String playerName = player.getName().getString();
        UUID playerId = player.getUuid();
        
        PlayerRelationship relationship = knownPlayers.computeIfAbsent(playerName, 
            k -> new PlayerRelationship(playerName, playerId));
        
        relationship.addInteraction(interaction, context);
        relationship.updateLastSeen();
        
        // Добавляем в общий список опыта
        addExperience("player_interaction", 
            String.format("Interacted with %s: %s (%s)", playerName, interaction, context));
        
        IntelligentNPCMod.LOGGER.debug("NPC {} recorded interaction with player {}: {}", 
            npcName, playerName, interaction);
    }
    
    public PlayerRelationship getPlayerRelationship(String playerName) {
        return knownPlayers.get(playerName);
    }
    
    public void updatePlayerRelationship(String playerName, double relationshipChange) {
        PlayerRelationship relationship = knownPlayers.get(playerName);
        if (relationship != null) {
            relationship.adjustRelationship(relationshipChange);
            addExperience("relationship_change", 
                String.format("Relationship with %s changed by %.2f (now %.2f)", 
                    playerName, relationshipChange, relationship.getRelationshipLevel()));
        }
    }
    
    // Общий опыт и события
    public void addExperience(String category, String description) {
        MemoryEntry entry = new MemoryEntry(category, description, LocalDateTime.now());
        experiences.add(entry);
        
        // Ограничиваем размер памяти
        while (experiences.size() > MAX_EXPERIENCES) {
            experiences.remove(0);
        }
    }
    
    public List<MemoryEntry> getRecentExperiences(int count) {
        int size = experiences.size();
        int fromIndex = Math.max(0, size - count);
        return new ArrayList<>(experiences.subList(fromIndex, size));
    }
    
    public JsonObject getRecentMemoriesAsJson() {
        JsonObject memories = new JsonObject();
        
        // Последние 10 опытов
        JsonArray recentExperiences = new JsonArray();
        getRecentExperiences(10).forEach(exp -> {
            JsonObject expObj = new JsonObject();
            expObj.addProperty("category", exp.category);
            expObj.addProperty("description", exp.description);
            expObj.addProperty("time", exp.timestamp.format(TIME_FORMAT));
            recentExperiences.add(expObj);
        });
        memories.add("recent_experiences", recentExperiences);
        
        // Известные игроки
        JsonObject players = new JsonObject();
        knownPlayers.forEach((name, rel) -> {
            JsonObject playerData = new JsonObject();
            playerData.addProperty("relationship_level", rel.getRelationshipLevel());
            playerData.addProperty("interactions_count", rel.getInteractionCount());
            playerData.addProperty("last_seen", rel.getLastSeen().format(TIME_FORMAT));
            players.add(name, playerData);
        });
        memories.add("known_players", players);
        
        // Навыки
        JsonObject skills = new JsonObject();
        skillLevels.forEach(skills::addProperty);
        memories.add("skills", skills);
        
        return memories;
    }
    
    // Строительные проекты
    public void recordBuildingProject(String projectName, BlockPos location, String description, String materials) {
        BuildingProject project = new BuildingProject(projectName, location, description, materials);
        buildingProjects.put(projectName, project);
        addExperience("building", String.format("Started building project '%s' at %s", projectName, location));
        increaseSkill("building", 1);
    }
    
    public void completeBuildingProject(String projectName) {
        BuildingProject project = buildingProjects.get(projectName);
        if (project != null) {
            project.markCompleted();
            addExperience("building", String.format("Completed building project '%s'", projectName));
            increaseSkill("building", 3);
        }
    }
    
    public BuildingProject getBuildingProject(String projectName) {
        return buildingProjects.get(projectName);
    }
    
    public List<BuildingProject> getCompletedProjects() {
        return buildingProjects.values().stream()
            .filter(BuildingProject::isCompleted)
            .toList();
    }
    
    // Торговая история
    public void recordTrade(String partner, String itemsGiven, String itemsReceived, double value) {
        TradeRecord trade = new TradeRecord(partner, itemsGiven, itemsReceived, value);
        tradeHistory.add(trade);
        
        while (tradeHistory.size() > MAX_TRADE_HISTORY) {
            tradeHistory.remove(0);
        }
        
        addExperience("trading", String.format("Traded with %s: gave %s, received %s (value: %.2f)", 
            partner, itemsGiven, itemsReceived, value));
        increaseSkill("trading", 1);
    }
    
    public List<TradeRecord> getTradeHistory() {
        return new ArrayList<>(tradeHistory);
    }
    
    public double getAverageTradeValue() {
        return tradeHistory.stream()
            .mapToDouble(TradeRecord::getValue)
            .average()
            .orElse(0.0);
    }
    
    // Навыки и обучение
    public void increaseSkill(String skill, int amount) {
        int currentLevel = skillLevels.getOrDefault(skill, 1);
        skillLevels.put(skill, currentLevel + amount);
        addExperience("learning", String.format("Skill '%s' increased to level %d", skill, currentLevel + amount));
    }
    
    public int getSkillLevel(String skill) {
        return skillLevels.getOrDefault(skill, 1);
    }
    
    public void learnPattern(String pattern, String context) {
        String patternEntry = String.format("%s: %s", pattern, context);
        learnedPatterns.add(patternEntry);
        addExperience("learning", String.format("Learned new pattern: %s", pattern));
        increaseSkill("learning", 1);
    }
    
    public List<String> getLearnedPatterns() {
        return new ArrayList<>(learnedPatterns);
    }
    
    // Черты личности
    public void adjustPersonalityTrait(String trait, double adjustment) {
        double currentValue = (Double) personalityTraits.getOrDefault(trait, 0.5);
        double newValue = Math.max(0.0, Math.min(1.0, currentValue + adjustment));
        personalityTraits.put(trait, newValue);
    }
    
    public double getPersonalityTrait(String trait) {
        Object value = personalityTraits.get(trait);
        return value instanceof Number ? ((Number) value).doubleValue() : 0.5;
    }
    
    // Опасности и выживание
    public void recordDangerEncounter(String dangerType, BlockPos location, String response, boolean survived) {
        DangerRecord danger = new DangerRecord(dangerType, location, response, survived);
        dangerEncounters.add(danger);
        
        while (dangerEncounters.size() > MAX_DANGER_RECORDS) {
            dangerEncounters.remove(0);
        }
        
        addExperience("survival", String.format("Encountered %s at %s, responded with %s (survived: %s)", 
            dangerType, location, response, survived));
        
        if (survived) {
            increaseSkill("survival", 2);
        }
    }
    
    public List<DangerRecord> getDangerHistory() {
        return new ArrayList<>(dangerEncounters);
    }
    
    public List<DangerRecord> getDangersByType(String dangerType) {
        return dangerEncounters.stream()
            .filter(d -> d.getDangerType().equals(dangerType))
            .toList();
    }

    // Задачи
    public List<String> getTaskQueue() {
        return new ArrayList<>(taskQueue);
    }

    public void setTaskQueue(List<String> tasks) {
        taskQueue.clear();
        taskQueue.addAll(tasks);
    }
    
    // Сохранение и загрузка
    public void saveToFile(NPCEntity npc) {
        try {
            JsonObject memoryData = new JsonObject();
            
            // Основная информация
            memoryData.addProperty("npc_name", npcName);
            memoryData.addProperty("last_saved", LocalDateTime.now().format(TIME_FORMAT));

            // Добавляем данные о сущности
            memoryData.addProperty("uuid", npc.getUuid().toString());
            memoryData.addProperty("role", npc.getRole());
            if (npc.getOwnerId() != null) {
                memoryData.addProperty("owner_id", npc.getOwnerId().toString());
            }
            memoryData.addProperty("world", npc.getWorld().getRegistryKey().getValue().toString());
            memoryData.addProperty("x", npc.getX());
            memoryData.addProperty("y", npc.getY());
            memoryData.addProperty("z", npc.getZ());
            memoryData.addProperty("yaw", npc.getYaw());
            memoryData.addProperty("pitch", npc.getPitch());
            memoryData.addProperty("health", npc.getHealth());
            
            // Игроки
            JsonObject playersJson = new JsonObject();
            knownPlayers.forEach((name, rel) -> playersJson.add(name, rel.toJson()));
            memoryData.add("known_players", playersJson);
            
            // Опыт
            JsonArray experiencesJson = new JsonArray();
            experiences.forEach(exp -> experiencesJson.add(exp.toJson()));
            memoryData.add("experiences", experiencesJson);
            
            // Строительные проекты
            JsonObject projectsJson = new JsonObject();
            buildingProjects.forEach((name, project) -> projectsJson.add(name, project.toJson()));
            memoryData.add("building_projects", projectsJson);
            
            // Торговая история
            JsonArray tradesJson = new JsonArray();
            tradeHistory.forEach(trade -> tradesJson.add(trade.toJson()));
            memoryData.add("trade_history", tradesJson);
            
            // Навыки
            JsonObject skillsJson = new JsonObject();
            skillLevels.forEach(skillsJson::addProperty);
            memoryData.add("skills", skillsJson);
            
            // Изученные паттерны
            JsonArray patternsJson = new JsonArray();
            learnedPatterns.forEach(patternsJson::add);
            memoryData.add("learned_patterns", patternsJson);
            
            // Черты личности
            JsonObject traitsJson = new JsonObject();
            personalityTraits.forEach((trait, value) -> traitsJson.addProperty(trait, (Number) value));
            memoryData.add("personality_traits", traitsJson);
            
            // Опасности
            JsonArray dangersJson = new JsonArray();
            dangerEncounters.forEach(danger -> dangersJson.add(danger.toJson()));
            memoryData.add("danger_encounters", dangersJson);

            // Очередь задач
            JsonArray tasksJson = new JsonArray();
            taskQueue.forEach(tasksJson::add);
            memoryData.add("task_queue", tasksJson);
            
            // Запись в файл
            try (FileWriter writer = new FileWriter(memoryFile, StandardCharsets.UTF_8)) {
                gson.toJson(memoryData, writer);
            }
            
            IntelligentNPCMod.LOGGER.debug("Saved memory for NPC {} to {}", npcName, memoryFile.getPath());
            
        } catch (IOException e) {
            IntelligentNPCMod.LOGGER.error("Failed to save memory for NPC {}: {}", npcName, e.getMessage());
        }
    }
    
    public void loadFromFile() {
        if (!memoryFile.exists()) {
            IntelligentNPCMod.LOGGER.debug("No memory file found for NPC {}, using defaults", npcName);
            return;
        }
        
        try (FileReader reader = new FileReader(memoryFile, StandardCharsets.UTF_8)) {
            JsonObject memoryData = gson.fromJson(reader, JsonObject.class);
            
            if (memoryData == null) {
                IntelligentNPCMod.LOGGER.warn("Invalid memory file for NPC {}", npcName);
                return;
            }
            
            // Загрузка игроков
            if (memoryData.has("known_players")) {
                JsonObject playersJson = memoryData.getAsJsonObject("known_players");
                playersJson.entrySet().forEach(entry -> {
                    PlayerRelationship rel = PlayerRelationship.fromJson(entry.getValue().getAsJsonObject());
                    knownPlayers.put(entry.getKey(), rel);
                });
            }
            
            // Загрузка опыта
            if (memoryData.has("experiences")) {
                JsonArray experiencesJson = memoryData.getAsJsonArray("experiences");
                experiencesJson.forEach(elem -> {
                    MemoryEntry entry = MemoryEntry.fromJson(elem.getAsJsonObject());
                    experiences.add(entry);
                });
            }
            
            // Загрузка проектов
            if (memoryData.has("building_projects")) {
                JsonObject projectsJson = memoryData.getAsJsonObject("building_projects");
                projectsJson.entrySet().forEach(entry -> {
                    BuildingProject project = BuildingProject.fromJson(entry.getValue().getAsJsonObject());
                    buildingProjects.put(entry.getKey(), project);
                });
            }
            
            // Загрузка торговой истории
            if (memoryData.has("trade_history")) {
                JsonArray tradesJson = memoryData.getAsJsonArray("trade_history");
                tradesJson.forEach(elem -> {
                    TradeRecord trade = TradeRecord.fromJson(elem.getAsJsonObject());
                    tradeHistory.add(trade);
                });
            }
            
            // Загрузка навыков
            if (memoryData.has("skills")) {
                JsonObject skillsJson = memoryData.getAsJsonObject("skills");
                skillsJson.entrySet().forEach(entry -> {
                    skillLevels.put(entry.getKey(), entry.getValue().getAsInt());
                });
            }
            
            // Загрузка паттернов
            if (memoryData.has("learned_patterns")) {
                JsonArray patternsJson = memoryData.getAsJsonArray("learned_patterns");
                patternsJson.forEach(elem -> learnedPatterns.add(elem.getAsString()));
            }
            
            // Загрузка черт личности
            if (memoryData.has("personality_traits")) {
                JsonObject traitsJson = memoryData.getAsJsonObject("personality_traits");
                traitsJson.entrySet().forEach(entry -> {
                    personalityTraits.put(entry.getKey(), entry.getValue().getAsDouble());
                });
            }
            
            // Загрузка опасностей
            if (memoryData.has("danger_encounters")) {
                JsonArray dangersJson = memoryData.getAsJsonArray("danger_encounters");
                dangersJson.forEach(elem -> {
                    DangerRecord danger = DangerRecord.fromJson(elem.getAsJsonObject());
                    dangerEncounters.add(danger);
                });
            }

            // Загрузка очереди задач
            if (memoryData.has("task_queue")) {
                JsonArray tasksJson = memoryData.getAsJsonArray("task_queue");
                tasksJson.forEach(elem -> taskQueue.add(elem.getAsString()));
            }
            
            IntelligentNPCMod.LOGGER.debug("Loaded memory for NPC {} from {}", npcName, memoryFile.getPath());
            
        } catch (IOException | JsonSyntaxException e) {
            IntelligentNPCMod.LOGGER.error("Failed to load memory for NPC {}: {}", npcName, e.getMessage());
        }
    }

    public static JsonObject loadNpcData(File memoryFile) {
        if (!memoryFile.exists()) {
            return null;
        }
        try (FileReader reader = new FileReader(memoryFile, StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, JsonObject.class);
        } catch (IOException | JsonSyntaxException e) {
            IntelligentNPCMod.LOGGER.error("Failed to load NPC data from {}: {}", memoryFile.getName(), e.getMessage());
            return null;
        }
    }
    
    // Вспомогательные классы
    public static class PlayerRelationship {
        private final String playerName;
        private final UUID playerId;
        private double relationshipLevel = 0.0; // -1.0 to 1.0
        private int interactionCount = 0;
        private LocalDateTime lastSeen;
        private final List<String> interactions = new ArrayList<>();
        
        public PlayerRelationship(String playerName, UUID playerId) {
            this.playerName = playerName;
            this.playerId = playerId;
            this.lastSeen = LocalDateTime.now();
        }
        
        public void addInteraction(String interaction, String context) {
            interactions.add(String.format("[%s] %s: %s", 
                LocalDateTime.now().format(TIME_FORMAT), interaction, context));
            interactionCount++;
            
            // Ограничиваем количество сохраненных взаимодействий
            while (interactions.size() > 50) {
                interactions.remove(0);
            }
        }
        
        public void adjustRelationship(double change) {
            relationshipLevel = Math.max(-1.0, Math.min(1.0, relationshipLevel + change));
        }
        
        public void updateLastSeen() {
            lastSeen = LocalDateTime.now();
        }
        
        // Геттеры
        public String getPlayerName() { return playerName; }
        public UUID getPlayerId() { return playerId; }
        public double getRelationshipLevel() { return relationshipLevel; }
        public int getInteractionCount() { return interactionCount; }
        public LocalDateTime getLastSeen() { return lastSeen; }
        public List<String> getInteractions() { return new ArrayList<>(interactions); }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("player_name", playerName);
            json.addProperty("player_id", playerId.toString());
            json.addProperty("relationship_level", relationshipLevel);
            json.addProperty("interaction_count", interactionCount);
            json.addProperty("last_seen", lastSeen.format(TIME_FORMAT));
            
            JsonArray interactionsJson = new JsonArray();
            interactions.forEach(interactionsJson::add);
            json.add("interactions", interactionsJson);
            
            return json;
        }
        
        public static PlayerRelationship fromJson(JsonObject json) {
            String name = json.get("player_name").getAsString();
            UUID id = UUID.fromString(json.get("player_id").getAsString());
            
            PlayerRelationship rel = new PlayerRelationship(name, id);
            rel.relationshipLevel = json.get("relationship_level").getAsDouble();
            rel.interactionCount = json.get("interaction_count").getAsInt();
            rel.lastSeen = LocalDateTime.parse(json.get("last_seen").getAsString(), TIME_FORMAT);
            
            if (json.has("interactions")) {
                JsonArray interactionsJson = json.getAsJsonArray("interactions");
                interactionsJson.forEach(elem -> rel.interactions.add(elem.getAsString()));
            }
            
            return rel;
        }
    }
    
    public static class MemoryEntry {
        private final String category;
        private final String description;
        private final LocalDateTime timestamp;
        
        public MemoryEntry(String category, String description, LocalDateTime timestamp) {
            this.category = category;
            this.description = description;
            this.timestamp = timestamp;
        }
        
        public String getCategory() { return category; }
        public String getDescription() { return description; }
        public LocalDateTime getTimestamp() { return timestamp; }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("category", category);
            json.addProperty("description", description);
            json.addProperty("timestamp", timestamp.format(TIME_FORMAT));
            return json;
        }
        
        public static MemoryEntry fromJson(JsonObject json) {
            return new MemoryEntry(
                json.get("category").getAsString(),
                json.get("description").getAsString(),
                LocalDateTime.parse(json.get("timestamp").getAsString(), TIME_FORMAT)
            );
        }
    }
    
    public static class BuildingProject {
        private final String name;
        private final BlockPos location;
        private final String description;
        private final String materials;
        private final LocalDateTime startTime;
        private LocalDateTime completedTime;
        private boolean completed = false;
        
        public BuildingProject(String name, BlockPos location, String description, String materials) {
            this.name = name;
            this.location = location;
            this.description = description;
            this.materials = materials;
            this.startTime = LocalDateTime.now();
        }
        
        public void markCompleted() {
            this.completed = true;
            this.completedTime = LocalDateTime.now();
        }
        
        // Геттеры
        public String getName() { return name; }
        public BlockPos getLocation() { return location; }
        public String getDescription() { return description; }
        public String getMaterials() { return materials; }
        public LocalDateTime getStartTime() { return startTime; }
        public LocalDateTime getCompletedTime() { return completedTime; }
        public boolean isCompleted() { return completed; }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("name", name);
            json.addProperty("location_x", location.getX());
            json.addProperty("location_y", location.getY());
            json.addProperty("location_z", location.getZ());
            json.addProperty("description", description);
            json.addProperty("materials", materials);
            json.addProperty("start_time", startTime.format(TIME_FORMAT));
            json.addProperty("completed", completed);
            if (completedTime != null) {
                json.addProperty("completed_time", completedTime.format(TIME_FORMAT));
            }
            return json;
        }
        
        public static BuildingProject fromJson(JsonObject json) {
            BlockPos location = new BlockPos(
                json.get("location_x").getAsInt(),
                json.get("location_y").getAsInt(),
                json.get("location_z").getAsInt()
            );
            
            BuildingProject project = new BuildingProject(
                json.get("name").getAsString(),
                location,
                json.get("description").getAsString(),
                json.get("materials").getAsString()
            );
            
            project.completed = json.get("completed").getAsBoolean();
            if (json.has("completed_time") && project.completed) {
                project.completedTime = LocalDateTime.parse(json.get("completed_time").getAsString(), TIME_FORMAT);
            }
            
            return project;
        }
    }
    
    public static class TradeRecord {
        private final String partner;
        private final String itemsGiven;
        private final String itemsReceived;
        private final double value;
        private final LocalDateTime timestamp;
        
        public TradeRecord(String partner, String itemsGiven, String itemsReceived, double value) {
            this.partner = partner;
            this.itemsGiven = itemsGiven;
            this.itemsReceived = itemsReceived;
            this.value = value;
            this.timestamp = LocalDateTime.now();
        }
        
        // Геттеры
        public String getPartner() { return partner; }
        public String getItemsGiven() { return itemsGiven; }
        public String getItemsReceived() { return itemsReceived; }
        public double getValue() { return value; }
        public LocalDateTime getTimestamp() { return timestamp; }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("partner", partner);
            json.addProperty("items_given", itemsGiven);
            json.addProperty("items_received", itemsReceived);
            json.addProperty("value", value);
            json.addProperty("timestamp", timestamp.format(TIME_FORMAT));
            return json;
        }
        
        public static TradeRecord fromJson(JsonObject json) {
            return new TradeRecord(
                json.get("partner").getAsString(),
                json.get("items_given").getAsString(),
                json.get("items_received").getAsString(),
                json.get("value").getAsDouble()
            );
        }
    }
    
    public static class DangerRecord {
        private final String dangerType;
        private final BlockPos location;
        private final String response;
        private final boolean survived;
        private final LocalDateTime timestamp;
        
        public DangerRecord(String dangerType, BlockPos location, String response, boolean survived) {
            this.dangerType = dangerType;
            this.location = location;
            this.response = response;
            this.survived = survived;
            this.timestamp = LocalDateTime.now();
        }
        
        // Геттеры
        public String getDangerType() { return dangerType; }
        public BlockPos getLocation() { return location; }
        public String getResponse() { return response; }
        public boolean isSurvived() { return survived; }
        public LocalDateTime getTimestamp() { return timestamp; }
        
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("danger_type", dangerType);
            json.addProperty("location_x", location.getX());
            json.addProperty("location_y", location.getY());
            json.addProperty("location_z", location.getZ());
            json.addProperty("response", response);
            json.addProperty("survived", survived);
            json.addProperty("timestamp", timestamp.format(TIME_FORMAT));
            return json;
        }
        
        public static DangerRecord fromJson(JsonObject json) {
            BlockPos location = new BlockPos(
                json.get("location_x").getAsInt(),
                json.get("location_y").getAsInt(),
                json.get("location_z").getAsInt()
            );
            
            return new DangerRecord(
                json.get("danger_type").getAsString(),
                location,
                json.get("response").getAsString(),
                json.get("survived").getAsBoolean()
            );
        }
    }
}