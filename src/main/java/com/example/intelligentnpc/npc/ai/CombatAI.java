
package com.example.intelligentnpc.npc.ai;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCMemory;
import com.google.gson.JsonObject;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CombatAI {
    private final NPCEntity npc;
    private final NPCMemory memory;
    
    // Боевое состояние
    private LivingEntity currentTarget;
    private final Map<Class<? extends LivingEntity>, ThreatAssessment> threatLevels = new ConcurrentHashMap<>();
    private final Set<UUID> allies = ConcurrentHashMap.newKeySet();
    private final Set<UUID> enemies = ConcurrentHashMap.newKeySet();
    
    // Настройки боя
    private double aggressionLevel = 0.5; // Уровень агрессии (0.0 - 1.0)
    private double defensiveness = 0.7; // Уровень защиты
    private boolean protectAllies = true; // Защищать союзников
    private boolean attackHostiles = true; // Атаковать враждебных мобов
    private boolean canAttackPlayers = false; // Может ли атаковать игроков
    private double engagementRange = 8.0; // Дистанция вступления в бой
    private double retreatHealthThreshold = 0.3; // Порог здоровья для отступления
    
    // Боевая статистика
    private int totalFights = 0;
    private int fightsWon = 0;
    private int fightsLost = 0;
    private int damageDealt = 0;
    private int damageTaken = 0;
    
    // Боевые тактики
    private CombatTactic currentTactic = CombatTactic.BALANCED;
    private long lastTacticChange = 0;
    private final Map<String, Integer> tacticSuccessRate = new ConcurrentHashMap<>();
    
    public CombatAI(NPCEntity npc) {
        this.npc = npc;
        this.memory = npc.getMemory();
        
        initializeThreatLevels();
        loadCombatSkillsFromMemory();
        determineCombatRole();
    }
    
    public void registerGoals(GoalSelector goalSelector, GoalSelector targetSelector) {
        // Боевые цели (высокий приоритет)
        goalSelector.add(1, new DefendAllyGoal());
        goalSelector.add(2, new AttackTargetGoal());
        goalSelector.add(2, new RetreatGoal());
        
        // Цели выбора противника
        targetSelector.add(1, new ProtectAllyTargetGoal());
        targetSelector.add(2, new RevengeGoal(npc));
        targetSelector.add(3, new ActiveTargetGoal<>(npc, HostileEntity.class, true));
        
        // Условно можем атаковать игроков (только если разрешено и есть причина)
        if (canAttackPlayers) {
            targetSelector.add(4, new ConditionalPlayerTargetGoal());
        }
    }
    
    private void initializeThreatLevels() {
        // Инициализация уровней угрозы для различных существ
        threatLevels.put(HostileEntity.class, new ThreatAssessment(0.8, "hostile_mob"));
        threatLevels.put(AnimalEntity.class, new ThreatAssessment(0.1, "peaceful_animal"));
        threatLevels.put(PlayerEntity.class, new ThreatAssessment(0.3, "player"));
        
        // TODO: Добавить больше специфичных типов мобов
    }
    
    private void loadCombatSkillsFromMemory() {
        int combatSkill = memory.getSkillLevel("combat");
        
        // Улучшение боевых способностей с ростом навыка
        if (combatSkill >= 10) {
            engagementRange = 12.0;
            aggressionLevel = Math.min(1.0, aggressionLevel + 0.1);
        }
        if (combatSkill >= 20) {
            engagementRange = 16.0;
            defensiveness = Math.min(1.0, defensiveness + 0.1);
            retreatHealthThreshold = 0.2; // Более смелый
        }
        
        // Загрузка предпочтительных тактик из памяти
        List<NPCMemory.MemoryEntry> combatMemories = memory.getRecentExperiences(20).stream()
            .filter(m -> m.getCategory().equals("combat"))
            .toList();
        
        // Анализ успешности тактик
        for (NPCMemory.MemoryEntry entry : combatMemories) {
            if (entry.getDescription().contains("won")) {
                updateTacticSuccess(currentTactic.name(), true);
            } else if (entry.getDescription().contains("lost")) {
                updateTacticSuccess(currentTactic.name(), false);
            }
        }
        
        IntelligentNPCMod.LOGGER.debug("CombatAI loaded with combat skill level: {}", combatSkill);
    }
    
    private void determineCombatRole() {
        String role = npc.getRole().toLowerCase();
        
        switch (role) {
            case "guard", "warrior", "knight" -> {
                aggressionLevel = 0.8;
                defensiveness = 0.9;
                protectAllies = true;
                attackHostiles = true;
                currentTactic = CombatTactic.AGGRESSIVE;
            }
            case "archer", "ranger" -> {
                aggressionLevel = 0.6;
                defensiveness = 0.6;
                engagementRange = 12.0;
                currentTactic = CombatTactic.RANGED;
            }
            case "healer", "cleric" -> {
                aggressionLevel = 0.3;
                defensiveness = 0.9;
                protectAllies = true;
                attackHostiles = false;
                currentTactic = CombatTactic.DEFENSIVE;
            }
            case "rogue", "assassin" -> {
                aggressionLevel = 0.7;
                defensiveness = 0.4;
                currentTactic = CombatTactic.AMBUSH;
            }
            default -> {
                // Обычная роль - сбалансированный бой
                currentTactic = CombatTactic.BALANCED;
            }
        }
        
        memory.addExperience("combat", "Determined combat role based on: " + role);
    }
    
    public void tick() {
        // Обновление текущего состояния боя
        updateCombatState();
        
        // Анализ ситуации каждые 2 секунды
        if (npc.age % 40 == 0) {
            analyzeCombatSituation();
        }
        
        // Смена тактики если текущая неэффективна
        if (npc.age % 200 == 0) { // Каждые 10 секунд
            considerTacticChange();
        }
    }
    
    private void updateCombatState() {
        // Проверка текущего противника
        if (currentTarget != null) {
            if (!currentTarget.isAlive() || npc.squaredDistanceTo(currentTarget) > engagementRange * engagementRange * 4) {
                currentTarget = null;
            }
        }
        
        // Проверка необходимости отступления
        if (shouldRetreat()) {
            currentTarget = null;
            npc.getNPCBrain().setGoal("retreat");
        }
    }
    
    private boolean shouldRetreat() {
        float healthRatio = npc.getHealth() / npc.getMaxHealth();
        return healthRatio < retreatHealthThreshold;
    }
    
    private void analyzeCombatSituation() {
        // Подсчет врагов и союзников поблизости
        List<HostileEntity> nearbyEnemies = npc.getWorld().getEntitiesByClass(
            HostileEntity.class, npc.getBoundingBox().expand(engagementRange), e -> true);
        
        List<NPCEntity> nearbyAllies = npc.getWorld().getEntitiesByClass(
            NPCEntity.class, npc.getBoundingBox().expand(16),
            ally -> ally != npc && allies.contains(ally.getUuid()));
        
        // Адаптация тактики к ситуации
        if (nearbyEnemies.size() > 3 && nearbyAllies.size() < 2) {
            // Много врагов, мало союзников - оборона или отступление
            if (currentTactic != CombatTactic.DEFENSIVE) {
                changeTactic(CombatTactic.DEFENSIVE);
            }
        } else if (nearbyAllies.size() > nearbyEnemies.size()) {
            // Численное преимущество - агрессивная тактика
            if (currentTactic != CombatTactic.AGGRESSIVE) {
                changeTactic(CombatTactic.AGGRESSIVE);
            }
        }
    }
    
    private void considerTacticChange() {
        // Анализ эффективности текущей тактики
        String currentTacticName = currentTactic.name();
        Integer successRate = tacticSuccessRate.get(currentTacticName);
        
        if (successRate != null && successRate < 30) { // Менее 30% успеха
            // Попробуем другую тактику
            CombatTactic[] tactics = CombatTactic.values();
            CombatTactic newTactic = tactics[(int)(Math.random() * tactics.length)];
            
            if (newTactic != currentTactic) {
                changeTactic(newTactic);
                npc.getChatAI().say("Попробую другую тактику...");
            }
        }
    }
    
    private void changeTactic(CombatTactic newTactic) {
        currentTactic = newTactic;
        lastTacticChange = System.currentTimeMillis();
        memory.addExperience("combat", "Changed tactic to: " + newTactic.name());
        
        // Адаптация параметров к новой тактике
        switch (newTactic) {
            case AGGRESSIVE -> {
                aggressionLevel = Math.min(1.0, aggressionLevel + 0.2);
                defensiveness = Math.max(0.2, defensiveness - 0.2);
            }
            case DEFENSIVE -> {
                aggressionLevel = Math.max(0.2, aggressionLevel - 0.2);
                defensiveness = Math.min(1.0, defensiveness + 0.2);
            }
            case RANGED -> {
                engagementRange = Math.min(20.0, engagementRange + 4.0);
            }
            case AMBUSH -> {
                // Скрытность и внезапность
                aggressionLevel = 0.8;
                engagementRange = 6.0;
            }
        }
    }
    
    public void onDamageReceived(float damage, LivingEntity attacker) {
        damageTaken += (int)damage;
        
        // Запоминаем агрессора
        if (attacker != null) {
            if (attacker instanceof PlayerEntity) {
                // Ухудшаем отношения с игроком
                memory.updatePlayerRelationship(attacker.getName().getString(), -0.2);
            }
            
            // Добавляем в список врагов если не союзник
            if (!allies.contains(attacker.getUuid())) {
                enemies.add(attacker.getUuid());
            }
        }
        
        // Эмоциональная реакция
        npc.getNPCBrain().adjustEmotion("anger", Math.min(0.3, damage / npc.getMaxHealth()));
        npc.getNPCBrain().adjustEmotion("fear", Math.min(0.2, damage / npc.getMaxHealth()));
        
        memory.addExperience("combat", String.format("Received %.1f damage from %s",
            damage, attacker != null ? attacker.getName().getString() : "unknown"));
    }
    
    public void onDamageDealt(float damage, LivingEntity target) {
        damageDealt += (int)damage;
        
        memory.addExperience("combat", String.format("Dealt %.1f damage to %s",
            damage, target.getName().getString()));
        memory.increaseSkill("combat", 1);
        
        // Положительная эмоциональная реакция при успешной атаке
        npc.getNPCBrain().adjustEmotion("confidence", 0.05);
    }
    
    public void onFightEnd(boolean won, LivingEntity opponent) {
        totalFights++;
        
        if (won) {
            fightsWon++;
            updateTacticSuccess(currentTactic.name(), true);
            memory.addExperience("combat", "Won fight against " +
                (opponent != null ? opponent.getName().getString() : "unknown"));
            
            // Увеличиваем навык и уверенность
            memory.increaseSkill("combat", 5);
            npc.getNPCBrain().adjustEmotion("happiness", 0.2);
            npc.getNPCBrain().adjustEmotion("confidence", 0.1);
            
            if (Math.random() < 0.3) {
                npc.getChatAI().say("Победа!");
            }
        } else {
            fightsLost++;
            updateTacticSuccess(currentTactic.name(), false);
            memory.addExperience("combat", "Lost fight against " +
                (opponent != null ? opponent.getName().getString() : "unknown"));
            
            // Снижаем уверенность, повышаем осторожность
            npc.getNPCBrain().adjustEmotion("sadness", 0.1);
            npc.getNPCBrain().adjustEmotion("fear", 0.1);
            memory.adjustPersonalityTrait("cautiousness", 0.05);
            
            if (Math.random() < 0.2) {
                npc.getChatAI().say("В следующий раз повезет больше...");
            }
        }
    }
    
    private void updateTacticSuccess(String tacticName, boolean success) {
        tacticSuccessRate.merge(tacticName, success ? 1 : -1, Integer::sum);
        // Ограничиваем значения от -100 до 100
        tacticSuccessRate.computeIfPresent(tacticName, (k, v) -> Math.max(-100, Math.min(100, v)));
    }
    
    // Управление союзниками и врагами
    public void addAlly(UUID entityId) {
        allies.add(entityId);
        enemies.remove(entityId);
        memory.addExperience("combat", "Added new ally: " + entityId.toString());
    }
    
    public void removeAlly(UUID entityId) {
        allies.remove(entityId);
        memory.addExperience("combat", "Removed ally: " + entityId.toString());
    }
    
    public void addEnemy(UUID entityId) {
        enemies.add(entityId);
        allies.remove(entityId);
        memory.addExperience("combat", "Added new enemy: " + entityId.toString());
    }
    
    public void removeEnemy(UUID entityId) {
        enemies.remove(entityId);
        memory.addExperience("combat", "Removed enemy: " + entityId.toString());
    }
    
    // Геттеры и сеттеры
    public LivingEntity getCurrentTarget() { return currentTarget; }
    public void setCurrentTarget(LivingEntity target) { this.currentTarget = target; }
    public CombatTactic getCurrentTactic() { return currentTactic; }
    public double getAggressionLevel() { return aggressionLevel; }
    public double getDefensiveness() { return defensiveness; }
    public int getTotalFights() { return totalFights; }
    public int getFightsWon() { return fightsWon; }
    public int getFightsLost() { return fightsLost; }
    public double getWinRate() { return totalFights > 0 ? (double)fightsWon / totalFights : 0.0; }
    
    public void setAggressionLevel(double level) {
        this.aggressionLevel = Math.max(0.0, Math.min(1.0, level));
        memory.adjustPersonalityTrait("aggression", level - this.aggressionLevel);
    }
    
    public void setDefensiveness(double level) {
        this.defensiveness = Math.max(0.0, Math.min(1.0, level));
    }
    
    public void setProtectAllies(boolean protect) {
        this.protectAllies = protect;
    }
    
    public void setAttackHostiles(boolean attack) {
        this.attackHostiles = attack;
    }
    
    public void setCanAttackPlayers(boolean canAttack) {
        this.canAttackPlayers = canAttack;
    }
    
    // Статистика
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("total_fights", totalFights);
        stats.addProperty("fights_won", fightsWon);
        stats.addProperty("fights_lost", fightsLost);
        stats.addProperty("win_rate", getWinRate());
        stats.addProperty("damage_dealt", damageDealt);
        stats.addProperty("damage_taken", damageTaken);
        stats.addProperty("current_tactic", currentTactic.name());
        stats.addProperty("aggression_level", aggressionLevel);
        stats.addProperty("defensiveness", defensiveness);
        stats.addProperty("allies_count", allies.size());
        stats.addProperty("enemies_count", enemies.size());
        stats.addProperty("engagement_range", engagementRange);
        stats.addProperty("retreat_threshold", retreatHealthThreshold);
        return stats;
    }
    
    // Внутренние классы и перечисления
    public enum CombatTactic {
        BALANCED, AGGRESSIVE, DEFENSIVE, RANGED, AMBUSH, SUPPORT
    }
    
    public static class ThreatAssessment {
        public final double threatLevel;
        public final String category;
        
        public ThreatAssessment(double threatLevel, String category) {
            this.threatLevel = threatLevel;
            this.category = category;
        }
    }
    
    // AI Goals
    private class AttackTargetGoal extends Goal {
        public AttackTargetGoal() {
            this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }
        
        @Override
        public boolean canStart() {
            return currentTarget != null && currentTarget.isAlive() && attackHostiles;
        }
        
        @Override
        public void start() {
            npc.setTarget(currentTarget);
            
            if (Math.random() < 0.2) {
                String[] battleCries = {"За победу!", "Не пройдешь!", "К бою!", "Защищу союзников!"};
                npc.getChatAI().say(battleCries[(int)(Math.random() * battleCries.length)]);
            }
        }
        
        @Override
        public void tick() {
            if (currentTarget != null) {
                double distance = npc.squaredDistanceTo(currentTarget);
                
                npc.getLookControl().lookAt(currentTarget);
                
                if (distance > engagementRange * engagementRange) {
                    // Приближаемся к цели
                    npc.getNavigation().startMovingTo(currentTarget, 1.0);
                } else if (distance < 4) {
                    // Слишком близко - отходим для маневра
                    npc.getNavigation().startMovingTo(
                        npc.getX() + (npc.getX() - currentTarget.getX()) * 0.5,
                        npc.getY(),
                        npc.getZ() + (npc.getZ() - currentTarget.getZ()) * 0.5,
                        1.0
                    );
                }
                
                // Атака в зависимости от тактики
                performTacticalAttack();
            }
        }
        
        private void performTacticalAttack() {
            switch (currentTactic) {
                case AGGRESSIVE -> {
                    // Прямая атака без остановки
                    if (npc.squaredDistanceTo(currentTarget) < 9) { // 3 блока
                        npc.tryAttack(currentTarget);
                    }
                }
                case DEFENSIVE -> {
                    // Атака только при приближении противника
                    if (npc.squaredDistanceTo(currentTarget) < 4) { // 2 блока
                        npc.tryAttack(currentTarget);
                    }
                }
                case RANGED -> {
                    // Держимся на расстоянии
                    if (npc.squaredDistanceTo(currentTarget) > 16) { // 4 блока
                        npc.getNavigation().startMovingTo(currentTarget, 0.8);
                    } else if (npc.squaredDistanceTo(currentTarget) < 9) { // 3 блока
                        // Отходим
                        BlockPos retreatPos = npc.getBlockPos().add(
                            npc.getBlockPos().getX() - currentTarget.getBlockPos().getX(),
                            0,
                            npc.getBlockPos().getZ() - currentTarget.getBlockPos().getZ()
                        );
                        npc.getNavigation().startMovingTo(retreatPos.getX(), retreatPos.getY(), retreatPos.getZ(), 1.2);
                    }
                }
                case AMBUSH -> {
                    // Внезапные атаки
                    if (npc.squaredDistanceTo(currentTarget) < 9 && Math.random() < 0.3) {
                        npc.tryAttack(currentTarget);
                    }
                }
            }
        }
        
        @Override
        public boolean shouldContinue() {
            return currentTarget != null && currentTarget.isAlive() &&
                   !shouldRetreat() && npc.squaredDistanceTo(currentTarget) < engagementRange * engagementRange * 2;
        }
        
        @Override
        public void stop() {
            npc.setTarget(null);
        }
    }
    
    private class DefendAllyGoal extends Goal {
        private NPCEntity allyToDefend;
        
        public DefendAllyGoal() {
            this.setControls(EnumSet.of(Control.MOVE, Control.TARGET));
        }
        
        @Override
        public boolean canStart() {
            if (!protectAllies) return false;
            
            // Поиск союзника под атакой
            List<NPCEntity> nearbyAllies = npc.getWorld().getEntitiesByClass(
                NPCEntity.class, npc.getBoundingBox().expand(16),
                ally -> ally != npc && allies.contains(ally.getUuid()) && ally.getTarget() != null);
            
            if (!nearbyAllies.isEmpty()) {
                allyToDefend = nearbyAllies.get(0);
                return true;
            }
            
            return false;
        }
        
        @Override
        public void start() {
            if (allyToDefend != null && allyToDefend.getTarget() != null) {
                currentTarget = allyToDefend.getTarget();
                npc.setTarget(currentTarget);
                
                if (Math.random() < 0.3) {
                    npc.getChatAI().say("Помогу союзнику!");
                }
            }
        }
        
        @Override
        public void tick() {
            if (allyToDefend != null && currentTarget != null) {
                // Движемся к союзнику
                npc.getNavigation().startMovingTo(allyToDefend, 1.2);
            }
        }
        
        @Override
        public boolean shouldContinue() {
            return allyToDefend != null && allyToDefend.isAlive() &&
                   currentTarget != null && currentTarget.isAlive() &&
                   npc.squaredDistanceTo(allyToDefend) < 256; // 16 блоков
        }
    }
    
    private class RetreatGoal extends Goal {
        private BlockPos retreatPosition;
        
        public RetreatGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return shouldRetreat();
        }
        
        @Override
        public void start() {
            retreatPosition = findSafeRetreatPosition();
            
            if (Math.random() < 0.4) {
                String[] retreatPhrases = {"Отступаю!", "Нужно лечиться!", "Тактическое отступление!"};
                npc.getChatAI().say(retreatPhrases[(int)(Math.random() * retreatPhrases.length)]);
            }
            
            memory.addExperience("combat", "Started tactical retreat");
        }
        
        @Override
        public void tick() {
            if (retreatPosition != null) {
                npc.getNavigation().startMovingTo(retreatPosition.getX(), retreatPosition.getY(), retreatPosition.getZ(), 1.5);
            }
        }
        
        private BlockPos findSafeRetreatPosition() {
            BlockPos currentPos = npc.getBlockPos();
            
            // Ищем безопасное место в радиусе 20 блоков
            for (int attempts = 0; attempts < 10; attempts++) {
                BlockPos testPos = currentPos.add(
                    (int)(Math.random() * 40) - 20,
                    0,
                    (int)(Math.random() * 40) - 20
                );
                
                if (npc.getSurvivalAI().isSafeLocation(testPos)) {
                    return testPos;
                }
            }
            
            // Если не нашли безопасное место, просто идем в сторону спавна
            return new BlockPos(0, (int)npc.getY(), 0);
        }
        
        @Override
        public boolean shouldContinue() {
            return shouldRetreat() && retreatPosition != null &&
                   npc.squaredDistanceTo(retreatPosition.getX(), retreatPosition.getY(), retreatPosition.getZ()) > 9;
        }
    }
    
    private class ProtectAllyTargetGoal extends Goal {
        public ProtectAllyTargetGoal() {
            this.setControls(EnumSet.of(Control.TARGET));
        }
        
        @Override
        public boolean canStart() {
            if (!protectAllies) return false;
            
            // Поиск союзников под атакой
            List<NPCEntity> alliesUnderAttack = npc.getWorld().getEntitiesByClass(
                NPCEntity.class, npc.getBoundingBox().expand(16),
                ally -> ally != npc && allies.contains(ally.getUuid()) &&
                       ally.getTarget() != null && ally.getHealth() < ally.getMaxHealth() * 0.7f);
            
            if (!alliesUnderAttack.isEmpty()) {
                NPCEntity ally = alliesUnderAttack.get(0);
                npc.setTarget(ally.getTarget());
                return true;
            }
            
            return false;
        }
    }
    
    private class ConditionalPlayerTargetGoal extends Goal {
        public ConditionalPlayerTargetGoal() {
            this.setControls(EnumSet.of(Control.TARGET));
        }
        
        @Override
        public boolean canStart() {
            if (!canAttackPlayers) return false;
            
            // Атакуем игроков только если они в списке врагов или атаковали нас первыми
            List<PlayerEntity> hostilePlayers = npc.getWorld().getEntitiesByClass(
                PlayerEntity.class, npc.getBoundingBox().expand(engagementRange),
                player -> enemies.contains(player.getUuid()) ||
                         (memory.getPlayerRelationship(player.getName().getString()) != null &&
                          memory.getPlayerRelationship(player.getName().getString()).getRelationshipLevel() < -0.5));
            
            if (!hostilePlayers.isEmpty()) {
                npc.setTarget(hostilePlayers.get(0));
                return true;
            }
            
            return false;
        }
    }
}