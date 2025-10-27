package com.example.intelligentnpc.commands;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.regex.Pattern;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class NPCChatCommand {
    
    // Паттерн для обнаружения команд NPC в чате
    private static final Pattern NPC_COMMAND_PATTERN = Pattern.compile("^(\\w+),\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // /say <npc_name> <message> - заставить NPC что-то сказать
        dispatcher.register(
            literal("npcsay")
                .requires(source -> source.hasPermissionLevel(1))
                .then(argument("npc_name", StringArgumentType.word())
                    .then(argument("message", StringArgumentType.greedyString())
                        .executes(NPCChatCommand::makeNPCSpeak)))
        );
        
        // /npcwhisper <npc_name> <message> - шептать NPC (только для админа)
        dispatcher.register(
            literal("npcwhisper")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("npc_name", StringArgumentType.word())
                    .then(argument("message", StringArgumentType.greedyString())
                        .executes(NPCChatCommand::whisperToNPC)))
        );
        
        // /npcmood <npc_name> <mood> - изменить настроение NPC
        dispatcher.register(
            literal("npcmood")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("npc_name", StringArgumentType.word())
                    .then(argument("mood", StringArgumentType.word())
                        .executes(NPCChatCommand::changeNPCMood)))
        );
        
        // /npcconversation <npc_name> - начать диалог с NPC
        dispatcher.register(
            literal("talk")
                .then(argument("npc_name", StringArgumentType.word())
                    .executes(NPCChatCommand::startConversation)
                    .then(argument("message", StringArgumentType.greedyString())
                        .executes(NPCChatCommand::sendMessageToNPC)))
        );
        
        // /npcemotion <npc_name> <emotion> <value> - изменить эмоцию NPC
        dispatcher.register(
            literal("npcemotion")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("npc_name", StringArgumentType.word())
                    .then(argument("emotion", StringArgumentType.word())
                        .then(argument("value", StringArgumentType.word())
                            .executes(NPCChatCommand::adjustNPCEmotion))))
        );
    }
    
    private static int makeNPCSpeak(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String npcName = StringArgumentType.getString(context, "npc_name");
        String message = StringArgumentType.getString(context, "message");
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(npcName);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + npcName + "' не найден"));
                return 0;
            }
            
            // Заставляем NPC говорить
            npc.getChatAI().say(message);
            
            source.sendFeedback(() -> Text.literal("§a" + npcName + " сказал: " + message), true);
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            IntelligentNPCMod.LOGGER.error("Error making NPC speak: {}", e.getMessage());
            return 0;
        }
    }
    
    private static int whisperToNPC(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String npcName = StringArgumentType.getString(context, "npc_name");
        String message = StringArgumentType.getString(context, "message");
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(npcName);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + npcName + "' не найден"));
                return 0;
            }
            
            // Отправляем приватное сообщение NPC (записываем в память как команду администратора)
            npc.getMemory().addExperience("admin_whisper", "Admin whispered: " + message);
            
            // NPC может отреагировать на шепот
            if (Math.random() < 0.3) {
                String[] responses = {
                    "Понял тебя...",
                    "Хорошо, учту это",
                    "Спасибо за подсказку",
                    "Интересно...",
                    "Буду помнить это"
                };
                
                String response = responses[(int) (Math.random() * responses.length)];
                npc.getChatAI().say(response);
            }
            
            source.sendFeedback(() -> Text.literal("§7Вы шепнули " + npcName + ": " + message), false);
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int changeNPCMood(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String npcName = StringArgumentType.getString(context, "npc_name");
        String mood = StringArgumentType.getString(context, "mood");
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(npcName);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + npcName + "' не найден"));
                return 0;
            }
            
            // Список допустимых настроений
            String[] validMoods = {"happy", "sad", "angry", "neutral", "excited", "curious", "tired", "confident"};
            boolean isValidMood = false;
            
            for (String validMood : validMoods) {
                if (validMood.equalsIgnoreCase(mood)) {
                    isValidMood = true;
                    break;
                }
            }
            
            if (!isValidMood) {
                source.sendError(Text.literal("§cНедопустимое настроение. Доступны: " + 
                    String.join(", ", validMoods)));
                return 0;
            }
            
            // Изменяем настроение NPC
            npc.getNPCBrain().setMood(mood.toLowerCase());
            
            // NPC реагирует на изменение настроения
            String response = generateMoodResponse(mood.toLowerCase());
            if (!response.isEmpty()) {
                npc.getChatAI().say(response);
            }
            
            source.sendFeedback(() -> Text.literal("§aНастроение " + npcName + " изменено на: " + mood), true);
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static String generateMoodResponse(String mood) {
        return switch (mood) {
            case "happy" -> "Ура! Я чувствую себя замечательно!";
            case "sad" -> "Почему-то стало грустно...";
            case "angry" -> "Что-то меня злит!";
            case "excited" -> "Ох как интересно! Что будем делать?";
            case "curious" -> "Хм, а что это там такое?";
            case "tired" -> "*зевает* Что-то устал я...";
            case "confident" -> "Я готов к любым вызовам!";
            case "neutral" -> ""; // Нейтральное настроение - без реакции
            default -> "";
        };
    }
    
    private static int startConversation(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String npcName = StringArgumentType.getString(context, "npc_name");
        
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(npcName);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + npcName + "' не найден"));
                return 0;
            }
            
            // Проверяем расстояние до NPC
            if (player.squaredDistanceTo(npc) > 100) { // 10 блоков
                source.sendError(Text.literal("§cВы слишком далеко от " + npcName));
                return 0;
            }
            
            // Начинаем разговор
            npc.getChatAI().handlePlayerInteraction(player, player.getActiveHand());
            
            source.sendFeedback(() -> Text.literal("§aНачали разговор с " + npcName), false);
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int sendMessageToNPC(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String npcName = StringArgumentType.getString(context, "npc_name");
        String message = StringArgumentType.getString(context, "message");
        
        try {
            ServerPlayerEntity player = source.getPlayerOrThrow();
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(npcName);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + npcName + "' не найден"));
                return 0;
            }
            
            // Проверяем расстояние до NPC
            if (player.squaredDistanceTo(npc) > 100) { // 10 блоков
                source.sendError(Text.literal("§cВы слишком далеко от " + npcName));
                return 0;
            }
            
            // Отправляем сообщение NPC
            npc.getChatAI().handleChatCommand(message, player);
            
            // Показываем сообщение другим игрокам поблизости
            String formattedMessage = String.format("§e%s §7говорит с §e%s§7: §f%s", 
                player.getName().getString(), npcName, message);
            
            List<ServerPlayerEntity> nearbyPlayers = player.getServerWorld().getPlayers(
                p -> p != player && p.squaredDistanceTo(player) < 225); // 15 блоков
            
            for (ServerPlayerEntity nearbyPlayer : nearbyPlayers) {
                nearbyPlayer.sendMessage(Text.literal(formattedMessage), false);
            }
            
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int adjustNPCEmotion(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String npcName = StringArgumentType.getString(context, "npc_name");
        String emotion = StringArgumentType.getString(context, "emotion");
        String valueStr = StringArgumentType.getString(context, "value");
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            NPCEntity npc = npcManager.getNPCByName(npcName);
            
            if (npc == null) {
                source.sendError(Text.literal("§cNPC '" + npcName + "' не найден"));
                return 0;
            }
            
            double value;
            try {
                value = Double.parseDouble(valueStr);
            } catch (NumberFormatException e) {
                source.sendError(Text.literal("§cНекорректное значение: " + valueStr));
                return 0;
            }
            
            // Ограничиваем значение от -1.0 до 1.0
            final double finalValue = Math.max(-1.0, Math.min(1.0, value));
            
            // Список допустимых эмоций
            String[] validEmotions = {"happiness", "sadness", "anger", "fear", "curiosity", "confidence"};
            boolean isValidEmotion = false;
            
            for (String validEmotion : validEmotions) {
                if (validEmotion.equalsIgnoreCase(emotion)) {
                    isValidEmotion = true;
                    break;
                }
            }
            
            if (!isValidEmotion) {
                source.sendError(Text.literal("§cНедопустимая эмоция. Доступны: " +
                    String.join(", ", validEmotions)));
                return 0;
            }
            
            // Применяем изменение эмоции (через память, так как прямого доступа к adjustEmotion нет)
            npc.getMemory().adjustPersonalityTrait(emotion.toLowerCase(), finalValue);
            
            // NPC реагирует на изменение эмоции
            String response = generateEmotionResponse(emotion.toLowerCase(), finalValue);
            if (!response.isEmpty()) {
                npc.getChatAI().say(response);
            }
            
            source.sendFeedback(() -> Text.literal(String.format("§aЭмоция '%s' для %s изменена на %.2f",
                emotion, npcName, finalValue)), true);
            return 1;
            
        } catch (Exception e) {
            source.sendError(Text.literal("§cОшибка: " + e.getMessage()));
            return 0;
        }
    }
    
    private static String generateEmotionResponse(String emotion, double value) {
        if (Math.abs(value) < 0.1) return ""; // Слишком маленькое изменение
        
        boolean positive = value > 0;
        
        return switch (emotion) {
            case "happiness" -> positive ? "Как же хорошо!" : "Что-то стало не так весело...";
            case "sadness" -> positive ? "Почему мне так грустно?" : "Настроение поднимается!";
            case "anger" -> positive ? "Что-то меня раздражает!" : "Успокаиваюсь...";
            case "fear" -> positive ? "Мне страшно..." : "Стало спокойнее";
            case "curiosity" -> positive ? "Интересно, что происходит?" : "А, не так уж и важно";
            case "confidence" -> positive ? "Я чувствую силу!" : "Что-то не уверен я...";
            default -> "";
        };
    }
    
    // Обработка чатовых команд от игроков (вызывается из NPCManager)
    public static boolean processNPCChatCommand(String message, ServerPlayerEntity sender) {
        java.util.regex.Matcher matcher = NPC_COMMAND_PATTERN.matcher(message);
        if (!matcher.matches()) {
            return false; // Это не команда для NPC
        }
        
        String npcName = matcher.group(1);
        String command = matcher.group(2);
        
        try {
            NPCManager npcManager = IntelligentNPCMod.getInstance().getNpcManager();
            if (npcManager != null) {
                npcManager.handleChatMessage(Text.literal(npcName + ", " + command), sender);
                return true;
            }
        } catch (Exception e) {
            IntelligentNPCMod.LOGGER.error("Error processing NPC chat command: {}", e.getMessage());
        }
        
        return false;
    }
    
    // Утилиты для работы с чатом
    public static String extractNPCNameFromMessage(String message) {
        java.util.regex.Matcher matcher = NPC_COMMAND_PATTERN.matcher(message);
        return matcher.matches() ? matcher.group(1) : null;
    }
    
    public static String extractCommandFromMessage(String message) {
        java.util.regex.Matcher matcher = NPC_COMMAND_PATTERN.matcher(message);
        return matcher.matches() ? matcher.group(2) : null;
    }
    
    public static boolean isNPCCommand(String message) {
        return NPC_COMMAND_PATTERN.matcher(message).matches();
    }
    
    // Предложения команд для автодополнения
    public static List<String> getSuggestedCommands() {
        return List.of(
            "привет",
            "как дела",
            "что делаешь",
            "построй дом",
            "торгуй со мной",
            "следуй за мной",
            "стой здесь",
            "расскажи шутку",
            "помоги мне",
            "научи меня",
            "работай",
            "отдыхай",
            "исследуй окрестности",
            "защищай меня"
        );
    }
    
    public static List<String> getSuggestedMoods() {
        return List.of("happy", "sad", "angry", "neutral", "excited", "curious", "tired", "confident");
    }
    
    public static List<String> getSuggestedEmotions() {
        return List.of("happiness", "sadness", "anger", "fear", "curiosity", "confidence");
    }
}