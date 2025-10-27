
package com.example.intelligentnpc.npc.ai;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCMemory;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SurvivalAI {
    private final NPCEntity npc;
    private final NPCMemory memory;
    private final World world;
    
    // Текущие угрозы и состояние
    private final Map<String, ThreatInfo> activeThreat = new ConcurrentHashMap<>();
    private final Set<BlockPos> knownDangerousAreas = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> threatCounters = new ConcurrentHashMap<>();
    
    // Настройки выживания
    private double cautionLevel = 0.6; // Уровень осторожности (0.0 - 1.0)
    private int safetyRadius = 8; // Радиус безопасности
    private boolean enableAutoHealing = true; // Автоматическое лечение
    private boolean enableDangerAvoidance = true; // Избегание опасностей
    private boolean enableFireSafety = true; // Противопожарная безопасность
    
    // Состояние здоровья
    private float lastHealth;
    private long lastDamageTime = 0;
    private String lastDamageSource = "";
    
    // Статистика выживания
    private int totalDangersDetected = 0;
    private int totalDangersAvoided = 0;
    private int totalDeaths = 0;
    private int totalDamageReceived = 0;
    
    public SurvivalAI(NPCEntity npc) {
        this.npc = npc;
        this.memory = npc.getMemory();
        this.world = npc.getWorld();
        this.lastHealth = npc.getHealth();
        
        loadSurvivalSkillsFromMemory();
    }
    
    public void registerGoals(GoalSelector goalSelector) {
        goalSelector.add(0, new AvoidLavaGoal());
        goalSelector.add(0, new ExtinguishFireGoal());
        goalSelector.add(1, new FleeEntityGoal<>(npc, HostileEntity.class, 8.0f, 1.0, 1.2));
        goalSelector.add(1, new AvoidCreeperGoal());
        goalSelector.add(1, new FleeFromDangerGoal());
        goalSelector.add(2, new SeekShelterGoal());
        goalSelector.add(2, new AutoHealGoal());
        goalSelector.add(3, new AvoidDangerousAreasGoal());
    }
    
    public void tick() {
        // Обновление состояния здоровья
        updateHealthStatus();
        
        // Сканирование угроз
        if (npc.age % 20 == 0) { // Каждую секунду
            scanForThreats();
        }
        
        // Очистка устаревших угроз
        if (npc.age % 100 == 0) { // Каждые 5 секунд
            cleanupThreats();
        }
        
        // Обновление знаний об опасных зонах
        if (npc.age % 600 == 0) { // Каждые 30 секунд
            updateDangerousAreas();
        }
        
        // Автоматические действия выживания
        performSurvivalActions();
    }
    
    private void updateHealthStatus() {
        float currentHealth = npc.getHealth();
        
        // Регистрация получения урона
        if (currentHealth < lastHealth) {
            float damage = lastHealth - currentHealth;
            totalDamageReceived += (int) damage;
            lastDamageTime = System.currentTimeMillis();
            
            // Анализ причины урона
            analyzeDamageSource(damage);
            
            // Эмоциональная реакция
            npc.getNPCBrain().adjustEmotion("fear", Math.min(0.3, damage / npc.getMaxHealth()));
        }
        
        lastHealth = currentHealth;
    }
    
    private void analyzeDamageSource(float damage) {
        BlockPos pos = npc.getBlockPos();
        String damageSource = "unknown";
        
        // Проверка различных источников урона
        if (npc.isOnFire()) {
            damageSource = "fire";
        } else if (world.getBlockState(pos).getBlock() == Blocks.LAVA || 
                   world.getBlockState(pos.down()).getBlock() == Blocks.LAVA) {
            damageSource = "lava";
        } else if (npc.isSubmergedInWater() && damage > 1) {
            damageSource = "drowning";
        } else if (pos.getY() > 100 && damage > 3) {
            damageSource = "fall";
        } else if (!world.getEntitiesByClass(HostileEntity.class, npc.getBoundingBox().expand(5), e -> true).isEmpty()) {
            damageSource = "mob_attack";
        } else if (world.isRaining() && world.isSkyVisible(pos)) {
            damageSource = "environmental";
        }
        
        lastDamageSource = damageSource;
        
        // Запись в память и статистику
        memory.recordDangerEncounter(damageSource, pos, "received_damage", true);
        memory.addExperience("survival", String.format("Received %.1f damage from %s", damage, damageSource));
        
        IntelligentNPCMod.LOGGER.debug("NPC {} took {} damage from {}", npc.getNpcName(), damage, damageSource);
    }
    
    private void scanForThreats() {
        BlockPos pos = npc.getBlockPos();
        
        // Сканирование блоков вокруг
        scanEnvironmentalThreats(pos);
        
        // Сканирование мобов
        scanMobThreats(pos);
        
        // Сканирование игроков (потенциальные угрозы)
        scanPlayerThreats(pos);
    }
    
    private void scanEnvironmentalThreats(BlockPos pos) {
        // Проверка лавы
        for (int x = -safetyRadius; x <= safetyRadius; x++) {
            for (int z = -safetyRadius; z <= safetyRadius; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos checkPos = pos.add(x, y, z);
                    BlockState state = world.getBlockState(checkPos);
                    
                    if (state.getBlock() == Blocks.LAVA) {
                        registerThreat("lava", checkPos, ThreatLevel.HIGH);
                    } else if (state.getBlock() == Blocks.FIRE) {
                        registerThreat("fire", checkPos, ThreatLevel.MEDIUM);
                    } else if (state.getBlock() == Blocks.CACTUS) {
                        registerThreat("cactus", checkPos, ThreatLevel.LOW);
                    }
                }
            }
        }
        
        // Проверка высоты (опасность падения)
        if (pos.getY() > 80 && !npc.isOnGround()) {
            registerThreat("height", pos, ThreatLevel.MEDIUM);
        }
        
        // Проверка времени суток (ночные мобы)
        long timeOfDay = world.getTimeOfDay() % 24000;
        if (timeOfDay > 13000 && timeOfDay < 23000) { // Ночь
            registerThreat("night", pos, ThreatLevel.LOW);
        }
    }
    
    private void scanMobThreats(BlockPos pos) {
        // Сканирование враждебных мобов
        List<HostileEntity> hostileMobs = world.getEntitiesByClass(
            HostileEntity.class, npc.getBoundingBox().expand(safetyRadius), e -> true);
        
        for (HostileEntity mob : hostileMobs) {
            String mobType = mob.getClass().getSimpleName().toLowerCase();
            ThreatLevel level = determineMobThreatLevel(mob);
            registerThreat(mobType, mob.getBlockPos(), level);
        }
        
        // Особое внимание к криперам
        List<CreeperEntity> creepers = world.getEntitiesByClass(
            CreeperEntity.class, npc.getBoundingBox().expand(safetyRadius * 2), e -> true);
        
        for (CreeperEntity creeper : creepers) {
            registerThreat("creeper", creeper.getBlockPos(), ThreatLevel.CRITICAL);
        }
    }
    
    private ThreatLevel determineMobThreatLevel(HostileEntity mob) {
        if (mob instanceof CreeperEntity) {
            return ThreatLevel.CRITICAL;
        } else if (mob instanceof ZombieEntity || mob instanceof SkeletonEntity) {
            return ThreatLevel.HIGH;
        } else {
            return ThreatLevel.MEDIUM;
        }
    }
    
    private void scanPlayerThreats(BlockPos pos) {
        // В данном случае игроки не рассматриваются как угрозы
        // Но можно добавить логику для PvP серверов
    }
    
    private void registerThreat(String threatType, BlockPos location, ThreatLevel level) {
        String key = threatType + "_" + location.toString();
        ThreatInfo threat = new ThreatInfo(threatType, location, level, System.currentTimeMillis());
        activeThreat.put(key, threat);
        
        totalDangersDetected++;
        threatCounters.merge(threatType, 1, Integer::sum);
        
        // Уведомление мозга о угрозе
        npc.getNPCBrain().onDangerDetected(threatType, location);
        
        IntelligentNPCMod.LOGGER.debug("NPC {} detected {} threat at {}",
            npc.getNpcName(), threatType, location);
    }
    
    private void cleanupThreats() {
        long currentTime = System.currentTimeMillis();
        activeThreat.entrySet().removeIf(entry -> {
            ThreatInfo threat = entry.getValue();
            return (currentTime - threat.detectionTime) > 30000; // 30 секунд
        });
    }
    
    private void updateDangerousAreas() {
        // Отмечаем зоны где NPC получал урон как опасные
        if (!lastDamageSource.isEmpty()) {
            knownDangerousAreas.add(npc.getBlockPos());
            memory.addExperience("survival",
                "Marked area as dangerous due to " + lastDamageSource);
            lastDamageSource = "";
        }
        
        // Очистка старых опасных зон (через 10 минут)
        // TODO: Реализовать временные метки для зон
    }
    
    private void performSurvivalActions() {
        // Автоматическое лечение
        if (enableAutoHealing && needsHealing()) {
            attemptHealing();
        }
        
        // Тушение огня
        if (npc.isOnFire() && enableFireSafety) {
            attemptExtinguishFire();
        }
        
        // Поиск укрытия от дождя/ночи
        if (needsShelter()) {
            findShelter();
        }
    }
    
    private boolean needsHealing() {
        return npc.getHealth() < npc.getMaxHealth() * 0.7f;
    }
    
    private void attemptHealing() {
        // Попытка найти еду или зелья лечения
        // TODO: Реализовать инвентарь NPC и использование предметов
        
        // Отдых для восстановления здоровья
        if (npc.getHealth() < npc.getMaxHealth() * 0.3f) {
            npc.getNPCBrain().setGoal("rest");
            memory.addExperience("survival", "Started resting to recover health");
        }
    }
    
    private void attemptExtinguishFire() {
        // Поиск воды поблизости
        BlockPos waterPos = findNearbyWater();
        if (waterPos != null) {
            npc.getNavigation().startMovingTo(waterPos.getX(), waterPos.getY(), waterPos.getZ(), 1.2);
            memory.addExperience("survival", "Moving to water to extinguish fire");
        } else {
            // Попытка найти ведро с водой или создать небольшой водоем
            // TODO: Реализовать использование предметов
        }
    }
    
    private BlockPos findNearbyWater() {
        BlockPos pos = npc.getBlockPos();
        for (int x = -safetyRadius; x <= safetyRadius; x++) {
            for (int z = -safetyRadius; z <= safetyRadius; z++) {
                for (int y = -2; y <= 2; y++) {
                    BlockPos checkPos = pos.add(x, y, z);
                    if (world.getBlockState(checkPos).getBlock() == Blocks.WATER) {
                        return checkPos;
                    }
                }
            }
        }
        return null;
    }
    
    private boolean needsShelter() {
        long timeOfDay = world.getTimeOfDay() % 24000;
        boolean isNight = timeOfDay > 13000 && timeOfDay < 23000;
        boolean isRaining = world.isRaining();
        
        return (isNight || isRaining) && !isInShelter();
    }
    
    private boolean isInShelter() {
        BlockPos pos = npc.getBlockPos();
        return !world.isSkyVisible(pos.up());
    }
    
    private void findShelter() {
        BlockPos shelterPos = findNearestShelter();
        if (shelterPos != null) {
            npc.getNavigation().startMovingTo(shelterPos.getX(), shelterPos.getY(), shelterPos.getZ(), 1.0);
            memory.addExperience("survival", "Seeking shelter");
        } else {
            // Создание временного укрытия
            createEmergencyShelter();
        }
    }
    
    private BlockPos findNearestShelter() {
        BlockPos pos = npc.getBlockPos();
        for (int radius = 5; radius <= 20; radius += 5) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = pos.add(x, 0, z);
                    if (isSafeLocation(checkPos)) {
                        return checkPos;
                    }
                }
            }
        }
        return null;
    }
    
    public boolean isSafeLocation(BlockPos pos) {
        // Проверка на наличие крыши
        if (world.isSkyVisible(pos.up())) {
            return false;
        }
        
        // Проверка на отсутствие враждебных мобов поблизости
        List<HostileEntity> nearbyMobs = world.getEntitiesByClass(
            HostileEntity.class, npc.getBoundingBox().expand(8), e -> true);
        if (!nearbyMobs.isEmpty()) {
            return false;
        }
        
        // Проверка на отсутствие лавы и огня
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                BlockPos checkPos = pos.add(x, 0, z);
                Block block = world.getBlockState(checkPos).getBlock();
                if (block == Blocks.LAVA || block == Blocks.FIRE) {
                    return false;
                }
            }
        }
        
        return true;
    }
    
    private void createEmergencyShelter() {
        // Создание простого укрытия из земли или булыжника
        BlockPos pos = npc.getBlockPos();
        
        // Простой навес 3x3
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos roofPos = pos.add(x, 3, z);
                if (world.getBlockState(roofPos).getBlock() == Blocks.AIR) {
                    world.setBlockState(roofPos, Blocks.DIRT.getDefaultState());
                }
            }
        }
        
        memory.addExperience("survival", "Created emergency shelter");
        totalDangersAvoided++;
    }
    
    private void loadSurvivalSkillsFromMemory() {
        int survivalSkill = memory.getSkillLevel("survival");
        
        // Настройка параметров на основе навыка
        if (survivalSkill >= 10) {
            safetyRadius = 12;
            cautionLevel = Math.min(1.0, cautionLevel + 0.1);
        }
        if (survivalSkill >= 20) {
            safetyRadius = 16;
            cautionLevel = Math.min(1.0, cautionLevel + 0.2);
        }
        
        // Загрузка известных опасных зон из памяти
        List<NPCMemory.DangerRecord> dangerHistory = memory.getDangerHistory();
        for (NPCMemory.DangerRecord danger : dangerHistory) {
            knownDangerousAreas.add(danger.getLocation());
        }
        
        IntelligentNPCMod.LOGGER.debug("SurvivalAI loaded with survival skill level: {}, {} known dangerous areas",
            survivalSkill, knownDangerousAreas.size());
    }
    
    // Геттеры и сеттеры
    public Map<String, ThreatInfo> getActiveThreats() { return new HashMap<>(activeThreat); }
    public Set<BlockPos> getKnownDangerousAreas() { return new HashSet<>(knownDangerousAreas); }
    public int getTotalDangersDetected() { return totalDangersDetected; }
    public int getTotalDangersAvoided() { return totalDangersAvoided; }
    public int getTotalDeaths() { return totalDeaths; }
    public double getCautionLevel() { return cautionLevel; }
    
    public void setCautionLevel(double level) {
        this.cautionLevel = Math.max(0.0, Math.min(1.0, level));
        memory.adjustPersonalityTrait("cautiousness", level - this.cautionLevel);
    }
    
    public void setSafetyRadius(int radius) {
        this.safetyRadius = Math.max(4, Math.min(32, radius));
    }
    
    public void setEnableAutoHealing(boolean enable) {
        this.enableAutoHealing = enable;
    }
    
    public void setEnableDangerAvoidance(boolean enable) {
        this.enableDangerAvoidance = enable;
    }
    
    public void setEnableFireSafety(boolean enable) {
        this.enableFireSafety = enable;
    }
    
    // Статистика
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("total_dangers_detected", totalDangersDetected);
        stats.addProperty("total_dangers_avoided", totalDangersAvoided);
        stats.addProperty("total_deaths", totalDeaths);
        stats.addProperty("total_damage_received", totalDamageReceived);
        stats.addProperty("caution_level", cautionLevel);
        stats.addProperty("safety_radius", safetyRadius);
        stats.addProperty("active_threats", activeThreat.size());
        stats.addProperty("known_dangerous_areas", knownDangerousAreas.size());
        stats.addProperty("enable_auto_healing", enableAutoHealing);
        stats.addProperty("enable_danger_avoidance", enableDangerAvoidance);
        stats.addProperty("enable_fire_safety", enableFireSafety);
        
        // Статистика по типам угроз
        JsonObject threatStats = new JsonObject();
        threatCounters.forEach(threatStats::addProperty);
        stats.add("threat_counters", threatStats);
        
        return stats;
    }
    
    // Внутренние классы
    public enum ThreatLevel {
        LOW(1), MEDIUM(2), HIGH(3), CRITICAL(4);
        
        public final int priority;
        
        ThreatLevel(int priority) {
            this.priority = priority;
        }
    }
    
    public static class ThreatInfo {
        public final String type;
        public final BlockPos location;
        public final ThreatLevel level;
        public final long detectionTime;
        
        public ThreatInfo(String type, BlockPos location, ThreatLevel level, long detectionTime) {
            this.type = type;
            this.location = location;
            this.level = level;
            this.detectionTime = detectionTime;
        }
    }
    
    // AI Goals
    private class AvoidLavaGoal extends Goal {
        public AvoidLavaGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return activeThreat.values().stream().anyMatch(t -> t.type.equals("lava"));
        }
        
        @Override
        public void start() {
            ThreatInfo lavaThreat = activeThreat.values().stream()
                .filter(t -> t.type.equals("lava"))
                .findFirst()
                .orElse(null);
            
            if (lavaThreat != null) {
                // Убегаем от лавы
                BlockPos safePos = findSafePositionAwayFrom(lavaThreat.location);
                if (safePos != null) {
                    npc.getNavigation().startMovingTo(safePos.getX(), safePos.getY(), safePos.getZ(), 1.5);
                }
            }
        }
        
        private BlockPos findSafePositionAwayFrom(BlockPos dangerPos) {
            BlockPos npcPos = npc.getBlockPos();
            Direction awayDirection = Direction.fromVector(
                npcPos.getX() - dangerPos.getX(),
                0,
                npcPos.getZ() - dangerPos.getZ()
            );
            
            if (awayDirection != null) {
                return npcPos.offset(awayDirection, 8);
            }
            
            return npcPos.up();
        }
    }
    
    private class ExtinguishFireGoal extends Goal {
        public ExtinguishFireGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return npc.isOnFire() && enableFireSafety;
        }
        
        @Override
        public void start() {
            attemptExtinguishFire();
        }
        
        @Override
        public void tick() {
            if (npc.isOnFire()) {
                BlockPos waterPos = findNearbyWater();
                if (waterPos != null) {
                    npc.getNavigation().startMovingTo(waterPos.getX(), waterPos.getY(), waterPos.getZ(), 1.5);
                }
            }
        }
    }
    
    private class AvoidCreeperGoal extends Goal {
        private CreeperEntity targetCreeper;
        
        public AvoidCreeperGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            List<CreeperEntity> creepers = world.getEntitiesByClass(
                CreeperEntity.class, npc.getBoundingBox().expand(8), e -> true);
            
            if (!creepers.isEmpty()) {
                targetCreeper = creepers.get(0);
                return true;
            }
            
            return false;
        }
        
        @Override
        public void start() {
            if (targetCreeper != null) {
                // Убегаем от крипера
                BlockPos creeperPos = targetCreeper.getBlockPos();
                BlockPos npcPos = npc.getBlockPos();
                
                int deltaX = npcPos.getX() - creeperPos.getX();
                int deltaZ = npcPos.getZ() - creeperPos.getZ();
                
                BlockPos escapePos = npcPos.add(deltaX * 2, 0, deltaZ * 2);
                npc.getNavigation().startMovingTo(escapePos.getX(), escapePos.getY(), escapePos.getZ(), 2.0);
                
                totalDangersAvoided++;
                memory.recordDangerEncounter("creeper", creeperPos, "fled", true);
            }
        }
        
        @Override
        public boolean shouldContinue() {
            return targetCreeper != null && targetCreeper.isAlive() &&
                   npc.squaredDistanceTo(targetCreeper) < 64; // 8 блоков
        }
    }
    
    private class FleeFromDangerGoal extends Goal {
        public FleeFromDangerGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return activeThreat.values().stream()
                .anyMatch(t -> t.level.priority >= ThreatLevel.HIGH.priority);
        }
        
        @Override
        public void start() {
            // Находим самую опасную угрозу
            ThreatInfo highestThreat = activeThreat.values().stream()
                .max(Comparator.comparing(t -> t.level.priority))
                .orElse(null);
            
            if (highestThreat != null) {
                BlockPos safePos = findSafePositionAwayFrom(highestThreat.location);
                if (safePos != null) {
                    npc.getNavigation().startMovingTo(safePos.getX(), safePos.getY(), safePos.getZ(), 1.8);
                }
            }
        }
        
        private BlockPos findSafePositionAwayFrom(BlockPos dangerPos) {
            BlockPos npcPos = npc.getBlockPos();
            
            for (int distance = 8; distance <= 24; distance += 4) {
                for (Direction dir : Direction.Type.HORIZONTAL) {
                    BlockPos testPos = npcPos.offset(dir, distance);
                    if (isSafeLocation(testPos)) {
                        return testPos;
                    }
                }
            }
            
            return npcPos.up(5); // В крайнем случае поднимаемся вверх
        }
    }
    
    private class SeekShelterGoal extends Goal {
        public SeekShelterGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return needsShelter();
        }
        
        @Override
        public void start() {
            findShelter();
        }
        
        @Override
        public void tick() {
            if (!isInShelter()) {
                BlockPos shelterPos = findNearestShelter();
                if (shelterPos != null) {
                    npc.getNavigation().startMovingTo(shelterPos.getX(), shelterPos.getY(), shelterPos.getZ(), 1.0);
                }
            }
        }
    }
    
    private class AutoHealGoal extends Goal {
        private int healingTicks = 0;
        
        public AutoHealGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return enableAutoHealing && needsHealing();
        }
        
        @Override
        public void start() {
            healingTicks = 0;
            npc.getNavigation().stop();
        }
        
        @Override
        public void tick() {
            healingTicks++;
            
            // Медленное восстановление здоровья во время отдыха
            if (healingTicks % 100 == 0 && npc.getHealth() < npc.getMaxHealth()) {
                // TODO: Реализовать постепенное лечение
            }
        }
        
        @Override
        public boolean shouldContinue() {
            return npc.getHealth() < npc.getMaxHealth() * 0.8f && healingTicks < 600;
        }
    }
    
    private class AvoidDangerousAreasGoal extends Goal {
        public AvoidDangerousAreasGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            BlockPos npcPos = npc.getBlockPos();
            return knownDangerousAreas.stream()
                .anyMatch(dangerPos -> dangerPos.getManhattanDistance(npcPos) < 5);
        }
        
        @Override
        public void start() {
            BlockPos npcPos = npc.getBlockPos();
            BlockPos nearestDanger = knownDangerousAreas.stream()
                .min(Comparator.comparing(pos -> pos.getManhattanDistance(npcPos)))
                .orElse(null);
            
            if (nearestDanger != null) {
                BlockPos safePos = findSafePositionAwayFrom(nearestDanger);
                if (safePos != null) {
                    npc.getNavigation().startMovingTo(safePos.getX(), safePos.getY(), safePos.getZ(), 1.2);
                }
            }
        }
        
        private BlockPos findSafePositionAwayFrom(BlockPos dangerPos) {
            BlockPos npcPos = npc.getBlockPos();
            
            for (Direction dir : Direction.Type.HORIZONTAL) {
                BlockPos testPos = npcPos.offset(dir, 10);
                if (!knownDangerousAreas.contains(testPos) && isSafeLocation(testPos)) {
                    return testPos;
                }
            }
            
            return npcPos.up(3);
        }
    }
}