package com.example.intelligentnpc.npc.ai;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCMemory;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BuilderAI {
    private final NPCEntity npc;
    private final NPCMemory memory;
    private final World world;
    
    // Текущие проекты и состояние
    private BuildingProject currentProject;
    private final Queue<BuildingTask> buildingQueue = new LinkedList<>();
    private final Map<String, BuildingTemplate> knownTemplates = new ConcurrentHashMap<>();
    
    // Настройки строительства
    private int maxBuildingRange = 32; // Максимальное расстояние для строительства
    private int blocksPerTick = 2; // Блоков за тик при активном строительстве
    private boolean autoGatherMaterials = true; // Автоматический сбор материалов
    private boolean allowTerrainModification = true; // Разрешена ли модификация ландшафта
    
    // Статистика
    private int totalBlocksPlaced = 0;
    private int totalProjectsCompleted = 0;
    private int totalBlocksBroken = 0;
    
    // Безопасность и ограничения
    private final Set<Block> forbiddenBlocks = Set.of(
        Blocks.BEDROCK, Blocks.COMMAND_BLOCK, Blocks.CHAIN_COMMAND_BLOCK,
        Blocks.REPEATING_COMMAND_BLOCK, Blocks.BARRIER
    );
    
    public BuilderAI(NPCEntity npc) {
        IntelligentNPCMod.LOGGER.debug("=== Creating BuilderAI ===");
        IntelligentNPCMod.LOGGER.debug("NPC: {}", npc != null ? npc.getNpcName() : "NULL");
        
        this.npc = npc;
        
        if (npc == null) {
            IntelligentNPCMod.LOGGER.error("CRITICAL: NPC is null in BuilderAI constructor!");
            throw new IllegalArgumentException("NPC cannot be null");
        }
        
        IntelligentNPCMod.LOGGER.debug("Getting memory from NPC '{}'...", npc.getNpcName());
        this.memory = npc.getMemory();
        IntelligentNPCMod.LOGGER.debug("Memory state: {}", memory != null ? "EXISTS" : "NULL");
        
        if (memory == null) {
            IntelligentNPCMod.LOGGER.error("CRITICAL: Memory is null for NPC '{}'", npc.getNpcName());
            throw new IllegalStateException("Memory cannot be null for BuilderAI of " + npc.getNpcName());
        }
        
        IntelligentNPCMod.LOGGER.debug("Getting world from NPC '{}'...", npc.getNpcName());
        this.world = npc.getWorld();
        IntelligentNPCMod.LOGGER.debug("World state: {}", world != null ? "EXISTS" : "NULL");
        
        try {
            IntelligentNPCMod.LOGGER.debug("Initializing known templates for '{}'...", npc.getNpcName());
            initializeKnownTemplates();
            IntelligentNPCMod.LOGGER.debug("✓ Known templates initialized for '{}'", npc.getNpcName());
            
            IntelligentNPCMod.LOGGER.debug("Loading building skills from memory for '{}'...", npc.getNpcName());
            loadBuildingSkillsFromMemory();
            IntelligentNPCMod.LOGGER.debug("✓ Building skills loaded for '{}'", npc.getNpcName());
            
            IntelligentNPCMod.LOGGER.info("✓ BuilderAI created successfully for '{}'", npc.getNpcName());
            
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("=== FAILED to create BuilderAI for '{}' ===", npc.getNpcName(), e);
            IntelligentNPCMod.LOGGER.error("Memory: {}, World: {}", memory != null, world != null);
            IntelligentNPCMod.LOGGER.error("Error: {}", e.getMessage());
            IntelligentNPCMod.LOGGER.error("Stack trace:", e);
            throw e; // Re-throw to prevent broken AI
        }
    }
    
    public void registerGoals(GoalSelector goalSelector) {
        goalSelector.add(3, new BuildingGoal());
        goalSelector.add(4, new MaterialGatheringGoal());
    }
    
    private void initializeKnownTemplates() {
        // Простые шаблоны строительства
        knownTemplates.put("house", createHouseTemplate());
        knownTemplates.put("tower", createTowerTemplate());
        knownTemplates.put("bridge", createBridgeTemplate());
        knownTemplates.put("wall", createWallTemplate());
        knownTemplates.put("farm", createFarmTemplate());
        knownTemplates.put("mine_entrance", createMineEntranceTemplate());
        
        IntelligentNPCMod.LOGGER.debug("BuilderAI initialized with {} templates", knownTemplates.size());
    }
    
    private BuildingTemplate createHouseTemplate() {
        BuildingTemplate template = new BuildingTemplate("house", "Простой дом", 7, 6, 7);
        
        // Фундамент
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                template.addBlock(x, 0, z, Blocks.COBBLESTONE.getDefaultState());
            }
        }
        
        // Стены
        for (int y = 1; y <= 4; y++) {
            // Внешние стены
            for (int x = 0; x < 7; x++) {
                template.addBlock(x, y, 0, Blocks.COBBLESTONE.getDefaultState()); // Северная стена
                template.addBlock(x, y, 6, Blocks.COBBLESTONE.getDefaultState()); // Южная стена
            }
            for (int z = 1; z < 6; z++) {
                template.addBlock(0, y, z, Blocks.COBBLESTONE.getDefaultState()); // Западная стена
                template.addBlock(6, y, z, Blocks.COBBLESTONE.getDefaultState()); // Восточная стена
            }
        }
        
        // Дверь
        template.addBlock(3, 1, 0, Blocks.AIR.getDefaultState());
        template.addBlock(3, 2, 0, Blocks.AIR.getDefaultState());
        
        // Окна
        template.addBlock(1, 2, 0, Blocks.GLASS.getDefaultState());
        template.addBlock(5, 2, 0, Blocks.GLASS.getDefaultState());
        template.addBlock(0, 2, 2, Blocks.GLASS.getDefaultState());
        template.addBlock(6, 2, 2, Blocks.GLASS.getDefaultState());
        
        // Крыша
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                template.addBlock(x, 5, z, Blocks.OAK_PLANKS.getDefaultState());
            }
        }
        
        return template;
    }
    
    private BuildingTemplate createTowerTemplate() {
        BuildingTemplate template = new BuildingTemplate("tower", "Сторожевая башня", 5, 12, 5);
        
        // Основание
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                template.addBlock(x, 0, z, Blocks.STONE_BRICKS.getDefaultState());
            }
        }
        
        // Стены башни
        for (int y = 1; y <= 10; y++) {
            for (int x = 0; x < 5; x++) {
                template.addBlock(x, y, 0, Blocks.STONE_BRICKS.getDefaultState());
                template.addBlock(x, y, 4, Blocks.STONE_BRICKS.getDefaultState());
            }
            for (int z = 1; z < 4; z++) {
                template.addBlock(0, y, z, Blocks.STONE_BRICKS.getDefaultState());
                template.addBlock(4, y, z, Blocks.STONE_BRICKS.getDefaultState());
            }
            
            // Окна на разных уровнях
            if (y == 3 || y == 6 || y == 9) {
                template.addBlock(2, y, 0, Blocks.GLASS.getDefaultState());
                template.addBlock(0, y, 2, Blocks.GLASS.getDefaultState());
                template.addBlock(2, y, 4, Blocks.GLASS.getDefaultState());
                template.addBlock(4, y, 2, Blocks.GLASS.getDefaultState());
            }
        }
        
        // Крыша с зубцами
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                if ((x + z) % 2 == 0) {
                    template.addBlock(x, 11, z, Blocks.STONE_BRICKS.getDefaultState());
                }
            }
        }
        
        return template;
    }
    
    private BuildingTemplate createBridgeTemplate() {
        BuildingTemplate template = new BuildingTemplate("bridge", "Мост", 3, 3, 10);
        
        // Основа моста
        for (int z = 0; z < 10; z++) {
            for (int x = 0; x < 3; x++) {
                template.addBlock(x, 0, z, Blocks.OAK_PLANKS.getDefaultState());
            }
        }
        
        // Перила
        for (int z = 0; z < 10; z++) {
            template.addBlock(0, 1, z, Blocks.OAK_FENCE.getDefaultState());
            template.addBlock(2, 1, z, Blocks.OAK_FENCE.getDefaultState());
        }
        
        return template;
    }
    
    private BuildingTemplate createWallTemplate() {
        BuildingTemplate template = new BuildingTemplate("wall", "Защитная стена", 1, 4, 10);
        
        for (int z = 0; z < 10; z++) {
            for (int y = 0; y < 4; y++) {
                template.addBlock(0, y, z, Blocks.COBBLESTONE.getDefaultState());
            }
        }
        
        return template;
    }
    
    private BuildingTemplate createFarmTemplate() {
        BuildingTemplate template = new BuildingTemplate("farm", "Небольшая ферма", 9, 1, 9);
        
        // Вода в центре
        template.addBlock(4, 0, 4, Blocks.WATER.getDefaultState());
        
        // Грядки вокруг
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                if (x != 4 || z != 4) { // Не в центре
                    template.addBlock(x, 0, z, Blocks.FARMLAND.getDefaultState());
                }
            }
        }
        
        return template;
    }
    
    private BuildingTemplate createMineEntranceTemplate() {
        BuildingTemplate template = new BuildingTemplate("mine_entrance", "Вход в шахту", 5, 5, 3);
        
        // Каркас входа
        for (int y = 0; y < 5; y++) {
            template.addBlock(0, y, 0, Blocks.STONE_BRICKS.getDefaultState());
            template.addBlock(4, y, 0, Blocks.STONE_BRICKS.getDefaultState());
            if (y == 4) {
                for (int x = 1; x < 4; x++) {
                    template.addBlock(x, y, 0, Blocks.STONE_BRICKS.getDefaultState());
                }
            }
        }
        
        // Спуск
        for (int z = 1; z < 3; z++) {
            template.addBlock(1, 3-z, z, Blocks.COBBLESTONE_STAIRS.getDefaultState());
            template.addBlock(2, 3-z, z, Blocks.AIR.getDefaultState());
            template.addBlock(3, 3-z, z, Blocks.COBBLESTONE_STAIRS.getDefaultState());
        }
        
        return template;
    }
    
    public void tick() {
        // Обработка текущего проекта
        if (currentProject != null) {
            processCurrentProject();
        } else if (!buildingQueue.isEmpty()) {
            startNextProject();
        }
        
        // Автоматический сбор материалов если нужно
        if (autoGatherMaterials && currentProject != null) {
            checkMaterialsNeeded();
        }
    }
    
    public void handleBuildCommand(String command, ServerPlayerEntity player) {
        try {
            BuildingRequest request = parseBuildCommand(command, player);
            if (request != null) {
                queueBuildingProject(request, player);
            }
        } catch (Exception e) {
            player.sendMessage(Text.literal("§cОшибка при обработке команды строительства: " + e.getMessage()), false);
            IntelligentNPCMod.LOGGER.error("Error processing build command: {}", e.getMessage());
        }
    }
    
    private BuildingRequest parseBuildCommand(String command, ServerPlayerEntity player) {
        String lowerCommand = command.toLowerCase();
        BlockPos playerPos = player.getBlockPos();
        
        // Определение типа постройки
        String structureType = "house"; // По умолчанию
        if (lowerCommand.contains("башн") || lowerCommand.contains("tower")) {
            structureType = "tower";
        } else if (lowerCommand.contains("мост") || lowerCommand.contains("bridge")) {
            structureType = "bridge";
        } else if (lowerCommand.contains("стен") || lowerCommand.contains("wall")) {
            structureType = "wall";
        } else if (lowerCommand.contains("ферм") || lowerCommand.contains("farm")) {
            structureType = "farm";
        } else if (lowerCommand.contains("шахт") || lowerCommand.contains("mine")) {
            structureType = "mine_entrance";
        }
        
        // Определение материала
        Block material = Blocks.COBBLESTONE;
        if (lowerCommand.contains("дуб") || lowerCommand.contains("oak")) {
            material = Blocks.OAK_PLANKS;
        } else if (lowerCommand.contains("камен") || lowerCommand.contains("stone")) {
            material = Blocks.STONE;
        } else if (lowerCommand.contains("кирпич") || lowerCommand.contains("brick")) {
            material = Blocks.STONE_BRICKS;
        }
        
        // Определение позиции
        BlockPos buildPos;
        if (lowerCommand.contains("здесь") || lowerCommand.contains("here")) {
            buildPos = playerPos;
        } else if (lowerCommand.contains("туда") || lowerCommand.contains("there")) {
            // TODO: Реализовать определение направления взгляда игрока
            buildPos = playerPos.offset(Direction.NORTH, 5);
        } else {
            buildPos = npc.getBlockPos().offset(Direction.NORTH, 3);
        }
        
        return new BuildingRequest(structureType, buildPos, material, player.getUuid());
    }
    
    private void queueBuildingProject(BuildingRequest request, ServerPlayerEntity player) {
        BuildingTemplate template = knownTemplates.get(request.structureType);
        if (template == null) {
            player.sendMessage(Text.literal("§cНе знаю, как строить: " + request.structureType), false);
            return;
        }
        
        // Проверка разрешений и ограничений
        if (!canBuildAt(request.location)) {
            player.sendMessage(Text.literal("§cНе могу строить в этом месте"), false);
            return;
        }
        
        // Создание проекта
        BuildingProject project = new BuildingProject(template, request.location, request.material, request.requesterId);
        
        if (currentProject == null) {
            currentProject = project;
            player.sendMessage(Text.literal("§aНачинаю строительство " + template.name), false);
            memory.recordBuildingProject(template.name, request.location, template.description, request.material.toString());
        } else {
            buildingQueue.offer(new BuildingTask(project, System.currentTimeMillis()));
            player.sendMessage(Text.literal("§eПроект добавлен в очередь: " + template.name), false);
        }
    }
    
    private boolean canBuildAt(BlockPos location) {
        // Проверка расстояния
        if (npc.getBlockPos().getManhattanDistance(location) > maxBuildingRange) {
            return false;
        }
        
        // Проверка доступности места
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 0; y <= 5; y++) {
                    BlockPos checkPos = location.add(x, y, z);
                    BlockState state = world.getBlockState(checkPos);
                    
                    if (forbiddenBlocks.contains(state.getBlock())) {
                        return false;
                    }
                }
            }
        }
        
        return true;
    }
    
    private void processCurrentProject() {
        if (currentProject.isCompleted()) {
            completeCurrentProject();
            return;
        }
        
        // Строим несколько блоков за тик
        for (int i = 0; i < blocksPerTick && !currentProject.isCompleted(); i++) {
            placeNextBlock();
        }
    }
    
    private void placeNextBlock() {
        BuildingProject.BlockPlacement nextBlock = currentProject.getNextBlock();
        if (nextBlock == null) {
            return;
        }
        
        BlockPos targetPos = currentProject.baseLocation.add(nextBlock.relativePos);
        
        // Проверка безопасности
        if (!canPlaceBlockAt(targetPos, nextBlock.blockState)) {
            currentProject.markBlockAsFailed(nextBlock);
            return;
        }
        
        // Проверка наличия материалов
        if (!hasRequiredMaterial(nextBlock.blockState.getBlock())) {
            // Попытка найти альтернативный материал
            Block alternative = findAlternativeMaterial(nextBlock.blockState.getBlock());
            if (alternative != null) {
                nextBlock = new BuildingProject.BlockPlacement(nextBlock.relativePos, alternative.getDefaultState());
            } else {
                currentProject.markBlockAsPending(nextBlock);
                return;
            }
        }
        
        // Размещение блока
        try {
            world.setBlockState(targetPos, nextBlock.blockState);
            currentProject.markBlockAsPlaced(nextBlock);
            totalBlocksPlaced++;
            
            // Опыт и память
            memory.addExperience("building", "Placed block: " + nextBlock.blockState.getBlock().toString());
            memory.increaseSkill("building", 1);
            
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("Error placing block at {}: {}", targetPos, e.getMessage());
            currentProject.markBlockAsFailed(nextBlock);
        }
    }
    
    private boolean canPlaceBlockAt(BlockPos pos, BlockState newState) {
        // Проверка границ мира
        if (!world.isInBuildLimit(pos)) {
            return false;
        }
        
        // Проверка текущего блока
        BlockState currentState = world.getBlockState(pos);
        
        // Не заменяем важные блоки
        if (forbiddenBlocks.contains(currentState.getBlock())) {
            return false;
        }
        
        // Не размещаем блоки в жидкостях (кроме случаев, когда это нужно)
        if (currentState.getBlock() == Blocks.WATER || currentState.getBlock() == Blocks.LAVA) {
            return newState.getBlock() != Blocks.AIR;
        }
        
        return true;
    }
    
    private boolean hasRequiredMaterial(Block block) {
        // TODO: Реализовать проверку инвентаря NPC
        // Пока что считаем, что материалы всегда есть
        return true;
    }
    
    private Block findAlternativeMaterial(Block originalBlock) {
        // Простая замена материалов
        if (originalBlock == Blocks.OAK_PLANKS) {
            return Blocks.BIRCH_PLANKS;
        }
        if (originalBlock == Blocks.STONE_BRICKS) {
            return Blocks.COBBLESTONE;
        }
        if (originalBlock == Blocks.GLASS) {
            return null; // Стекло не заменяем
        }
        
        return Blocks.COBBLESTONE; // Универсальная замена
    }
    
    private void completeCurrentProject() {
        if (currentProject == null) return;
        
        String projectName = currentProject.template.name;
        totalProjectsCompleted++;
        
        // Сохранение в память
        memory.completeBuildingProject(projectName);
        memory.addExperience("building", "Completed project: " + projectName);
        memory.increaseSkill("building", 10);
        
        // Уведомление
        npc.getChatAI().say("Завершил строительство: " + projectName);
        
        // Сброс текущего проекта
        currentProject = null;
        
        IntelligentNPCMod.LOGGER.info("NPC {} completed building project: {}", npc.getNpcName(), projectName);
    }
    
    private void startNextProject() {
        BuildingTask nextTask = buildingQueue.poll();
        if (nextTask != null) {
            currentProject = nextTask.project;
            npc.getChatAI().say("Начинаю новый проект: " + currentProject.template.name);
        }
    }
    
    private void checkMaterialsNeeded() {
        // TODO: Реализовать проверку и автоматический сбор материалов
    }
    
    private void loadBuildingSkillsFromMemory() {
        IntelligentNPCMod.LOGGER.debug("=== loadBuildingSkillsFromMemory() START for '{}' ===", npc != null ? npc.getNpcName() : "UNKNOWN");
        
        if (memory == null) {
            IntelligentNPCMod.LOGGER.error("CRITICAL: Memory is null in loadBuildingSkillsFromMemory()!");
            IntelligentNPCMod.LOGGER.error("NPC: {}", npc != null ? npc.getNpcName() : "NULL");
            IntelligentNPCMod.LOGGER.error("Using default building skill values");
            return;
        }
        
        try {
            IntelligentNPCMod.LOGGER.debug("Getting building skill level from memory...");
            int buildingSkill = memory.getSkillLevel("building");
            IntelligentNPCMod.LOGGER.debug("Building skill level: {}", buildingSkill);
            
            // Настройка параметров на основе навыка
            IntelligentNPCMod.LOGGER.debug("Configuring parameters based on skill level...");
            if (buildingSkill >= 10) {
                blocksPerTick = 3;
                maxBuildingRange = 48;
                IntelligentNPCMod.LOGGER.debug("Applied level 10+ bonuses: blocksPerTick=3, range=48");
            }
            if (buildingSkill >= 20) {
                blocksPerTick = 4;
                maxBuildingRange = 64;
                IntelligentNPCMod.LOGGER.debug("Applied level 20+ bonuses: blocksPerTick=4, range=64");
            }
            
            IntelligentNPCMod.LOGGER.info("✓ BuilderAI loaded with building skill level: {} for '{}'",
                buildingSkill, npc.getNpcName());
                
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("=== ERROR in loadBuildingSkillsFromMemory() ===", e);
            IntelligentNPCMod.LOGGER.error("NPC: {}", npc != null ? npc.getNpcName() : "NULL");
            IntelligentNPCMod.LOGGER.error("Memory: {}", memory != null ? "EXISTS" : "NULL");
            IntelligentNPCMod.LOGGER.error("Error: {}", e.getMessage());
            IntelligentNPCMod.LOGGER.error("Stack trace:", e);
            // Не бросаем исключение, используем значения по умолчанию
        }
    }
    
    // Геттеры и сеттеры
    public BuildingProject getCurrentProject() { return currentProject; }
    public int getQueueSize() { return buildingQueue.size(); }
    public int getTotalBlocksPlaced() { return totalBlocksPlaced; }
    public int getTotalProjectsCompleted() { return totalProjectsCompleted; }
    public Set<String> getKnownTemplates() { return knownTemplates.keySet(); }
    
    public void setMaxBuildingRange(int range) {
        this.maxBuildingRange = Math.max(8, Math.min(128, range));
    }
    
    public void setBlocksPerTick(int blocks) {
        this.blocksPerTick = Math.max(1, Math.min(10, blocks));
    }
    
    public void setAutoGatherMaterials(boolean auto) {
        this.autoGatherMaterials = auto;
    }
    
    public void setAllowTerrainModification(boolean allow) {
        this.allowTerrainModification = allow;
    }
    
    // Статистика
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("total_blocks_placed", totalBlocksPlaced);
        stats.addProperty("total_projects_completed", totalProjectsCompleted);
        stats.addProperty("current_project", currentProject != null ? currentProject.template.name : null);
        stats.addProperty("queue_size", buildingQueue.size());
        stats.addProperty("known_templates", knownTemplates.size());
        stats.addProperty("max_building_range", maxBuildingRange);
        stats.addProperty("blocks_per_tick", blocksPerTick);
        stats.addProperty("auto_gather_materials", autoGatherMaterials);
        return stats;
    }
    
    // Внутренние классы
    public static class BuildingTemplate {
        public final String id;
        public final String name;
        public final String description;
        public final int width, height, length;
        private final List<BlockPlacement> blocks = new ArrayList<>();
        
        public BuildingTemplate(String id, String name, int width, int height, int length) {
            this.id = id;
            this.name = name;
            this.description = name;
            this.width = width;
            this.height = height;
            this.length = length;
        }
        
        public void addBlock(int x, int y, int z, BlockState state) {
            blocks.add(new BlockPlacement(new BlockPos(x, y, z), state));
        }
        
        public List<BlockPlacement> getBlocks() {
            return new ArrayList<>(blocks);
        }
        
        public static class BlockPlacement {
            public final BlockPos relativePos;
            public final BlockState blockState;
            
            public BlockPlacement(BlockPos relativePos, BlockState blockState) {
                this.relativePos = relativePos;
                this.blockState = blockState;
            }
        }
    }
    
    public static class BuildingProject {
        public final BuildingTemplate template;
        public final BlockPos baseLocation;
        public final Block primaryMaterial;
        public final UUID requesterId;
        
        private final List<BlockPlacement> remainingBlocks;
        private final List<BlockPlacement> placedBlocks = new ArrayList<>();
        private final List<BlockPlacement> failedBlocks = new ArrayList<>();
        private final List<BlockPlacement> pendingBlocks = new ArrayList<>();
        
        public BuildingProject(BuildingTemplate template, BlockPos baseLocation, Block primaryMaterial, UUID requesterId) {
            this.template = template;
            this.baseLocation = baseLocation;
            this.primaryMaterial = primaryMaterial;
            this.requesterId = requesterId;
            this.remainingBlocks = new ArrayList<>();
            
            // Конвертируем типы блоков
            for (BuildingTemplate.BlockPlacement templateBlock : template.getBlocks()) {
                this.remainingBlocks.add(new BlockPlacement(templateBlock.relativePos, templateBlock.blockState));
            }
        }
        
        public BlockPlacement getNextBlock() {
            return remainingBlocks.isEmpty() ? null : remainingBlocks.get(0);
        }
        
        public void markBlockAsPlaced(BlockPlacement block) {
            remainingBlocks.remove(block);
            placedBlocks.add(block);
        }
        
        public void markBlockAsFailed(BlockPlacement block) {
            remainingBlocks.remove(block);
            failedBlocks.add(block);
        }
        
        public void markBlockAsPending(BlockPlacement block) {
            remainingBlocks.remove(block);
            pendingBlocks.add(block);
        }
        
        public boolean isCompleted() {
            return remainingBlocks.isEmpty() && pendingBlocks.isEmpty();
        }
        
        public double getProgress() {
            int total = placedBlocks.size() + failedBlocks.size() + remainingBlocks.size() + pendingBlocks.size();
            return total == 0 ? 1.0 : (double) placedBlocks.size() / total;
        }
        
        public static class BlockPlacement {
            public final BlockPos relativePos;
            public BlockState blockState;
            
            public BlockPlacement(BlockPos relativePos, BlockState blockState) {
                this.relativePos = relativePos;
                this.blockState = blockState;
            }
        }
    }
    
    private static class BuildingRequest {
        final String structureType;
        final BlockPos location;
        final Block material;
        final UUID requesterId;
        
        BuildingRequest(String structureType, BlockPos location, Block material, UUID requesterId) {
            this.structureType = structureType;
            this.location = location;
            this.material = material;
            this.requesterId = requesterId;
        }
    }
    
    private static class BuildingTask {
        final BuildingProject project;
        final long timestamp;
        
        BuildingTask(BuildingProject project, long timestamp) {
            this.project = project;
            this.timestamp = timestamp;
        }
    }
    
    // AI Goals
    private class BuildingGoal extends Goal {
        public BuildingGoal() {
            this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }
        
        @Override
        public boolean canStart() {
            return currentProject != null && !currentProject.isCompleted();
        }
        
        @Override
        public boolean shouldContinue() {
            return canStart();
        }
        
        @Override
        public void start() {
            npc.getNavigation().stop();
        }
        
        @Override
        public void tick() {
            if (currentProject == null) return;
            
            // Поворачиваемся к месту строительства
            BlockPos targetPos = currentProject.baseLocation;
            npc.getLookControl().lookAt(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
            
            // Подходим ближе если нужно
            if (npc.squaredDistanceTo(targetPos.getX(), targetPos.getY(), targetPos.getZ()) > 16) {
                npc.getNavigation().startMovingTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 0.8);
            }
        }
    }
    
    private class MaterialGatheringGoal extends Goal {
        public MaterialGatheringGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return autoGatherMaterials && currentProject != null && needsMaterials();
        }
        
        @Override
        public boolean shouldContinue() {
            return canStart();
        }
        
        private boolean needsMaterials() {
            // TODO: Реализовать проверку нехватки материалов
            return false;
        }
        
        @Override
        public void tick() {
            // TODO: Реализовать логику сбора материалов
        }
    }
}