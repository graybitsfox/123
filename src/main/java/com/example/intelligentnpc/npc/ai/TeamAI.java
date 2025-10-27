
package com.example.intelligentnpc.npc.ai;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCMemory;
import com.google.gson.JsonObject;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TeamAI {
    private final NPCEntity npc;
    private final NPCMemory memory;
    
    // Командные данные
    private final Set<UUID> teamMembers = ConcurrentHashMap.newKeySet();
    private UUID teamLeader; // null если этот NPC лидер
    private String currentTeamTask = "none";
    private final Map<UUID, String> memberRoles = new ConcurrentHashMap<>();
    private final Map<String, TeamTask> activeTasks = new ConcurrentHashMap<>();
    
    // Настройки командной работы
    private boolean canLeadTeam = true;
    private boolean canFollowTeam = true;
    private int maxTeamSize = 5;
    private double leadershipSkill = 0.5;
    private double teamWorkSkill = 0.6;
    
    // Коммуникация в команде
    private final Map<UUID, Long> lastCommunication = new ConcurrentHashMap<>();
    private final List<TeamMessage> messageQueue = new ArrayList<>();
    
    // Статистика
    private int totalTeamTasks = 0;
    private int successfulTeamTasks = 0;
    private int timesLeader = 0;
    private int timesFollower = 0;
    
    public TeamAI(NPCEntity npc) {
        this.npc = npc;
        this.memory = npc.getMemory();
        
        loadTeamSkillsFromMemory();
        determineTeamRole();
    }
    
    public void registerGoals(GoalSelector goalSelector) {
        goalSelector.add(4, new FollowTeamLeaderGoal());
        goalSelector.add(5, new CoordinateTeamGoal());
        goalSelector.add(6, new AssistTeamMateGoal());
    }
    
    private void loadTeamSkillsFromMemory() {
        // Загрузка навыков командной работы из памяти
        leadershipSkill = Math.min(1.0, memory.getPersonalityTrait("confidence") + 
                         memory.getSkillLevel("communication") * 0.05);
        teamWorkSkill = Math.min(1.0, memory.getPersonalityTrait("friendliness") + 
                        memory.getSkillLevel("cooperation") * 0.05);
        
        IntelligentNPCMod.LOGGER.debug("TeamAI loaded with leadership: {}, teamwork: {}", 
            leadershipSkill, teamWorkSkill);
    }
    
    private void determineTeamRole() {
        // Определение роли в команде на основе личности и навыков
        String npcRole = npc.getRole().toLowerCase();
        
        switch (npcRole) {
            case "leader", "commander", "captain" -> {
                canLeadTeam = true;
                leadershipSkill = Math.min(1.0, leadershipSkill + 0.2);
                maxTeamSize = 8;
            }
            case "assistant", "helper", "support" -> {
                canFollowTeam = true;
                teamWorkSkill = Math.min(1.0, teamWorkSkill + 0.2);
            }
            case "scout", "explorer" -> {
                canLeadTeam = true;
                canFollowTeam = true;
                maxTeamSize = 3; // Небольшие группы разведки
            }
            case "worker", "builder" -> {
                canFollowTeam = true;
                teamWorkSkill = Math.min(1.0, teamWorkSkill + 0.1);
            }
        }
        
        memory.addExperience("teamwork", "Determined team role based on: " + npcRole);
    }
    
    public void tick() {
        // Обработка командных задач
        processTeamTasks();
        
        // Коммуникация с командой
        if (npc.age % 60 == 0) { // Каждые 3 секунды
            communicateWithTeam();
        }
        
        // Проверка состояния команды
        if (npc.age % 200 == 0) { // Каждые 10 секунд
            updateTeamStatus();
        }
        
        // Поиск новых членов команды или присоединение к существующей
        if (npc.age % 400 == 0 && teamMembers.isEmpty()) { // Каждые 20 секунд
            considerTeamFormation();
        }
    }
    
    private void processTeamTasks() {
        if (activeTasks.isEmpty()) return;
        
        // Обработка активных командных задач
        activeTasks.entrySet().removeIf(entry -> {
            TeamTask task = entry.getValue();
            
            if (task.isExpired()) {
                onTaskFailed(task);
                return true;
            }
            
            if (task.isCompleted()) {
                onTaskCompleted(task);
                return true;
            }
            
            // Продолжаем выполнение задачи
            executeTeamTask(task);
            return false;
        });
    }
    
    private void executeTeamTask(TeamTask task) {
        String myRole = memberRoles.get(npc.getUuid());
        if (myRole == null) myRole = "member";
        
        switch (task.type) {
            case "building" -> {
                if (myRole.equals("builder") || myRole.equals("leader")) {
                    // Координация строительства
                    if (npc.getBuilderAI() != null) {
                        // Делегирование строительных задач
                        coordinateBuildingTask(task);
                    }
                }
            }
            case "exploration" -> {
                if (myRole.equals("scout") || myRole.equals("leader")) {
                    coordinateExplorationTask(task);
                }
            }
            case "defense" -> {
                if (myRole.equals("guard") || myRole.equals("leader")) {
                    coordinateDefenseTask(task);
                }
            }
            case "gathering" -> {
                if (myRole.equals("worker") || myRole.equals("member")) {
                    coordinateGatheringTask(task);
                }
            }
        }
    }
    
    private void coordinateBuildingTask(TeamTask task) {
        // Координация строительной задачи между членами команды
        if (isTeamLeader()) {
            // Лидер распределяет роли
            assignBuildingRoles(task);
        } else {
            // Член команды выполняет свою часть
            executeBuildingRole(task);
        }
    }
    
    private void coordinateExplorationTask(TeamTask task) {
        if (isTeamLeader()) {
            // Назначаем направления исследования
            List<NPCEntity> teamNPCs = getTeamMemberEntities();
            int directions = Math.min(4, teamNPCs.size());
            
            for (int i = 0; i < teamNPCs.size(); i++) {
                NPCEntity member = teamNPCs.get(i);
                sendTeamMessage(member.getUuid(), "explore", 
                    "direction:" + (i % directions)); // Север, Юг, Восток, Запад
            }
        }
    }
    
    private void coordinateDefenseTask(TeamTask task) {
        if (isTeamLeader()) {
            // Организация обороны
            BlockPos defensePoint = task.targetLocation;
            List<NPCEntity> teamNPCs = getTeamMemberEntities();
            
            for (int i = 0; i < teamNPCs.size(); i++) {
                NPCEntity member = teamNPCs.get(i);
                BlockPos guardPos = defensePoint.add((i % 2) * 5 - 2, 0, (i / 2) * 5 - 2);
                sendTeamMessage(member.getUuid(), "guard", 
                    String.format("position:%d,%d,%d", guardPos.getX(), guardPos.getY(), guardPos.getZ()));
            }
        }
    }
    
    private void coordinateGatheringTask(TeamTask task) {
        // Координация сбора ресурсов
        if (isTeamLeader()) {
            // Распределяем зоны сбора
            assignGatheringAreas(task);
        } else {
            // Собираем ресурсы в назначенной зоне
            executeGatheringInArea(task);
        }
    }
    
    private void assignBuildingRoles(TeamTask task) {
        List<NPCEntity> teamNPCs = getTeamMemberEntities();
        
        for (NPCEntity member : teamNPCs) {
            String role = determineOptimalRole(member, "building");
            memberRoles.put(member.getUuid(), role);
            sendTeamMessage(member.getUuid(), "role_assignment", role + ":" + task.description);
        }
    }
    
    private void assignGatheringAreas(TeamTask task) {
        List<NPCEntity> teamNPCs = getTeamMemberEntities();
        BlockPos basePos = task.targetLocation;
        
        for (int i = 0; i < teamNPCs.size(); i++) {
            NPCEntity member = teamNPCs.get(i);
            BlockPos gatherArea = basePos.add(i * 16, 0, 0); // Зоны по 16 блоков
            sendTeamMessage(member.getUuid(), "gather_area", 
                String.format("%d,%d,%d", gatherArea.getX(), gatherArea.getY(), gatherArea.getZ()));
        }
    }
    
    private String determineOptimalRole(NPCEntity member, String taskType) {
        String memberRole = member.getRole().toLowerCase();
        
        if (taskType.equals("building")) {
            if (memberRole.contains("builder") || memberRole.contains("architect")) {
                return "chief_builder";
            } else if (memberRole.contains("miner") || memberRole.contains("worker")) {
                return "material_gatherer";
            } else {
                return "assistant";
            }
        } else if (taskType.equals("combat")) {
            if (memberRole.contains("guard") || memberRole.contains("warrior")) {
                return "frontline";
            } else if (memberRole.contains("archer") || memberRole.contains("ranger")) {
                return "ranged_support";
            } else {
                return "backup";
            }
        }
        
        return "member";
    }
    
    private void executeBuildingRole(TeamTask task) {
        String myRole = memberRoles.get(npc.getUuid());
        
        switch (myRole) {
            case "chief_builder" -> {
                // Основное строительство
                if (npc.getBuilderAI() != null) {
                    // Выполняем основные строительные задачи
                }
            }
            case "material_gatherer" -> {
                // Сбор материалов
                gatherBuildingMaterials(task.targetLocation);
            }
            case "assistant" -> {
                // Помощь главному строителю
                assistChiefBuilder();
            }
        }
    }
    
    private void executeGatheringInArea(TeamTask task) {
        // TODO: Реализовать сбор ресурсов в назначенной области
    }
    
    private void gatherBuildingMaterials(BlockPos buildLocation) {
        // TODO: Реализовать сбор строительных материалов
    }
    
    private void assistChiefBuilder() {
        // Поиск главного строителя в команде и помощь ему
        NPCEntity chiefBuilder = findTeamMemberWithRole("chief_builder");
        if (chiefBuilder != null && npc.squaredDistanceTo(chiefBuilder) > 25) {
            // Подходим к главному строителю
            npc.getNavigation().startMovingTo(chiefBuilder, 0.8);
        }
    }
    
    private NPCEntity findTeamMemberWithRole(String role) {
        for (UUID memberId : teamMembers) {
            if (role.equals(memberRoles.get(memberId))) {
                List<NPCEntity> nearbyNPCs = npc.getWorld().getEntitiesByClass(
                    NPCEntity.class, npc.getBoundingBox().expand(32), 
                    npc -> npc.getUuid().equals(memberId));
                
                if (!nearbyNPCs.isEmpty()) {
                    return nearbyNPCs.get(0);
                }
            }
        }
        return null;
    }
    
    private void communicateWithTeam() {
        // Обработка входящих сообщений
        processIncomingMessages();
        
        // Отправка статус-сообщений если мы лидер
        if (isTeamLeader() && !teamMembers.isEmpty()) {
            sendPeriodicStatusUpdates();
        }
    }
    
    private void processIncomingMessages() {
        synchronized (messageQueue) {
            messageQueue.removeIf(message -> {
                if (System.currentTimeMillis() - message.timestamp > 30000) {
                    return true; // Удаляем старые сообщения
                }
                
                handleTeamMessage(message);
                return true; // Обработали сообщение
            });
        }
    }
    
    private void handleTeamMessage(TeamMessage message) {
        switch (message.type) {
            case "role_assignment" -> {
                String[] parts = message.content.split(":", 2);
                if (parts.length == 2) {
                    memberRoles.put(npc.getUuid(), parts[0]);
                    memory.addExperience("teamwork", "Assigned role: " + parts[0]);
                }
            }
            case "task_update" -> {
                memory.addExperience("teamwork", "Received task update: " + message.content);
            }
            case "help_request" -> {
                // Запрос о помощи от члена команды
                respondToHelpRequest(message);
            }
            case "status_report" -> {
                // Отчет о статусе от члена команды
                processStatusReport(message);
            }
        }
    }
    
    private void respondToHelpRequest(TeamMessage message) {
        if (canAssistTeammate()) {
            UUID requesterId = message.senderId;
            NPCEntity requester = findNPCById(requesterId);
            
            if (requester != null && npc.squaredDistanceTo(requester) < 400) { // В радиусе 20 блоков
                // Идем помочь
                npc.getNavigation().startMovingTo(requester, 1.0);
                sendTeamMessage(requesterId, "help_coming", "On my way!");
                
                memory.addExperience("teamwork", "Responding to help request");
            }
        }
    }
    
    private void processStatusReport(TeamMessage message) {
        // Обработка отчета о статусе от члена команды (если мы лидер)
        if (isTeamLeader()) {
            String[] parts = message.content.split(":");
            if (parts.length >= 2) {
                String status = parts[0];
                String details = parts[1];
                
                // Обновляем информацию о состоянии члена команды
                updateMemberStatus(message.senderId, status, details);
            }
        }
    }
    
    private void updateMemberStatus(UUID memberId, String status, String details) {
        // TODO: Сохранение статуса члена команды для планирования задач
    }
    
    private void sendPeriodicStatusUpdates() {
        // Периодические обновления для команды
        long currentTime = System.currentTimeMillis();
        
        for (UUID memberId : teamMembers) {
            if (currentTime - lastCommunication.getOrDefault(memberId, 0L) > 30000) { // 30 секунд
                sendTeamMessage(memberId, "status_check", "How are you doing?");
            }
        }
    }
    
    private void updateTeamStatus() {
        // Удаление неактивных членов команды
        teamMembers.removeIf(memberId -> {
            NPCEntity member = findNPCById(memberId);
            return member == null || !member.isAlive() || npc.squaredDistanceTo(member) > 1024; // 32 блока
        });
        
        // Если команда стала слишком маленькой, пытаемся найти новых членов
        if (teamMembers.size() < 2 && canLeadTeam) {
            considerTeamFormation();
        }
    }
    
    private void considerTeamFormation() {
        // Поиск ближайших NPC для формирования команды или присоединения к существующей
        List<NPCEntity> nearbyNPCs = npc.getWorld().getEntitiesByClass(
            NPCEntity.class, npc.getBoundingBox().expand(16),
            other -> other != npc && other.getTeamAI().getTeamMembers().size() < maxTeamSize);
        
        if (nearbyNPCs.isEmpty()) return;
        
        // Попытка присоединиться к существующей команде
        for (NPCEntity otherNPC : nearbyNPCs) {
            if (!otherNPC.getTeamAI().teamMembers.isEmpty()) {
                requestToJoinTeam(otherNPC);
                return;
            }
        }
        
        // Создание новой команды если подходящих не найдено
        if (canLeadTeam && nearbyNPCs.size() >= 1) {
            formNewTeam(nearbyNPCs);
        }
    }
    
    private void requestToJoinTeam(NPCEntity potentialTeam) {
        UUID leaderId = potentialTeam.getTeamAI().teamLeader;
        if (leaderId != null) {
            NPCEntity leader = findNPCById(leaderId);
            if (leader != null) {
                leader.getTeamAI().receiveJoinRequest(npc.getUuid());
            }
        } else {
            // Сам NPC является лидером
            potentialTeam.getTeamAI().receiveJoinRequest(npc.getUuid());
        }
    }
    
    public void formNewTeam() {
        teamLeader = null; // Мы становимся лидером
        timesLeader++;
        
        // Добавляем себя в команду
        teamMembers.add(npc.getUuid());
        memberRoles.put(npc.getUuid(), "leader");
        
        memory.addExperience("teamwork", "Formed a new team");
        npc.getChatAI().say("Создаю новую команду!");
    }

    private void formNewTeam(List<NPCEntity> candidates) {
        formNewTeam();

        // Приглашаем других NPC
        int inviteCount = Math.min(candidates.size(), maxTeamSize - 1);
        for (int i = 0; i < inviteCount; i++) {
            NPCEntity candidate = candidates.get(i);
            sendTeamInvitation(candidate);
        }
        
        memory.addExperience("teamwork", String.format("Invited %d members to the new team", inviteCount));
    }
    
    private void sendTeamInvitation(NPCEntity candidate) {
        candidate.getTeamAI().receiveTeamInvitation(npc.getUuid(), "general");
    }
    
    public void receiveTeamInvitation(UUID inviterId, String teamType) {
        // Получение приглашения в команду
        if (teamMembers.isEmpty() && canFollowTeam) {
            acceptTeamInvitation(inviterId, teamType);
        }
    }
    
    public void receiveJoinRequest(UUID requesterId) {
        // Получение запроса на вступление в команду
        if (isTeamLeader() && teamMembers.size() < maxTeamSize) {
            acceptTeamMember(requesterId);
        }
    }
    
    private void acceptTeamInvitation(UUID leaderId, String teamType) {
        teamLeader = leaderId;
        teamMembers.clear();
        teamMembers.add(npc.getUuid());
        
        // Уведомляем лидера о принятии приглашения
        NPCEntity leader = findNPCById(leaderId);
        if (leader != null) {
            leader.getTeamAI().onMemberJoined(npc.getUuid());
        }
        
        timesFollower++;
        memory.addExperience("teamwork", "Joined team led by " + leaderId.toString());
        npc.getChatAI().say("Присоединяюсь к команде!");
    }
    
    private void acceptTeamMember(UUID memberId) {
        teamMembers.add(memberId);
        
        // Назначаем роль новому члену команды
        NPCEntity newMember = findNPCById(memberId);
        if (newMember != null) {
            String role = determineOptimalRole(newMember, currentTeamTask);
            memberRoles.put(memberId, role);
            
            sendTeamMessage(memberId, "welcome", "Welcome to the team! Your role: " + role);
        }
        
        memory.addExperience("teamwork", "Accepted new team member: " + memberId.toString());
    }
    
    public void onMemberJoined(UUID memberId) {
        if (!teamMembers.contains(memberId)) {
            teamMembers.add(memberId);
            memberRoles.put(memberId, "member");
        }
    }

    public boolean addMember(NPCEntity member) {
        if (!isTeamLeader() || teamMembers.size() >= maxTeamSize) {
            return false;
        }
        teamMembers.add(member.getUuid());
        member.getTeamAI().joinTeam(this.npc);
        return true;
    }

    public void joinTeam(NPCEntity leader) {
        this.teamLeader = leader.getUuid();
    }

    public void leaveTeam() {
        if (isTeamLeader()) {
            // Распускаем команду
            broadcastTeamMessage("team_disbanded", "The team has been disbanded.");
            teamMembers.clear();
            memberRoles.clear();
        } else {
            // Покидаем команду
            NPCEntity leader = findNPCById(teamLeader);
            if (leader != null) {
                leader.getTeamAI().removeMember(npc.getUuid());
            }
            this.teamLeader = null;
        }
    }

    public void removeMember(UUID memberId) {
        teamMembers.remove(memberId);
        memberRoles.remove(memberId);
    }
    
    private void onTaskCompleted(TeamTask task) {
        totalTeamTasks++;
        successfulTeamTasks++;
        
        memory.addExperience("teamwork", "Completed team task: " + task.type);
        memory.increaseSkill("cooperation", 3);
        
        // Уведомление команды
        if (isTeamLeader()) {
            broadcastTeamMessage("task_completed", "Great job everyone! Task completed: " + task.description);
            npc.getChatAI().say("Отличная работа, команда!");
        }
    }
    
    private void onTaskFailed(TeamTask task) {
        totalTeamTasks++;
        
        memory.addExperience("teamwork", "Failed team task: " + task.type);
        
        if (isTeamLeader()) {
            broadcastTeamMessage("task_failed", "Task failed, let's regroup: " + task.description);
            npc.getChatAI().say("Не получилось, но мы попробуем снова!");
        }
    }
    
    // Utility методы
    private boolean isTeamLeader() {
        return teamLeader == null && !teamMembers.isEmpty();
    }
    
    private boolean canAssistTeammate() {
        return !npc.getNPCBrain().getCurrentGoal().equals("combat") &&
               !npc.getNPCBrain().getCurrentGoal().equals("retreat");
    }
    
    private NPCEntity findNPCById(UUID id) {
        List<NPCEntity> allNPCs = npc.getWorld().getEntitiesByClass(
            NPCEntity.class, npc.getBoundingBox().expand(64),
            npcEntity -> npcEntity.getUuid().equals(id));
        
        return allNPCs.isEmpty() ? null : allNPCs.get(0);
    }
    
    private List<NPCEntity> getTeamMemberEntities() {
        List<NPCEntity> members = new ArrayList<>();
        
        for (UUID memberId : teamMembers) {
            NPCEntity member = findNPCById(memberId);
            if (member != null) {
                members.add(member);
            }
        }
        
        return members;
    }
    
    private void sendTeamMessage(UUID targetId, String type, String content) {
        NPCEntity target = findNPCById(targetId);
        if (target != null) {
            target.getTeamAI().receiveTeamMessage(new TeamMessage(npc.getUuid(), type, content));
            lastCommunication.put(targetId, System.currentTimeMillis());
        }
    }
    
    private void broadcastTeamMessage(String type, String content) {
        for (UUID memberId : teamMembers) {
            if (!memberId.equals(npc.getUuid())) {
                sendTeamMessage(memberId, type, content);
            }
        }
    }
    
    public void receiveTeamMessage(TeamMessage message) {
        synchronized (messageQueue) {
            messageQueue.add(message);
        }
    }
    
    // Публичные методы для создания задач
    public void assignTeamTask(String taskType, BlockPos location, String description) {
        if (!isTeamLeader()) return;
        
        TeamTask task = new TeamTask(taskType, location, description);
        activeTasks.put(taskType, task);
        currentTeamTask = taskType;
        
        // Уведомляем команду о новой задаче
        broadcastTeamMessage("new_task", taskType + ":" + description);
        
        memory.addExperience("teamwork", "Assigned team task: " + taskType);
    }
    
    // Геттеры и сеттеры
    public Set<UUID> getTeamMembers() { return new HashSet<>(teamMembers); }
    public boolean isInTeam() { return !teamMembers.isEmpty(); }
    public UUID getTeamLeader() { return teamLeader; }
    public String getCurrentTeamTask() { return currentTeamTask; }
    public int getTotalTeamTasks() { return totalTeamTasks; }
    public int getSuccessfulTeamTasks() { return successfulTeamTasks; }
    public double getTeamSuccessRate() {
        return totalTeamTasks > 0 ? (double)successfulTeamTasks / totalTeamTasks : 0.0;
    }
    
    public void setCanLeadTeam(boolean canLead) {
        this.canLeadTeam = canLead;
    }
    
    public void setCanFollowTeam(boolean canFollow) {
        this.canFollowTeam = canFollow;
    }
    
    public void setMaxTeamSize(int maxSize) {
        this.maxTeamSize = Math.max(2, Math.min(10, maxSize));
    }
    
    // Статистика
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("team_members_count", teamMembers.size());
        stats.addProperty("is_leader", isTeamLeader());
        stats.addProperty("team_leader_id", teamLeader != null ? teamLeader.toString() : null);
        stats.addProperty("current_team_task", currentTeamTask);
        stats.addProperty("total_team_tasks", totalTeamTasks);
        stats.addProperty("successful_team_tasks", successfulTeamTasks);
        stats.addProperty("team_success_rate", getTeamSuccessRate());
        stats.addProperty("times_leader", timesLeader);
        stats.addProperty("times_follower", timesFollower);
        stats.addProperty("leadership_skill", leadershipSkill);
        stats.addProperty("teamwork_skill", teamWorkSkill);
        stats.addProperty("active_tasks", activeTasks.size());
        return stats;
    }
    
    // Внутренние классы
    public static class TeamTask {
        public final String type;
        public final BlockPos targetLocation;
        public final String description;
        public final long creationTime;
        public final long maxDuration;
        public boolean completed = false;
        
        public TeamTask(String type, BlockPos targetLocation, String description) {
            this.type = type;
            this.targetLocation = targetLocation;
            this.description = description;
            this.creationTime = System.currentTimeMillis();
            this.maxDuration = 600000; // 10 минут по умолчанию
        }
        
        public boolean isCompleted() {
            return completed;
        }
        
        public boolean isExpired() {
            return (System.currentTimeMillis() - creationTime) > maxDuration;
        }
        
        public void markCompleted() {
            completed = true;
        }
    }
    
    public static class TeamMessage {
        public final UUID senderId;
        public final String type;
        public final String content;
        public final long timestamp;
        
        public TeamMessage(UUID senderId, String type, String content) {
            this.senderId = senderId;
            this.type = type;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
    }
    
    // AI Goals
    private class FollowTeamLeaderGoal extends Goal {
        public FollowTeamLeaderGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return teamLeader != null && !isTeamLeader();
        }
        
        @Override
        public void tick() {
            NPCEntity leader = findNPCById(teamLeader);
            if (leader != null) {
                double distance = npc.squaredDistanceTo(leader);
                
                if (distance > 64) { // 8 блоков
                    npc.getNavigation().startMovingTo(leader, 1.0);
                } else if (distance < 9) { // 3 блока - слишком близко
                    // Отходим немного
                    BlockPos pos = npc.getBlockPos().add(
                        npc.getX() > leader.getX() ? 2 : -2,
                        0,
                        npc.getZ() > leader.getZ() ? 2 : -2
                    );
                    npc.getNavigation().startMovingTo(pos.getX(), pos.getY(), pos.getZ(), 0.8);
                }
            }
        }
        
        @Override
        public boolean shouldContinue() {
            NPCEntity leader = findNPCById(teamLeader);
            return leader != null && leader.isAlive() && npc.squaredDistanceTo(leader) < 1024; // 32 блока
        }
    }
    
    private class CoordinateTeamGoal extends Goal {
        public CoordinateTeamGoal() {
            this.setControls(EnumSet.of(Control.LOOK));
        }
        
        @Override
        public boolean canStart() {
            return isTeamLeader() && !activeTasks.isEmpty();
        }
        
        @Override
        public void tick() {
            // Координация выполнения задач
            for (TeamTask task : activeTasks.values()) {
                if (!task.isCompleted() && !task.isExpired()) {
                    executeTeamTask(task);
                }
            }
        }
    }
    
    private class AssistTeamMateGoal extends Goal {
        private NPCEntity teammateToAssist;
        
        public AssistTeamMateGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            if (!isInTeam()) return false;
            
            // Ищем члена команды, который нуждается в помощи
            for (UUID memberId : teamMembers) {
                if (memberId.equals(npc.getUuid())) continue;
                
                NPCEntity member = findNPCById(memberId);
                if (member != null && needsAssistance(member)) {
                    teammateToAssist = member;
                    return true;
                }
            }
            
            return false;
        }
        
        private boolean needsAssistance(NPCEntity member) {
            // Проверяем, нужна ли помощь члену команды
            return member.getHealth() < member.getMaxHealth() * 0.5f ||
                   (member.getTarget() != null && member.getTarget().isAlive());
        }
        
        @Override
        public void start() {
            if (teammateToAssist != null) {
                sendTeamMessage(teammateToAssist.getUuid(), "help_coming", "Coming to assist!");
                memory.addExperience("teamwork", "Assisting teammate");
            }
        }
        
        @Override
        public void tick() {
            if (teammateToAssist != null) {
                npc.getNavigation().startMovingTo(teammateToAssist, 1.2);
                
                // Если добрались до товарища, помогаем в бою
                if (npc.squaredDistanceTo(teammateToAssist) < 16) {
                    LivingEntity teammateTarget = teammateToAssist.getTarget();
                    if (teammateTarget != null && teammateTarget.isAlive()) {
                        npc.setTarget(teammateTarget);
                    }
                }
            }
        }
        
        @Override
        public boolean shouldContinue() {
            return teammateToAssist != null && teammateToAssist.isAlive() &&
                   npc.squaredDistanceTo(teammateToAssist) < 400 && // 20 блоков
                   needsAssistance(teammateToAssist);
        }
    }
}