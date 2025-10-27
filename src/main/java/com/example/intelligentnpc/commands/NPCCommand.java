package com.example.intelligentnpc.commands;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class NPCCommand {
    
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            literal("npc")
                .requires(source -> source.hasPermissionLevel(2)) // Требуется уровень оператора
                
                // /npc create <имя> [роль]
                .then(literal("create")
                    .then(argument("name", StringArgumentType.word())
                        .executes(NPCCommand::createNPC)
                        .then(argument("role", StringArgumentType.word())
                            .executes(NPCCommand::createNPCWithRole))))
                
                // /npc remove <имя>
                .then(literal("remove")
                    .then(argument("name", StringArgumentType.word())
                        .executes(NPCCommand::removeNPC)))
                
                // /npc list
                .then(literal("list")
                    .executes(NPCCommand::listNPCs))
                
                // /npc info <имя>
                .then(literal("info")
                    .then(argument("name", StringArgumentType.word())
                        .executes(NPCCommand::showNPCInfo)))
                
                // /npc teleport <имя>
                .then(literal("teleport")
                    .then(argument("name", StringArgumentType.word())
                        .executes(NPCCommand::teleportNPCToPlayer)))
                
                // /npc stats
                .then(literal("stats")
                    .executes(NPCCommand::showStats))
                
                // /npc config <имя> <параметр> <значение>
                .then(literal("config")
                    .then(argument("name", StringArgumentType.word())
                        .then(argument("parameter", StringArgumentType.word())
                            .then(argument("value", StringArgumentType.greedyString())
                                .executes(NPCCommand::configureNPC)))))
                
                // /npc task <имя> <задача> [параметры]
                .then(literal("task")
                    .then(argument("name", StringArgumentType.word())
                        .then(argument("task", StringArgumentType.word())
                            .executes(NPCCommand::assignTask)
                            .then(argument("parameters", StringArgumentType.greedyString())
                                .executes(NPCCommand::assignTaskWithParams)))))
                
                // /npc team <подкоманда>
                .then(literal("team")
                    .then(literal("create")
                        .then(argument("leader", StringArgumentType.word())
                            .executes(NPCCommand::createTeam)))
                    .then(literal("join")
                        .then(argument("member", StringArgumentType.word())
                            .then(argument("leader", StringArgumentType.word())
                                .executes(NPCCommand::joinTeam))))
                    .then(literal("leave")
                        .then(argument("member", StringArgumentType.word())
                            .executes(NPCCommand::leaveTeam))))
        );
    }
    
    private static int createNPC(CommandContext<ServerCommandSource> context) {
        return createNPCWithRole(context, "generic");
    }
    
    private static int createNPCWithRole(CommandContext<ServerCommandSource> context) {
        String role = StringArgumentType.getString(context, "role");
        return createNPCWithRole(context, role);
    }
    
    private static int createNPCWithRole(CommandContext<ServerCommandSource> context, String role) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            BlockPos pos = player.getBlockPos();
            
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            if (npcManager == null) {
                source.sendError(Text.literal("NPCManager не инициализирован"));
                return 0;
            }
            
            NPCEntity npc = npcManager.createNPC(name, role, pos, player.getServerWorld(), player);
            if (npc != null) {
                source.sendFeedback(() -> Text.literal(
                    String.format("§aСоздан NPC '%s' с ролью '%s' в позиции %s", 
                        name, role, pos.toString())), true);
                return 1;
            } else {
                source.sendError(Text.literal("§cНе удалось создать NPC"));
                return 0;
            }
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            IntelligentNPCMod.LOGGER.error("Error creating NPC: {}", e.getMessage());
            return 0;
        }
    }
    
    private static int removeNPC(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            
            if (npcManager.removeNPC(name, player)) {
                source.sendFeedback(() -> Text.literal("§aNPC '" + name + "' удален"), true);
                return 1;
            } else {
                source.sendError(Text.literal("§cNPC '" + name + "' не найден или нет прав"));
                return 0;
            }
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int listNPCs(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            List<NPCEntity> npcs = npcManager.getAllNPCs();
            
            if (npcs.isEmpty()) {
                source.sendFeedback(() -> Text.literal("§eNPC не найдены"), false);
                return 0;
            }
            
            source.sendFeedback(() -> Text.literal("§6=== Список NPC ==="), false);
            
            for (NPCEntity npc : npcs) {
                String info = String.format("§e%s §7(§f%s§7) - §a%s §7at §f%d, %d, %d", 
                    npc.getNpcName(),
                    npc.getRole(), 
                    npc.getCurrentTask(),
                    npc.getBlockPos().getX(),
                    npc.getBlockPos().getY(),
                    npc.getBlockPos().getZ());
                
                source.sendFeedback(() -> Text.literal(info), false);
            }
            
            return npcs.size();
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int showNPCInfo(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(name);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + name + "' не найден"));
                return 0;
            }
            
            // Основная информация
            source.sendFeedback(() -> Text.literal("§6=== Информация о " + name + " ==="), false);
            source.sendFeedback(() -> Text.literal("§eРоль: §f" + npc.getRole()), false);
            source.sendFeedback(() -> Text.literal("§eТекущая задача: §f" + npc.getCurrentTask()), false);
            source.sendFeedback(() -> Text.literal("§eЗдоровье: §f" + 
                String.format("%.1f/%.1f", npc.getHealth(), npc.getMaxHealth())), false);
            
            BlockPos pos = npc.getBlockPos();
            source.sendFeedback(() -> Text.literal("§eПозиция: §f" + 
                String.format("%d, %d, %d", pos.getX(), pos.getY(), pos.getZ())), false);
            
            // Навыки
            if (npc.getMemory() != null) {
                source.sendFeedback(() -> Text.literal("§eНавыки:"), false);
                source.sendFeedback(() -> Text.literal("  §7Строительство: §f" + 
                    npc.getMemory().getSkillLevel("building")), false);
                source.sendFeedback(() -> Text.literal("  §7Торговля: §f" + 
                    npc.getMemory().getSkillLevel("trading")), false);
                source.sendFeedback(() -> Text.literal("  §7Бой: §f" + 
                    npc.getMemory().getSkillLevel("combat")), false);
                source.sendFeedback(() -> Text.literal("  §7Обучение: §f" + 
                    npc.getMemory().getSkillLevel("learning")), false);
            }
            
            // Команда
            if (npc.getTeamAI() != null && npc.getTeamAI().isInTeam()) {
                source.sendFeedback(() -> Text.literal("§eВ команде: §aДа §7(" + 
                    npc.getTeamAI().getTeamMembers().size() + " членов)"), false);
            } else {
                source.sendFeedback(() -> Text.literal("§eВ команде: §cНет"), false);
            }
            
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int teleportNPCToPlayer(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(name);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + name + "' не найден"));
                return 0;
            }
            
            BlockPos playerPos = player.getBlockPos();
            npc.teleport(player.getServerWorld(), playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5,
                java.util.Set.of(), 0.0f, 0.0f);
            
            source.sendFeedback(() -> Text.literal("§aТелепортировал " + name + " к вам"), true);
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int teleportNPCToCoords(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(name);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + name + "' не найден"));
                return 0;
            }
            
            // Упрощенная телепортация к игроку
            ServerPlayerEntity player = source.getPlayerOrThrow();
            BlockPos playerPos = player.getBlockPos();
            
            if (npc.getWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                npc.teleport(serverWorld, playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5,
                    java.util.Set.of(), 0.0f, 0.0f);
            }
            
            source.sendFeedback(() -> Text.literal(String.format("§aТелепортировал %s в позицию %d, %d, %d",
                name, playerPos.getX(), playerPos.getY(), playerPos.getZ())), true);
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int showStats(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            npcManager.printStatistics(source.getPlayer());
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int configureNPC(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        String parameter = StringArgumentType.getString(context, "parameter");
        String value = StringArgumentType.getString(context, "value");
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(name);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + name + "' не найден"));
                return 0;
            }
            
            boolean success = configureNPCParameter(npc, parameter, value);
            
            if (success) {
                source.sendFeedback(() -> Text.literal(String.format("§aПараметр '%s' установлен в '%s' для %s", 
                    parameter, value, name)), true);
                return 1;
            } else {
                source.sendError(Text.literal("§cНеизвестный параметр: " + parameter));
                return 0;
            }
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static boolean configureNPCParameter(NPCEntity npc, String parameter, String value) {
        try {
            switch (parameter.toLowerCase()) {
                case "role" -> {
                    npc.setRole(value);
                    return true;
                }
                case "chattiness" -> {
                    double level = Double.parseDouble(value);
                    npc.getChatAI().setChattiness(level);
                    return true;
                }
                case "aggression" -> {
                    double level = Double.parseDouble(value);
                    npc.getCombatAI().setAggressionLevel(level);
                    return true;
                }
                case "building_range" -> {
                    int range = Integer.parseInt(value);
                    npc.getBuilderAI().setMaxBuildingRange(range);
                    return true;
                }
                case "team_leader" -> {
                    boolean canLead = Boolean.parseBoolean(value);
                    npc.getTeamAI().setCanLeadTeam(canLead);
                    return true;
                }
                case "learning_rate" -> {
                    double rate = Double.parseDouble(value);
                    npc.getLearningAI().setLearningRate(rate);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (NumberFormatException | NullPointerException e) {
            return false;
        }
    }
    
    private static int assignTask(CommandContext<ServerCommandSource> context) {
        return assignTaskWithParams(context, "");
    }
    
    private static int assignTaskWithParams(CommandContext<ServerCommandSource> context) {
        String params = "";
        try {
            params = StringArgumentType.getString(context, "parameters");
        } catch (Exception e) {
            // Параметры не обязательны
        }
        return assignTaskWithParams(context, params);
    }
    
    private static int assignTaskWithParams(CommandContext<ServerCommandSource> context, String params) {
        ServerCommandSource source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        String task = StringArgumentType.getString(context, "task");
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(name);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + name + "' не найден"));
                return 0;
            }
            
            boolean success = assignTaskToNPC(npc, task, params);
            
            if (success) {
                source.sendFeedback(() -> Text.literal(String.format("§aЗадача '%s' назначена для %s", 
                    task, name)), true);
                return 1;
            } else {
                source.sendError(Text.literal("§cНе удалось назначить задачу: " + task));
                return 0;
            }
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static boolean assignTaskToNPC(NPCEntity npc, String task, String params) {
        switch (task.toLowerCase()) {
            case "build", "строить" -> {
                npc.getNPCBrain().setGoal("building");
                if (!params.isEmpty()) {
                    // TODO: Парсинг параметров строительства
                }
                return true;
            }
            case "trade", "торговать" -> {
                npc.getNPCBrain().setGoal("trading");
                return true;
            }
            case "guard", "охранять" -> {
                npc.getNPCBrain().setGoal("guard");
                return true;
            }
            case "follow", "следовать" -> {
                npc.getNPCBrain().setGoal("follow_player");
                return true;
            }
            case "stay", "стоять" -> {
                npc.getNPCBrain().setGoal("stay");
                return true;
            }
            case "explore", "исследовать" -> {
                npc.getNPCBrain().setGoal("explore");
                return true;
            }
            case "rest", "отдыхать" -> {
                npc.getNPCBrain().setGoal("rest");
                return true;
            }
            case "learn", "учиться" -> {
                npc.getNPCBrain().setGoal("learn");
                if (!params.isEmpty()) {
                    npc.getLearningAI().setCurrentLearningFocus(params);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
    
    private static int createTeam(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String leaderName = StringArgumentType.getString(context, "leader");

        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity leader = npcManager.getNPCByName(leaderName);

            if (leader == null) {
                source.sendError(Text.literal("§cNPC '" + leaderName + "' не найден"));
                return 0;
            }

            if (leader.getTeamAI().isInTeam()) {
                source.sendError(Text.literal("§c" + leaderName + " уже в команде"));
                return 0;
            }

            leader.getTeamAI().formNewTeam();
            source.sendFeedback(() -> Text.literal("§a" + leaderName + " создал новую команду"), true);
            return 1;

        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }

    private static int joinTeam(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String memberName = StringArgumentType.getString(context, "member");
        String leaderName = StringArgumentType.getString(context, "leader");

        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity member = npcManager.getNPCByName(memberName);
            NPCEntity leader = npcManager.getNPCByName(leaderName);

            if (member == null) {
                source.sendError(Text.literal("§cNPC '" + memberName + "' не найден"));
                return 0;
            }

            if (leader == null) {
                source.sendError(Text.literal("§cNPC '" + leaderName + "' не найден"));
                return 0;
            }

            if (member.getTeamAI().isInTeam()) {
                source.sendError(Text.literal("§c" + memberName + " уже состоит в команде."));
                return 0;
            }

            boolean success = leader.getTeamAI().addMember(member);
            if (success) {
                source.sendFeedback(() -> Text.literal(String.format("§a%s присоединился к команде %s",
                    memberName, leaderName)), true);
                return 1;
            } else {
                source.sendError(Text.literal("§cНе удалось присоединиться к команде. Возможно, она заполнена."));
                return 0;
            }

        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }

    private static int leaveTeam(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String memberName = StringArgumentType.getString(context, "member");

        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity member = npcManager.getNPCByName(memberName);

            if (member == null) {
                source.sendError(Text.literal("§cNPC '" + memberName + "' не найден"));
                return 0;
            }

            if (!member.getTeamAI().isInTeam()) {
                source.sendError(Text.literal("§c" + memberName + " не в команде"));
                return 0;
            }

            member.getTeamAI().leaveTeam();
            source.sendFeedback(() -> Text.literal("§a" + memberName + " покинул команду"), true);
            return 1;

        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
}