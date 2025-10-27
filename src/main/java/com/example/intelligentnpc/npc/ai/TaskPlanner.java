package com.example.intelligentnpc.npc.ai;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCMemory;
import com.google.gson.JsonObject;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TaskPlanner {
    private final NPCEntity npc;
    private final NPCMemory memory;
    
    // Система приоритетов и задач
    private final Queue<PlannedTask> taskQueue = new PriorityQueue<>(Comparator.comparing(PlannedTask::getPriority).reversed());
    private PlannedTask currentTask;
    private final Map<String, Integer> taskSuccessRate = new ConcurrentHashMap<>();
    
    // Планирование и стратегия
    private PlanningStrategy currentStrategy = PlanningStrategy.BALANCED;
    private final Map<String, Long> taskCooldowns = new ConcurrentHashMap<>();
    private long lastPlanningUpdate = 0;
    
    // Статистика
    private int totalTasksPlanned = 0;
    private int tasksCompleted = 0;
    private int tasksFailed = 0;
    
    public TaskPlanner(NPCEntity npc) {
        this.npc = npc;
        this.memory = npc.getMemory();
        
        initializePlanningStrategy();
        loadPlanningDataFromMemory();
    }
    
    private void initializePlanningStrategy() {
        String role = npc.getRole().toLowerCase();
        
        switch (role) {
            case "leader", "commander" -> currentStrategy = PlanningStrategy.LEADERSHIP_FOCUSED;
            case "builder", "architect" -> currentStrategy = PlanningStrategy.PROJECT_FOCUSED;
            case "trader", "merchant" -> currentStrategy = PlanningStrategy.OPPORTUNISTIC;
            case "guard", "warrior" -> currentStrategy = PlanningStrategy.SECURITY_FOCUSED;
            case "explorer", "scout" -> currentStrategy = PlanningStrategy.EXPLORATION_FOCUSED;
            default -> currentStrategy = PlanningStrategy.BALANCED;
        }
        
        memory.addExperience("planning", "Set planning strategy to: " + currentStrategy.name());
    }
    
    private void loadPlanningDataFromMemory() {
        // Загрузка успешности задач из памяти
        List<NPCMemory.MemoryEntry> taskMemories = memory.getRecentExperiences(50).stream()
            .filter(m -> m.getCategory().equals("task_completion"))
            .toList();
        
        for (NPCMemory.MemoryEntry entry : taskMemories) {
            if (entry.getDescription().contains("success")) {
                updateTaskSuccess("generic", true);
            } else if (entry.getDescription().contains("failure")) {
                updateTaskSuccess("generic", false);
            }
        }
        
        IntelligentNPCMod.LOGGER.debug("TaskPlanner loaded planning data for {}", npc.getNpcName());
    }
    
    public void tick() {
        // Обновление планирования каждые 5 секунд
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastPlanningUpdate > 5000) {
            updatePlanning();
            lastPlanningUpdate = currentTime;
        }
        
        // Обработка текущей задачи
        if (currentTask != null) {
            processCurrentTask();
        } else if (!taskQueue.isEmpty()) {
            startNextTask();
        }
        
        // Очистка просроченных кулдаунов
        taskCooldowns.entrySet().removeIf(entry -> 
            currentTime - entry.getValue() > 300000); // 5 минут
    }
    
    private void updatePlanning() {
        // Анализ текущей ситуации
        AnalysisResult analysis = analyzeCurrentSituation();
        
        // Генерация новых задач на основе анализа
        generateTasksFromAnalysis(analysis);
        
        // Переоценка приоритетов существующих задач
        reprioritizeTasks(analysis);
        
        // Адаптация стратегии планирования
        adaptStrategy(analysis);
    }
    
    private AnalysisResult analyzeCurrentSituation() {
        AnalysisResult analysis = new AnalysisResult();
        
        // Анализ здоровья и ресурсов
        analysis.healthRatio = npc.getHealth() / npc.getMaxHealth();
        analysis.energyLevel = npc.getNPCBrain().getEnergy();
        analysis.needsRest = analysis.healthRatio < 0.5 || analysis.energyLevel < 0.3;
        
        // Анализ окружения
        BlockPos pos = npc.getBlockPos();
        analysis.timeOfDay = npc.getWorld().getTimeOfDay() % 24000;
        analysis.isNight = analysis.timeOfDay > 13000 && analysis.timeOfDay < 23000;
        analysis.isRaining = npc.getWorld().isRaining();
        
        // Анализ угроз
        analysis.threatsNearby = !npc.getSurvivalAI().getActiveThreats().isEmpty();
        analysis.inDangerousArea = npc.getSurvivalAI().getKnownDangerousAreas().stream()
            .anyMatch(dangerPos -> dangerPos.getManhattanDistance(pos) < 10);
        
        // Анализ социального контекста
        analysis.playersNearby = !npc.getWorld().getEntitiesByClass(
            net.minecraft.entity.player.PlayerEntity.class, 
            npc.getBoundingBox().expand(16), p -> true).isEmpty();
        
        analysis.alliesNearby = npc.getTeamAI().getTeamMembers().size() > 1;
        
        return analysis;
    }
    
    private void generateTasksFromAnalysis(AnalysisResult analysis) {
        // Генерация задач на основе текущей ситуации и стратегии
        
        // Приоритетные задачи безопасности
        if (analysis.threatsNearby || analysis.inDangerousArea) {
            addTaskIfNotCooldown("seek_safety", Priority.CRITICAL, "Find safe location");
        }
        
        // Задачи восстановления
        if (analysis.needsRest) {
            addTaskIfNotCooldown("rest", Priority.HIGH, "Rest to recover");
        }
        
        // Задачи в зависимости от времени суток
        if (analysis.isNight && !analysis.threatsNearby) {
            addTaskIfNotCooldown("seek_shelter", Priority.MEDIUM, "Find shelter for the night");
        }
        
        // Социальные задачи
        if (analysis.playersNearby && Math.random() < 0.3) {
            addTaskIfNotCooldown("socialize", Priority.LOW, "Interact with players");
        }
        
        // Стратегические задачи
        switch (currentStrategy) {
            case PROJECT_FOCUSED -> {
                if (!analysis.needsRest && !analysis.threatsNearby) {
                    addTaskIfNotCooldown("build", Priority.MEDIUM, "Work on building projects");
                }
            }
            case OPPORTUNISTIC -> {
                if (analysis.playersNearby) {
                    addTaskIfNotCooldown("trade", Priority.MEDIUM, "Look for trading opportunities");
                }
            }
            case EXPLORATION_FOCUSED -> {
                if (!analysis.isNight && !analysis.threatsNearby) {
                    addTaskIfNotCooldown("explore", Priority.LOW, "Explore surroundings");
                }
            }
            case SECURITY_FOCUSED -> {
                if (analysis.alliesNearby) {
                    addTaskIfNotCooldown("patrol", Priority.MEDIUM, "Patrol and guard area");
                }
            }
            case LEADERSHIP_FOCUSED -> {
                if (analysis.alliesNearby) {
                    addTaskIfNotCooldown("coordinate_team", Priority.HIGH, "Coordinate team activities");
                }
            }
        }
        
        // Обучающие задачи
        if (analysis.playersNearby && Math.random() < 0.2) {
            addTaskIfNotCooldown("observe_and_learn", Priority.LOW, "Observe player actions to learn");
        }
    }
    
    private void addTaskIfNotCooldown(String taskType, Priority priority, String description) {
        Long lastExecution = taskCooldowns.get(taskType);
        long currentTime = System.currentTimeMillis();
        
        // Проверяем кулдаун (разный для разных типов задач)
        long cooldownDuration = getCooldownForTaskType(taskType);
        
        if (lastExecution == null || (currentTime - lastExecution) > cooldownDuration) {
            PlannedTask task = new PlannedTask(taskType, priority, description);
            taskQueue.offer(task);
            totalTasksPlanned++;
        }
    }
    
    private long getCooldownForTaskType(String taskType) {
        return switch (taskType) {
            case "rest" -> 120000; // 2 минуты
            case "socialize" -> 60000; // 1 минута
            case "build" -> 300000; // 5 минут
            case "trade" -> 180000; // 3 минуты
            case "explore" -> 240000; // 4 минуты
            case "patrol" -> 120000; // 2 минуты
            default -> 60000; // 1 минута по умолчанию
        };
    }
    
    private void reprioritizeTasks(AnalysisResult analysis) {
        // Переоценка приоритетов задач в очереди
        List<PlannedTask> tasks = new ArrayList<>(taskQueue);
        taskQueue.clear();
        
        for (PlannedTask task : tasks) {
            // Корректировка приоритета на основе текущей ситуации
            Priority newPriority = calculateAdjustedPriority(task, analysis);
            
            if (newPriority != Priority.IGNORED) {
                task.priority = newPriority;
                taskQueue.offer(task);
            }
        }
    }
    
    private Priority calculateAdjustedPriority(PlannedTask task, AnalysisResult analysis) {
        Priority basePriority = task.priority;
        
        // Корректировка в зависимости от ситуации
        if (analysis.threatsNearby) {
            // Во время опасности снижаем приоритет несрочных задач
            if (task.taskType.equals("socialize") || task.taskType.equals("explore")) {
                return Priority.IGNORED;
            }
            if (task.taskType.equals("seek_safety")) {
                return Priority.CRITICAL;
            }
        }
        
        if (analysis.needsRest) {
            if (task.taskType.equals("rest")) {
                return Priority.HIGH;
            }
        }
        
        if (analysis.playersNearby) {
            if (task.taskType.equals("socialize") || task.taskType.equals("trade")) {
                return Priority.values()[Math.min(Priority.values().length - 1, basePriority.ordinal() + 1)];
            }
        }
        
        return basePriority;
    }
    
    private void adaptStrategy(AnalysisResult analysis) {
        // Адаптация стратегии планирования на основе результатов выполнения задач
        
        // Анализ успешности текущей стратегии
        double currentStrategySuccess = calculateStrategySuccess();
        
        if (currentStrategySuccess < 0.5) { // Менее 50% успеха
            // Попробуем другую стратегию
            PlanningStrategy[] strategies = PlanningStrategy.values();
            PlanningStrategy newStrategy = strategies[(int)(Math.random() * strategies.length)];
            
            if (newStrategy != currentStrategy) {
                currentStrategy = newStrategy;
                memory.addExperience("planning", "Changed strategy to: " + newStrategy.name());
            }
        }
    }
    
    private double calculateStrategySuccess() {
        if (totalTasksPlanned == 0) return 0.5;
        
        return (double) tasksCompleted / (tasksCompleted + tasksFailed);
    }
    
    private void processCurrentTask() {
        if (currentTask.isExpired()) {
            onTaskFailed(currentTask, "Task expired");
            currentTask = null;
            return;
        }
        
        // Выполнение задачи в зависимости от типа
        boolean completed = executeTask(currentTask);
        
        if (completed) {
            onTaskCompleted(currentTask);
            currentTask = null;
        }
    }
    
    private boolean executeTask(PlannedTask task) {
        try {
            switch (task.taskType) {
                case "seek_safety" -> {
                    return npc.getNPCBrain().getCurrentGoal().equals("retreat") || 
                           npc.getSurvivalAI().getActiveThreats().isEmpty();
                }
                case "rest" -> {
                    npc.getNPCBrain().setGoal("rest");
                    return npc.getNPCBrain().getEnergy() > 0.8;
                }
                case "socialize" -> {
                    // Поиск игроков для общения
                    List<net.minecraft.entity.player.PlayerEntity> nearbyPlayers = npc.getWorld().getEntitiesByClass(
                        net.minecraft.entity.player.PlayerEntity.class, npc.getBoundingBox().expand(10), p -> true);
                    
                    if (!nearbyPlayers.isEmpty() && Math.random() < 0.5) {
                        npc.getChatAI().say("Привет всем!");
                        return true;
                    }
                    return false;
                }
                case "build" -> {
                    npc.getNPCBrain().setGoal("building");
                    return true;
                }
                case "trade" -> {
                    npc.getNPCBrain().setGoal("trading");
                    return true;
                }
                case "explore" -> {
                    npc.getNPCBrain().setGoal("explore");
                    return true;
                }
                case "patrol" -> {
                    npc.getNPCBrain().setGoal("patrol");
                    return true;
                }
                case "coordinate_team" -> {
                    if (npc.getTeamAI().isInTeam()) {
                        // Координация команды
                        return true;
                    }
                    return false;
                }
                case "observe_and_learn" -> {
                    if (npc.getLearningAI() != null) {
                        npc.getNPCBrain().setGoal("learn");
                        return true;
                    }
                    return false;
                }
                default -> {
                    return false;
                }
            }
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("Error executing task {} for NPC {}: {}", 
                task.taskType, npc.getNpcName(), e.getMessage());
            return false;
        }
    }
    
    private void startNextTask() {
        currentTask = taskQueue.poll();
        if (currentTask != null) {
            memory.addExperience("planning", "Started task: " + currentTask.taskType);
            taskCooldowns.put(currentTask.taskType, System.currentTimeMillis());
        }
    }
    
    private void onTaskCompleted(PlannedTask task) {
        tasksCompleted++;
        updateTaskSuccess(task.taskType, true);
        
        memory.addExperience("planning", "Completed task: " + task.taskType);
        memory.increaseSkill("planning", 2);
        
        // Положительная эмоциональная реакция
        npc.getNPCBrain().adjustEmotion("happiness", 0.05);
        npc.getNPCBrain().adjustEmotion("confidence", 0.03);
        
        IntelligentNPCMod.LOGGER.debug("NPC {} completed task: {}", npc.getNpcName(), task.taskType);
    }
    
    private void onTaskFailed(PlannedTask task, String reason) {
        tasksFailed++;
        updateTaskSuccess(task.taskType, false);
        
        memory.addExperience("planning", "Failed task: " + task.taskType + " (" + reason + ")");
        
        // Негативная эмоциональная реакция
        npc.getNPCBrain().adjustEmotion("sadness", 0.03);
        
        IntelligentNPCMod.LOGGER.debug("NPC {} failed task: {} ({})", npc.getNpcName(), task.taskType, reason);
    }
    
    private void updateTaskSuccess(String taskType, boolean success) {
        taskSuccessRate.merge(taskType, success ? 1 : -1, Integer::sum);
        // Ограничиваем значения
        taskSuccessRate.computeIfPresent(taskType, (k, v) -> Math.max(-10, Math.min(10, v)));
    }
    
    // Публичные методы для взаимодействия с другими модулями
    public void requestTask(String taskType, Priority priority, String description) {
        PlannedTask task = new PlannedTask(taskType, priority, description);
        taskQueue.offer(task);
        totalTasksPlanned++;
    }
    
    public void requestUrgentTask(String taskType, String description) {
        requestTask(taskType, Priority.CRITICAL, description);
    }
    
    public void cancelTasksOfType(String taskType) {
        taskQueue.removeIf(task -> task.taskType.equals(taskType));
        
        if (currentTask != null && currentTask.taskType.equals(taskType)) {
            onTaskFailed(currentTask, "Cancelled");
            currentTask = null;
        }
    }
    
    public void clearAllTasks() {
        taskQueue.clear();
        if (currentTask != null) {
            onTaskFailed(currentTask, "All tasks cleared");
            currentTask = null;
        }
    }
    
    // Геттеры и статистика
    public PlannedTask getCurrentTask() { return currentTask; }
    public int getQueueSize() { return taskQueue.size(); }
    public PlanningStrategy getCurrentStrategy() { return currentStrategy; }
    public int getTotalTasksPlanned() { return totalTasksPlanned; }
    public int getTasksCompleted() { return tasksCompleted; }
    public int getTasksFailed() { return tasksFailed; }
    public double getSuccessRate() {
        int total = tasksCompleted + tasksFailed;
        return total > 0 ? (double) tasksCompleted / total : 0.0;
    }
    
    public void setCurrentStrategy(PlanningStrategy strategy) {
        this.currentStrategy = strategy;
        memory.addExperience("planning", "Strategy changed to: " + strategy.name());
    }
    
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("current_strategy", currentStrategy.name());
        stats.addProperty("queue_size", taskQueue.size());
        stats.addProperty("current_task", currentTask != null ? currentTask.taskType : null);
        stats.addProperty("total_planned", totalTasksPlanned);
        stats.addProperty("completed", tasksCompleted);
        stats.addProperty("failed", tasksFailed);
        stats.addProperty("success_rate", getSuccessRate());
        
        // Статистика по типам задач
        JsonObject taskStats = new JsonObject();
        taskSuccessRate.forEach(taskStats::addProperty);
        stats.add("task_success_rates", taskStats);
        
        return stats;
    }
    
    // Внутренние классы
    public enum Priority {
        LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4), IGNORED(0);
        
        public final int value;
        
        Priority(int value) {
            this.value = value;
        }
    }
    
    public enum PlanningStrategy {
        BALANCED,           // Сбалансированный подход
        PROJECT_FOCUSED,    // Фокус на проектах/строительстве
        OPPORTUNISTIC,      // Использование возможностей
        SECURITY_FOCUSED,   // Фокус на безопасности
        EXPLORATION_FOCUSED,// Фокус на исследовании
        LEADERSHIP_FOCUSED  // Фокус на лидерстве
    }
    
    public static class PlannedTask {
        public final String taskType;
        public Priority priority;
        public final String description;
        public final long creationTime;
        public final long maxDuration;
        
        public PlannedTask(String taskType, Priority priority, String description) {
            this.taskType = taskType;
            this.priority = priority;
            this.description = description;
            this.creationTime = System.currentTimeMillis();
            this.maxDuration = 300000; // 5 минут по умолчанию
        }
        
        public Priority getPriority() {
            return priority;
        }
        
        public boolean isExpired() {
            return (System.currentTimeMillis() - creationTime) > maxDuration;
        }
    }
    
    private static class AnalysisResult {
        boolean needsRest;
        boolean threatsNearby;
        boolean inDangerousArea;
        boolean isNight;
        boolean isRaining;
        boolean playersNearby;
        boolean alliesNearby;
        float healthRatio;
        double energyLevel;
        long timeOfDay;
    }
}