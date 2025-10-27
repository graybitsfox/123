package com.example.intelligentnpc.npc.ai;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCMemory;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LearningAI {
    private final NPCEntity npc;
    private final NPCMemory memory;
    private final World world;

    // Обучающие данные и паттерны
    private final Map<String, ObservedPattern> observedPatterns = new ConcurrentHashMap<>();
    private final Map<String, PlayerBehavior> playerBehaviors = new ConcurrentHashMap<>();
    private final Set<String> learnedSkills = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> actionSuccessRate = new ConcurrentHashMap<>();

    // Настройки обучения
    private double learningRate = 0.1; // Скорость обучения
    private int observationRadius = 10; // Радиус наблюдения
    private boolean enableImitation = true; // Включено ли подражание
    private boolean enableExperimentation = true; // Включены ли эксперименты
    private int maxPatternsToStore = 100; // Максимум сохраненных паттернов

    // Текущее состояние обучения
    private String currentLearningFocus = "general"; // На что сейчас фокусируемся
    private PlayerEntity currentTeacher; // Текущий "учитель"
    private long lastLearningEvent = 0;

    // Статистика обучения
    private int totalObservations = 0;
    private int patternsLearned = 0;
    private int successfulImitations = 0;
    private int failedImitations = 0;

    public LearningAI(NPCEntity npc) {
        this.npc = npc;
        this.memory = npc.getMemory();
        this.world = npc.getWorld();

        loadLearningDataFromMemory();
        initializeLearningFocus();
    }

    public void registerGoals(GoalSelector goalSelector) {
        goalSelector.add(7, new ObservePlayerGoal());
        goalSelector.add(8, new ImitateActionGoal());
        goalSelector.add(9, new ExperimentGoal());
    }

    private void loadLearningDataFromMemory() {
        // Загрузка изученных паттернов из памяти
        List<String> learnedPatternsList = memory.getLearnedPatterns();
        for (String pattern : learnedPatternsList) {
            String[] parts = pattern.split(": ", 2);
            if (parts.length == 2) {
                observedPatterns.put(parts[0], new ObservedPattern(parts[0], parts[1], 1));
            }
        }

        // Настройка скорости обучения на основе навыка
        int learningSkill = memory.getSkillLevel("learning");
        learningRate = 0.05 + (learningSkill * 0.01); // 5% базовая + 1% за уровень

        IntelligentNPCMod.LOGGER.debug("LearningAI loaded with {} patterns and learning rate {}",
                observedPatterns.size(), learningRate);
    }

    private void initializeLearningFocus() {
        // Определение фокуса обучения на основе роли
        String role = npc.getRole().toLowerCase();
        switch (role) {
            case "builder" -> currentLearningFocus = "building";
            case "trader" -> currentLearningFocus = "trading";
            case "farmer" -> currentLearningFocus = "farming";
            case "guard" -> currentLearningFocus = "combat";
            default -> currentLearningFocus = "general";
        }

        memory.addExperience("learning", "Set learning focus to: " + currentLearningFocus);
    }

    public void tick() {
        // Наблюдение за игроками поблизости
        if (npc.age % 20 == 0) { // Каждую секунду
            observeNearbyPlayers();
        }

        // Анализ и обработка наблюдений
        if (npc.age % 100 == 0) { // Каждые 5 секунд
            analyzeObservations();
        }

        // Очистка устаревших данных
        if (npc.age % 1200 == 0) { // Каждую минуту
            cleanupOldData();
        }

        // Экспериментирование
        if (enableExperimentation && npc.age % 2400 == 0) { // Каждые 2 минуты
            considerExperimentation();
        }
    }

    private void observeNearbyPlayers() {
        List<PlayerEntity> nearbyPlayers = world.getEntitiesByClass(
                PlayerEntity.class, npc.getBoundingBox().expand(observationRadius), p -> true);

        for (PlayerEntity player : nearbyPlayers) {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                observePlayer(serverPlayer);
            }
        }
    }

    private void observePlayer(ServerPlayerEntity player) {
        String playerName = player.getName().getString();
        PlayerBehavior behavior = playerBehaviors.computeIfAbsent(playerName, k -> new PlayerBehavior(k));

        // Наблюдение за действиями игрока
        observePlayerActions(player, behavior);

        // Наблюдение за строительством
        observePlayerBuilding(player, behavior);

        // Наблюдение за добычей ресурсов
        observePlayerMining(player, behavior);

        totalObservations++;
    }

    private void observePlayerActions(ServerPlayerEntity player, PlayerBehavior behavior) {
        BlockPos playerPos = player.getBlockPos();

        // Записываем последние действия
        String currentAction = determinePlayerAction(player);
        behavior.addAction(currentAction, playerPos);

        // Анализ паттернов в действиях
        if (behavior.actions.size() >= 3) {
            String pattern = extractActionPattern(behavior);
            if (!pattern.isEmpty()) {
                learnPattern(pattern, "player_behavior:" + player.getName().getString());
            }
        }
    }

    private String determinePlayerAction(ServerPlayerEntity player) {
        // Определение текущего действия игрока на основе контекста
        BlockPos pos = player.getBlockPos();

        // Проверяем, что игрок держит в руках
        ItemStack mainHand = player.getMainHandStack();
        if (!mainHand.isEmpty()) {
            String itemName = mainHand.getItem().toString().toLowerCase();

            if (itemName.contains("pickaxe")) {
                return "mining";
            } else if (itemName.contains("axe")) {
                return "chopping";
            } else if (itemName.contains("shovel")) {
                return "digging";
            } else if (itemName.contains("sword")) {
                return "combat";
            } else if (itemName.contains("hoe")) {
                return "farming";
            } else if (itemName.contains("food")) {
                return "eating";
            }
        }

        // Анализ движения
        if (player.isSprinting()) {
            return "running";
        } else if (player.isSneaking()) {
            return "sneaking";
        } else if (player.getVelocity().length() > 0.1) {
            return "walking";
        } else {
            return "standing";
        }
    }

    private void observePlayerBuilding(ServerPlayerEntity player, PlayerBehavior behavior) {
        // Наблюдение за строительными действиями
        if (currentLearningFocus.equals("building") || currentLearningFocus.equals("general")) {
            BlockPos pos = player.getBlockPos();

            // Проверяем изменения блоков вокруг игрока
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    for (int y = -1; y <= 3; y++) {
                        BlockPos checkPos = pos.add(x, y, z);
                        Block block = world.getBlockState(checkPos).getBlock();

                        // Если блок не воздух и не базовые блоки мира
                        if (!block.getDefaultState().isAir() &&
                                !isNaturalBlock(block)) {

                            String blockId = Registries.BLOCK.getId(block).toString();
                            String buildingPattern = String.format("place_%s_at_%d_%d_%d",
                                    blockId, x, y, z);
                            learnPattern(buildingPattern, "building_technique");
                        }
                    }
                }
            }
        }
    }

    private void observePlayerMining(ServerPlayerEntity player, PlayerBehavior behavior) {
        if (currentLearningFocus.equals("mining") || currentLearningFocus.equals("general")) {
            // TODO: Реализовать наблюдение за добычей ресурсов
            // Это требует отслеживания изменений блоков
        }
    }

    private boolean isNaturalBlock(Block block) {
        String blockName = block.toString().toLowerCase();
        return blockName.contains("stone") || blockName.contains("dirt") ||
                blockName.contains("grass") || blockName.contains("sand") ||
                blockName.contains("gravel") || blockName.contains("water") ||
                blockName.contains("lava") || blockName.contains("air");
    }

    private String extractActionPattern(PlayerBehavior behavior) {
        if (behavior.actions.size() < 3) return "";

        // Берем последние 3 действия
        List<String> recentActions = behavior.actions.subList(
                Math.max(0, behavior.actions.size() - 3), behavior.actions.size());

        // Создаем паттерн
        String pattern = String.join(" -> ", recentActions);

        // Проверяем, является ли это повторяющимся паттерном
        if (Collections.frequency(behavior.actions, recentActions.get(0)) > 2) {
            return "repeat:" + pattern;
        } else {
            return "sequence:" + pattern;
        }
    }

    private void learnPattern(String pattern, String context) {
        ObservedPattern existingPattern = observedPatterns.get(pattern);

        if (existingPattern != null) {
            existingPattern.reinforcePattern();
        } else {
            if (observedPatterns.size() < maxPatternsToStore) {
                observedPatterns.put(pattern, new ObservedPattern(pattern, context, 1));
                memory.learnPattern(pattern, context);
                patternsLearned++;

                // Уведомление о новом изученном паттерне
                if (Math.random() < learningRate) {
                    npc.getChatAI().say("Интересно, я заметил паттерн: " + simplifyPatternForSpeech(pattern));
                }
            }
        }

        lastLearningEvent = System.currentTimeMillis();
        memory.addExperience("learning", "Learned pattern: " + pattern);
        memory.increaseSkill("learning", 1);
    }

    private String simplifyPatternForSpeech(String pattern) {
        // Упрощение паттерна для человекочитаемого вида
        if (pattern.contains("building")) {
            return "новую строительную технику";
        } else if (pattern.contains("mining")) {
            return "способ добычи ресурсов";
        } else if (pattern.contains("combat")) {
            return "боевой прием";
        } else if (pattern.contains("sequence")) {
            return "последовательность действий";
        } else if (pattern.contains("repeat")) {
            return "повторяющееся поведение";
        }
        return "что-то новое";
    }

    private void analyzeObservations() {
        // Анализ накопленных наблюдений для выявления новых знаний
        if (observedPatterns.size() < 2) return;

        // Поиск связей между паттернами
        List<ObservedPattern> patterns = new ArrayList<>(observedPatterns.values());
        for (int i = 0; i < patterns.size() - 1; i++) {
            for (int j = i + 1; j < patterns.size(); j++) {
                analyzePatternConnection(patterns.get(i), patterns.get(j));
            }
        }

        // Обновление успешности действий
        updateActionSuccessRates();
    }

    private void analyzePatternConnection(ObservedPattern pattern1, ObservedPattern pattern2) {
        // Анализ связи между двумя паттернами
        if (pattern1.context.equals(pattern2.context)) {
            // Паттерны из одного контекста - возможно, они связаны
            String combinedPattern = pattern1.pattern + " + " + pattern2.pattern;

            if (!observedPatterns.containsKey(combinedPattern)) {
                memory.addExperience("learning", "Found pattern connection: " + combinedPattern);
            }
        }
    }

    private void updateActionSuccessRates() {
        // Обновление статистики успешности различных действий
        for (ObservedPattern pattern : observedPatterns.values()) {
            if (pattern.observations > 5) { // Достаточно наблюдений
                int successRate = Math.min(100, (int) (pattern.observations * 10));
                actionSuccessRate.put(pattern.pattern, successRate);
            }
        }
    }

    private void cleanupOldData() {
        // Удаление устаревших и малополезных данных
        observedPatterns.entrySet().removeIf(entry -> {
            ObservedPattern pattern = entry.getValue();
            long age = System.currentTimeMillis() - pattern.lastObserved;
            return age > 600000 && pattern.observations < 3; // 10 минут и мало наблюдений
        });

        // Очистка данных о поведении игроков
        playerBehaviors.entrySet().removeIf(entry -> {
            PlayerBehavior behavior = entry.getValue();
            return System.currentTimeMillis() - behavior.lastUpdate > 1800000; // 30 минут
        });
    }

    private void considerExperimentation() {
        if (!enableExperimentation) return;

        // Решение о проведении эксперимента
        double experimentChance = learningRate * memory.getPersonalityTrait("curiosity");

        if (Math.random() < experimentChance) {
            planExperiment();
        }
    }

    private void planExperiment() {
        // Планирование эксперимента на основе изученных паттернов
        List<ObservedPattern> patterns = new ArrayList<>(observedPatterns.values());
        if (patterns.isEmpty()) return;

        ObservedPattern randomPattern = patterns.get((int) (Math.random() * patterns.size()));

        // Пробуем модификацию известного паттерна
        String experimentType = "modify_" + randomPattern.pattern;
        memory.addExperience("learning", "Planning experiment: " + experimentType);

        // Уведомление
        if (Math.random() < 0.5) {
            npc.getChatAI().say("Хм, а что если попробовать по-другому...");
        }
    }

    // Обучение у конкретного игрока
    public void startLearningFromPlayer(PlayerEntity player, String skill) {
        currentTeacher = player;
        currentLearningFocus = skill;

        memory.addExperience("learning",
                String.format("Started learning %s from %s", skill, player.getName().getString()));

        npc.getChatAI().say("Научишь меня " + skill + ", " + player.getName().getString() + "?");
    }

    public void stopLearningFromPlayer() {
        if (currentTeacher != null) {
            memory.addExperience("learning",
                    "Finished learning session with " + currentTeacher.getName().getString());
            currentTeacher = null;
        }
        currentLearningFocus = "general";
    }

    // Применение изученных знаний
    public boolean tryApplyLearnedPattern(String situation) {
        // Поиск подходящего паттерна для текущей ситуации
        Optional<ObservedPattern> bestPattern = findBestPatternForSituation(situation);

        if (bestPattern.isPresent()) {
            boolean success = attemptPatternApplication(bestPattern.get());
            if (success) {
                successfulImitations++;
                memory.addExperience("learning", "Successfully applied pattern: " + bestPattern.get().pattern);
            } else {
                failedImitations++;
                memory.addExperience("learning", "Failed to apply pattern: " + bestPattern.get().pattern);
            }
            return success;
        }
        return false;
    }

    private Optional<ObservedPattern> findBestPatternForSituation(String situation) {
        return observedPatterns.values().stream()
                .filter(p -> p.context.contains(situation) && p.observations >= 3 && p.confidence > 0.5)
                .max(Comparator.comparingDouble(p -> p.confidence));
    }

    private boolean attemptPatternApplication(ObservedPattern pattern) {
        try {
            if (pattern.context.equals("building_technique")) {
                return applyBuildingPattern(pattern.pattern);
            } else if (pattern.context.startsWith("player_behavior")) {
                return applyBehaviorPattern(pattern.pattern);
            }
            return false;
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("Error applying learned pattern {}: {}",
                    pattern.pattern, e.getMessage());
            return false;
        }
    }

    private static final Pattern BUILDING_PATTERN = Pattern.compile("place_([a-z0-9_:]+)_at_(-?\\d+)_(-?\\d+)_(-?\\d+)");

    private boolean applyBuildingPattern(String patternStr) {
        Matcher matcher = BUILDING_PATTERN.matcher(patternStr);
        if (!matcher.matches()) {
            return false;
        }

        String blockIdStr = matcher.group(1);
        int relX = Integer.parseInt(matcher.group(2));
        int relY = Integer.parseInt(matcher.group(3));
        int relZ = Integer.parseInt(matcher.group(4));

        Optional<Block> blockOptional = Registries.BLOCK.getOrEmpty(Identifier.of(blockIdStr));
        if (blockOptional.isEmpty()) {
            IntelligentNPCMod.LOGGER.warn("Could not find block for ID: {}", blockIdStr);
            return false;
        }

        Block blockToPlace = blockOptional.get();
        Item itemToPlace = blockToPlace.asItem();

        if (itemToPlace != Items.AIR && npc.getInventory().containsAny(Set.of(itemToPlace))) {
            BlockPos targetPos = npc.getBlockPos().add(relX, relY, relZ);
            if (world.getBlockState(targetPos).isAir() && world instanceof ServerWorld) {
                ItemStack itemStack = null;
                for(int i = 0; i < npc.getInventory().size(); i++) {
                    ItemStack stack = npc.getInventory().getStack(i);
                    if(stack.isOf(itemToPlace)) {
                        itemStack = stack;
                        break;
                    }
                }
                if (itemStack == null) return false;

                ((ServerWorld) world).setBlockState(targetPos, blockToPlace.getDefaultState());
                itemStack.decrement(1);

                npc.swingHand(Hand.MAIN_HAND);
                memory.addExperience("learning", "Applied building pattern: placed " + blockIdStr);
                return true;
            }
        }
        return false;
    }

    private boolean applyBehaviorPattern(String patternStr) {
        if (patternStr.contains("mining")) {
            boolean hasPickaxe = false;
            for (int i = 0; i < npc.getInventory().size(); i++) {
                if (npc.getInventory().getStack(i).getItem().toString().contains("pickaxe")) {
                    hasPickaxe = true;
                    break;
                }
            }

            if (!hasPickaxe) return false;

            Optional<BlockPos> nearestStone = BlockPos.findClosest(
                    npc.getBlockPos(),
                    10, 5,
                    pos -> world.getBlockState(pos).isOf(Blocks.STONE) || world.getBlockState(pos).isOf(Blocks.COBBLESTONE) || world.getBlockState(pos).isOf(Blocks.DEEPSLATE)
            );

            if (nearestStone.isPresent()) {
                npc.getNavigation().startMovingTo(nearestStone.get().getX(), nearestStone.get().getY(), nearestStone.get().getZ(), 1.0);
                memory.addExperience("learning", "Applied mining behavior: moving to stone.");
                return true;
            }
        }
        return false;
    }

    // Геттеры и сеттеры
    public Map<String, ObservedPattern> getObservedPatterns() {
        return new HashMap<>(observedPatterns);
    }

    public String getCurrentLearningFocus() {
        return currentLearningFocus;
    }

    public PlayerEntity getCurrentTeacher() {
        return currentTeacher;
    }

    public int getTotalObservations() {
        return totalObservations;
    }

    public int getPatternsLearned() {
        return patternsLearned;
    }

    public int getSuccessfulImitations() {
        return successfulImitations;
    }

    public int getFailedImitations() {
        return failedImitations;
    }

    public double getLearningRate() {
        return learningRate;
    }

    public void setLearningRate(double rate) {
        this.learningRate = Math.max(0.01, Math.min(1.0, rate));
    }

    public void setObservationRadius(int radius) {
        this.observationRadius = Math.max(5, Math.min(32, radius));
    }

    public void setEnableImitation(boolean enable) {
        this.enableImitation = enable;
    }

    public void setEnableExperimentation(boolean enable) {
        this.enableExperimentation = enable;
    }

    public void setCurrentLearningFocus(String focus) {
        this.currentLearningFocus = focus;
        memory.addExperience("learning", "Changed learning focus to: " + focus);
    }

    // Статистика
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("total_observations", totalObservations);
        stats.addProperty("patterns_learned", patternsLearned);
        stats.addProperty("successful_imitations", successfulImitations);
        stats.addProperty("failed_imitations", failedImitations);
        stats.addProperty("success_rate",
                (successfulImitations + failedImitations) > 0 ?
                        (double) successfulImitations / (successfulImitations + failedImitations) : 0.0);
        stats.addProperty("known_patterns", observedPatterns.size());
        stats.addProperty("learning_rate", learningRate);
        stats.addProperty("current_focus", currentLearningFocus);
        stats.addProperty("observation_radius", observationRadius);
        stats.addProperty("enable_imitation", enableImitation);
        stats.addProperty("enable_experimentation", enableExperimentation);
        return stats;
    }

    // Внутренние классы
    public static class ObservedPattern {
        public final String pattern;
        public final String context;
        public int observations;
        public long lastObserved;
        public double confidence;

        public ObservedPattern(String pattern, String context, int observations) {
            this.pattern = pattern;
            this.context = context;
            this.observations = observations;
            this.lastObserved = System.currentTimeMillis();
            this.confidence = Math.min(1.0, observations * 0.1);
        }

        public void reinforcePattern() {
            observations++;
            lastObserved = System.currentTimeMillis();
            confidence = Math.min(1.0, observations * 0.1);
        }
    }

    private static class PlayerBehavior {
        final String playerName;
        final List<String> actions = new ArrayList<>();
        final List<BlockPos> positions = new ArrayList<>();
        long lastUpdate;

        PlayerBehavior(String playerName) {
            this.playerName = playerName;
            this.lastUpdate = System.currentTimeMillis();
        }

        void addAction(String action, BlockPos position) {
            actions.add(action);
            positions.add(position);
            lastUpdate = System.currentTimeMillis();

            // Ограничиваем размер истории
            if (actions.size() > 50) {
                actions.remove(0);
                positions.remove(0);
            }
        }
    }

    // AI Goals
    private class ObservePlayerGoal extends Goal {
        private PlayerEntity targetPlayer;

        public ObservePlayerGoal() {
            this.setControls(EnumSet.of(Control.LOOK));
        }

        @Override
        public boolean canStart() {
            List<PlayerEntity> players = world.getEntitiesByClass(
                    PlayerEntity.class, npc.getBoundingBox().expand(observationRadius), p -> true);

            if (!players.isEmpty()) {
                targetPlayer = players.get(0);
                return true;
            }

            return false;
        }

        @Override
        public void tick() {
            if (targetPlayer != null) {
                npc.getLookControl().lookAt(targetPlayer);

                // Более внимательное наблюдение за "учителем"
                if (targetPlayer.equals(currentTeacher)) {
                    // Специальное наблюдение за учителем
                    observeTeacherActions();
                }
            }
        }

        private void observeTeacherActions() {
            if (currentTeacher instanceof ServerPlayerEntity teacher) {
                String action = determinePlayerAction(teacher);

                if (!action.equals("standing")) {
                    memory.addExperience("learning",
                            "Observed teacher action: " + action);
                }
            }
        }

        @Override
        public boolean shouldContinue() {
            return targetPlayer != null && targetPlayer.isAlive() &&
                    npc.squaredDistanceTo(targetPlayer) < observationRadius * observationRadius;
        }
    }

    private class ImitateActionGoal extends Goal {
        private ObservedPattern patternToImitate;
        private int imitationTicks = 0;

        public ImitateActionGoal() {
            this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }

        @Override
        public boolean canStart() {
            if (!enableImitation || !npc.getNavigation().isIdle()) return false;
            if (Math.random() > 0.1) return false; // Don't try to imitate constantly

            // Try to find a pattern relevant to the current situation/focus
            Optional<ObservedPattern> pattern = findBestPatternForSituation(currentLearningFocus);
            if (pattern.isPresent()) {
                patternToImitate = pattern.get();
                return true;
            }

            return false;
        }

        @Override
        public void start() {
            imitationTicks = 0;
            if (Math.random() < 0.3) {
                npc.getChatAI().say("Попробую повторить...");
            }
        }

        @Override
        public void tick() {
            imitationTicks++;

            // Попытка имитировать действие
            if (imitationTicks == 1) { // Try once at the beginning
                boolean success = attemptPatternApplication(patternToImitate);

                if (success && Math.random() < 0.2) {
                    npc.getChatAI().say("Получается!");
                } else if (!success && Math.random() < 0.1) {
                    npc.getChatAI().say("Хм, что-то не так...");
                }
            }
        }

        @Override
        public boolean shouldContinue() {
            return imitationTicks < 20; // Only takes 1 second
        }

        @Override
        public void stop() {
            patternToImitate = null;
        }
    }

    private class ExperimentGoal extends Goal {
        private int experimentTicks = 0;
        private String experimentType;

        public ExperimentGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }

        @Override
        public boolean canStart() {
            if (!enableExperimentation) return false;

            // Начинаем эксперимент с малой вероятностью
            if (Math.random() < 0.05) {
                experimentType = "exploration"; // Базовый тип эксперимента
                return true;
            }

            return false;
        }

        @Override
        public void start() {
            experimentTicks = 0;
            memory.addExperience("learning", "Started experiment: " + experimentType);

            if (Math.random() < 0.4) {
                npc.getChatAI().say("Попробую что-то новое!");
            }
        }

        @Override
        public void tick() {
            experimentTicks++;

            // Простой эксперимент - исследование
            if (experimentType.equals("exploration")) {
                // Случайное движение для исследования
                if (experimentTicks % 60 == 0) {
                    BlockPos randomPos = npc.getBlockPos().add(
                            (int) (Math.random() * 10) - 5,
                            0,
                            (int) (Math.random() * 10) - 5
                    );
                    npc.getNavigation().startMovingTo(randomPos.getX(), randomPos.getY(), randomPos.getZ(), 0.8);
                }
            }
        }

        @Override
        public boolean shouldContinue() {
            return experimentTicks < 300; // 15 секунд максимум
        }

        @Override
        public void stop() {
            memory.addExperience("learning", "Completed experiment: " + experimentType);
        }
    }
}
