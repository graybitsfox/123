package com.example.intelligentnpc.npc.ai;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCMemory;
import com.google.gson.JsonObject;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class ChatAI {
    private final NPCEntity npc;
    private final NPCMemory memory;
    
    // Настройки поведения
    private double chattiness = 0.5; // Насколько часто говорит
    private double humor = 0.4; // Уровень юмора
    private double sarcasm = 0.3; // Уровень сарказма
    private boolean allowDarkHumor = true; // Разрешен ли черный юмор
    
    // Кэш сообщений и реакций
    private final Map<String, Long> lastPlayerInteraction = new HashMap<>();
    private final List<String> recentMessages = new ArrayList<>();
    private String lastSpokenMessage = "";
    private long lastMessageTime = 0;
    
    // Шаблоны реакций
    private static final List<String> GREETINGS = Arrays.asList(
        "Привет, {player}!",
        "О, {player}! Как дела?",
        "Здарова, {player}!",
        "Вижу знакомое лицо - {player}!",
        "{player}, рад тебя видеть!",
        "О нет, опять {player}...",
        "Что за чёрт, {player} появился!"
    );
    
    private static final List<String> FAREWELLS = Arrays.asList(
        "Увидимся, {player}!",
        "Пока-пока, {player}!",
        "До встречи!",
        "Не скучай без меня, {player}!",
        "Ну наконец-то {player} уходит...",
        "Свободен от {player}!"
    );
    
    private static final List<String> POSITIVE_REACTIONS = Arrays.asList(
        "Отлично!",
        "Супер!",
        "Класс!",
        "Вот это да!",
        "Круто!",
        "Замечательно!",
        "Потрясающе!"
    );
    
    private static final List<String> NEGATIVE_REACTIONS = Arrays.asList(
        "Фу, гадость...",
        "Не нравится мне это",
        "Ужас какой-то",
        "Что за ерунда?",
        "Блин, не то...",
        "Кошмар!",
        "Отвратительно!"
    );
    
    private static final List<String> HUMOROUS_COMMENTS = Arrays.asList(
        "Знаете, а ведь жизнь прекрасна... когда спишь!",
        "Я не ленивый, я просто в энергосберегающем режиме",
        "Мой IQ как температура - иногда ниже нормы",
        "Жизнь как лотерея, только билеты дорогие, а призов нет",
        "Я не сумасшедший, я просто творческая личность!",
        "Работать надо не 12 часов в день, а головой!",
        "Деньги не главное в жизни, главное их отсутствие"
    );
    
    private static final List<String> DARK_HUMOR = Arrays.asList(
        "Смерть - это не конец, это просто очень долгий сон без будильника",
        "Жизнь коротка, но иногда кажется, что она длится вечность",
        "Все мы умрем, но некоторые успеют это понять",
        "Оптимист видит свет в конце туннеля, пессимист - встречный поезд",
        "Жизнь - штука временная, но очень навязчивая",
        "Главное в жизни - вовремя родиться и вовремя умереть"
    );
    
    public ChatAI(NPCEntity npc) {
        this.npc = npc;
        this.memory = npc.getMemory();
        
        // Настройка параметров на основе личности из памяти
        initializePersonality();
    }
    
    private void initializePersonality() {
        if (memory != null) {
            chattiness = memory.getPersonalityTrait("friendliness") * 0.8 + 0.2;
            humor = memory.getPersonalityTrait("humor") * 0.8 + 0.1;
            sarcasm = memory.getPersonalityTrait("sarcasm") * 0.7 + 0.1;
            
            // Черный юмор разрешен только при определенных условиях
            allowDarkHumor = memory.getPersonalityTrait("humor") > 0.6 && 
                           memory.getPersonalityTrait("creativity") > 0.5;
        }
    }
    
    public void tick() {
        // Периодические реплики
        if (shouldMakeRandomComment()) {
            makeRandomComment();
        }
        
        // Очистка старых сообщений
        cleanupOldMessages();
        
        // Обновление параметров личности
        if (npc.age % 1200 == 0) { // Каждую минуту
            updatePersonalityFromMemory();
        }
    }
    
    private boolean shouldMakeRandomComment() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastMessageTime < 30000) { // Минимум 30 секунд между случайными репликами
            return false;
        }
        
        // Вероятность зависит от общительности
        double chance = chattiness * 0.001; // Очень низкая базовая вероятность
        
        // Увеличиваем шанс, если рядом есть игроки
        if (hasPlayersNearby()) {
            chance *= 5;
        }
        
        return Math.random() < chance;
    }
    
    private boolean hasPlayersNearby() {
        return !npc.getWorld().getEntitiesByClass(
            PlayerEntity.class, npc.getBoundingBox().expand(10.0), p -> true).isEmpty();
    }
    
    private void makeRandomComment() {
        List<String> possibleComments = new ArrayList<>();
        
        // Добавляем различные типы комментариев
        if (Math.random() < humor) {
            possibleComments.addAll(HUMOROUS_COMMENTS);
            
            if (allowDarkHumor && Math.random() < 0.3) {
                possibleComments.addAll(DARK_HUMOR);
            }
        }
        
        // Комментарии о текущем состоянии
        addContextualComments(possibleComments);
        
        if (!possibleComments.isEmpty()) {
            String comment = getRandomElement(possibleComments);
            say(comment);
        }
    }
    
    private void addContextualComments(List<String> comments) {
        // Комментарии на основе времени суток
        long timeOfDay = npc.getWorld().getTimeOfDay() % 24000;
        if (timeOfDay < 1000) {
            comments.add("Доброе утро! Хотя кому оно доброе...");
            comments.add("Утро - время, когда хочется спать больше всего");
        } else if (timeOfDay > 13000 && timeOfDay < 23000) {
            comments.add("Ночью все кошки серые, а криперы зеленые");
            comments.add("Ночь - лучшее время для размышлений о смысле жизни");
        }
        
        // Комментарии о погоде
        if (npc.getWorld().isRaining()) {
            comments.add("Дождь как слезы неба... или просто вода");
            comments.add("Дождик, дождик, пуще! А то засуха совсем заколебала");
        }
        
        // Комментарии о здоровье
        if (npc.getHealth() < npc.getMaxHealth() * 0.5) {
            comments.add("Чувствую себя как зомби... хотя нет, они выглядят лучше");
            comments.add("Здоровье не купишь, но можно дорого продать");
        }
        
        // Комментарии на основе роли
        String role = npc.getRole();
        switch (role.toLowerCase()) {
            case "builder" -> {
                comments.add("Строить - моя страсть. Ломать тоже неплохо");
                comments.add("Архитектор - это тот, кто знает, куда поставить унитаз");
            }
            case "trader" -> {
                comments.add("Торговля - искусство продать то, что не нужно");
                comments.add("Деньги не пахнут, но их отсутствие очень заметно");
            }
            case "guard" -> {
                comments.add("Защищаю всех... кроме себя от скуки");
                comments.add("Лучшая защита - это нападение. Но я ленивый");
            }
        }
    }
    
    // Обработка взаимодействий с игроками
    public void handlePlayerInteraction(PlayerEntity player, Hand hand) {
        String playerName = player.getName().getString();
        long currentTime = System.currentTimeMillis();
        
        // Запись взаимодействия в память
        memory.recordPlayerInteraction(player, "interaction", "Player interacted with hand");
        
        // Определение типа реакции на основе отношений
        NPCMemory.PlayerRelationship relationship = memory.getPlayerRelationship(playerName);
        String reaction = generateInteractionResponse(player, relationship);
        
        say(reaction);
        
        // Обновление времени последнего взаимодействия
        lastPlayerInteraction.put(playerName, currentTime);
        
        // Возможная дополнительная реакция
        if (Math.random() < 0.3) {
            scheduleDelayedResponse(player, relationship);
        }
    }
    
    private String generateInteractionResponse(PlayerEntity player, NPCMemory.PlayerRelationship relationship) {
        String playerName = player.getName().getString();
        double relationshipLevel = relationship != null ? relationship.getRelationshipLevel() : 0.0;
        
        List<String> responses = new ArrayList<>();
        
        if (relationshipLevel > 0.7) {
            // Очень хорошие отношения
            responses.add("Привет, мой лучший друг " + playerName + "!");
            responses.add(playerName + ", как дела? Соскучился!");
            responses.add("О, " + playerName + "! Ты как всегда в нужное время!");
        } else if (relationshipLevel > 0.3) {
            // Хорошие отношения
            responses.add("Привет, " + playerName + "! Как поживаешь?");
            responses.add("О, " + playerName + "! Что нового?");
            responses.add("Рад видеть тебя, " + playerName + "!");
        } else if (relationshipLevel > -0.3) {
            // Нейтральные отношения
            responses.add("Здравствуй, " + playerName + ".");
            responses.add("О, это " + playerName + ".");
            responses.add("Что тебе нужно, " + playerName + "?");
        } else if (relationshipLevel > -0.7) {
            // Плохие отношения
            responses.add("Фу, опять " + playerName + "...");
            responses.add("Чего тебе, " + playerName + "?");
            responses.add(playerName + ", ты меня достал!");
        } else {
            // Очень плохие отношения
            responses.add("Убирайся, " + playerName + "!");
            responses.add("Не хочу тебя видеть, " + playerName + "!");
            responses.add("Отстань от меня, " + playerName + "!");
        }
        
        // Добавляем элемент случайности и сарказма
        if (Math.random() < sarcasm) {
            responses.add("О, великий " + playerName + " снизошел до меня!");
            responses.add("Какая честь, сам " + playerName + " ко мне пожаловал!");
            responses.add(playerName + ", ты как всегда очаровательно невыносим!");
        }
        
        return getRandomElement(responses);
    }
    
    private void scheduleDelayedResponse(PlayerEntity player, NPCMemory.PlayerRelationship relationship) {
        // Отложенная реакция через 2-5 секунд
        int delay = 40 + ThreadLocalRandom.current().nextInt(60); // 2-5 секунд в тиках
        
        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                if (npc.squaredDistanceTo(player) < 100) { // Если игрок все еще рядом
                    String delayedResponse = generateDelayedResponse(player, relationship);
                    say(delayedResponse);
                }
            }
        }, delay * 50L); // Конвертация тиков в миллисекунды
    }
    
    private String generateDelayedResponse(PlayerEntity player, NPCMemory.PlayerRelationship relationship) {
        String playerName = player.getName().getString();
        List<String> responses = new ArrayList<>();
        
        responses.add("Кстати, " + playerName + ", как твои дела?");
        responses.add("А помнишь, " + playerName + ", как мы в прошлый раз...?");
        responses.add("Слушай, " + playerName + ", у меня есть идея!");
        
        if (Math.random() < humor) {
            responses.add("Знаешь, " + playerName + ", жизнь как Minecraft - главное не копать вниз!");
            responses.add(playerName + ", ты похож на крипера - появляешься внезапно и взрываешь мой мозг!");
        }
        
        return getRandomElement(responses);
    }
    
    // Обработка команд чата
    public void handleChatCommand(String command, ServerPlayerEntity sender) {
        String response = processChatCommand(command, sender);
        
        if (!response.isEmpty()) {
            say(response);
            
            // Запись в память
            memory.recordPlayerInteraction(sender, "chat_command", command);
            memory.addExperience("communication", "Received command: " + command);
        }
    }
    
    private String processChatCommand(String command, ServerPlayerEntity sender) {
        String lowerCommand = command.toLowerCase();
        String playerName = sender.getName().getString();
        
        // Обработка различных типов команд
        if (lowerCommand.contains("привет") || lowerCommand.contains("hello") || lowerCommand.contains("hi")) {
            return getRandomElement(GREETINGS).replace("{player}", playerName);
        }
        
        if (lowerCommand.contains("пока") || lowerCommand.contains("bye") || lowerCommand.contains("goodbye")) {
            return getRandomElement(FAREWELLS).replace("{player}", playerName);
        }
        
        if (lowerCommand.contains("как дела") || lowerCommand.contains("how are you")) {
            return generateStatusResponse();
        }
        
        if (lowerCommand.contains("расскажи шутку") || lowerCommand.contains("tell joke")) {
            return generateJoke();
        }
        
        if (lowerCommand.contains("что думаешь") || lowerCommand.contains("what do you think")) {
            return generateOpinion(command);
        }
        
        if (lowerCommand.contains("помощь") || lowerCommand.contains("help")) {
            return generateHelpResponse();
        }
        
        // Общий ответ на неизвестные команды
        return generateGenericResponse(command, sender);
    }
    
    private String generateStatusResponse() {
        List<String> responses = new ArrayList<>();
        
        double energy = npc.getNPCBrain().getEnergy();
        String mood = npc.getNPCBrain().getCurrentMood();
        
        if (energy > 0.8) {
            responses.add("Дела отлично! Полон энергии!");
            responses.add("Все супер! Готов горы свернуть!");
        } else if (energy > 0.5) {
            responses.add("Дела нормально, как обычно");
            responses.add("Все хорошо, можно и лучше");
        } else {
            responses.add("Уставший я что-то...");
            responses.add("Дела так себе, энергии нет");
        }
        
        responses.add("Настроение " + mood + ", а дела как у всех");
        responses.add("Живой пока что, это уже неплохо");
        
        return getRandomElement(responses);
    }
    
    private String generateJoke() {
        List<String> jokes = new ArrayList<>(HUMOROUS_COMMENTS);
        
        if (allowDarkHumor && Math.random() < 0.4) {
            jokes.addAll(DARK_HUMOR);
        }
        
        // Minecraft-специфичные шутки
        jokes.add("Почему криперы зеленые? Потому что синие уже заняты!");
        jokes.add("Что общего у игрока и лавы? Оба могут испортить весь день!");
        jokes.add("Зачем эндермен украл блок? Хотел построить дом, но забыл про крышу!");
        jokes.add("Почему скелеты плохие музыканты? У них нет органов!");
        
        return getRandomElement(jokes);
    }
    
    private String generateOpinion(String topic) {
        List<String> opinions = new ArrayList<>();
        
        opinions.add("По-моему, это интересная тема для размышлений");
        opinions.add("Мое мнение? Все относительно");
        opinions.add("Думаю, что истина где-то посередине");
        opinions.add("А вот мне кажется, что это все ерунда");
        opinions.add("Не знаю, не думал об этом раньше");
        
        if (Math.random() < sarcasm) {
            opinions.add("Конечно, я же эксперт по всем вопросам!");
            opinions.add("Очень глубокая мысль, прямо философия!");
        }
        
        return getRandomElement(opinions);
    }
    
    private String generateHelpResponse() {
        return "Я могу общаться с тобой, рассказывать шутки, строить, торговать и многое другое! " +
               "Просто скажи мне, что нужно сделать. Например: 'построй дом' или 'расскажи шутку'";
    }
    
    private String generateGenericResponse(String command, ServerPlayerEntity sender) {
        List<String> responses = new ArrayList<>();
        
        responses.add("Интересно, расскажи подробнее");
        responses.add("Хм, а что ты имеешь в виду?");
        responses.add("Не совсем понимаю, но звучит заманчиво");
        responses.add("Ага, понятно... а нет, не понятно");
        
        if (Math.random() < humor) {
            responses.add("Говоришь как моя бабушка - непонятно, но с душой");
            responses.add("Твои слова мудры, жаль что я их не понимаю");
        }
        
        if (Math.random() < sarcasm) {
            responses.add("Вау, какие умные слова!");
            responses.add("Конечно-конечно, все ясно как в тумане");
        }
        
        return getRandomElement(responses);
    }
    
    // Обработка общего чата
    public void handleGeneralChat(String message, ServerPlayerEntity sender) {
        if (shouldRespondToGeneralChat(message, sender)) {
            String response = generateGeneralChatResponse(message, sender);
            if (!response.isEmpty()) {
                say(response);
                memory.addExperience("social", "Reacted to general chat: " + message);
            }
        }
    }
    
    private boolean shouldRespondToGeneralChat(String message, ServerPlayerEntity sender) {
        // Не отвечаем на собственные сообщения
        if (recentMessages.contains(message)) {
            return false;
        }
        
        // Проверяем, упоминается ли NPC в сообщении
        String lowerMessage = message.toLowerCase();
        String npcName = npc.getNpcName().toLowerCase();
        
        if (lowerMessage.contains(npcName)) {
            return true; // Всегда отвечаем, если нас упоминают
        }
        
        // Случайная реакция на основе общительности
        double chance = chattiness * 0.1;
        
        // Увеличиваем шанс для знакомых игроков
        NPCMemory.PlayerRelationship relationship = memory.getPlayerRelationship(sender.getName().getString());
        if (relationship != null && relationship.getRelationshipLevel() > 0.3) {
            chance *= 2;
        }
        
        return Math.random() < chance;
    }
    
    private String generateGeneralChatResponse(String message, ServerPlayerEntity sender) {
        String playerName = sender.getName().getString();
        String lowerMessage = message.toLowerCase();
        
        // Реакция на ключевые слова
        if (lowerMessage.contains("смерть") || lowerMessage.contains("убийство") || lowerMessage.contains("мертв")) {
            if (allowDarkHumor) {
                return "Смерть - единственное, что мы получаем бесплатно в этой жизни";
            } else {
                return "Давайте о чем-нибудь повеселее!";
            }
        }
        
        if (lowerMessage.contains("любовь") || lowerMessage.contains("сердце")) {
            return "Любовь - это как алмазы в Minecraft: редкая, ценная и иногда взрывается";
        }
        
        if (lowerMessage.contains("работа") || lowerMessage.contains("труд")) {
            return "Работа не волк, работа - это крафт на всю жизнь";
        }
        
        // Общие реакции
        List<String> reactions = new ArrayList<>();
        reactions.add("Согласен с " + playerName);
        reactions.add("А я думаю иначе, " + playerName);
        reactions.add("Интересная мысль, " + playerName);
        reactions.add("Хм, а мне кажется...");
        
        if (Math.random() < humor) {
            reactions.add("Это напоминает мне анекдот...");
            reactions.add("А вот и философ объявился!");
        }
        
        return getRandomElement(reactions);
    }
    
    // Вспомогательные методы
    public void say(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        
        // Отправка сообщения всем игрокам поблизости
        npc.getWorld().getPlayers().forEach(player -> {
            if (npc.squaredDistanceTo(player) < 225) { // В радиусе 15 блоков
                player.sendMessage(Text.literal("§b<" + npc.getNpcName() + ">§r " + message), false);
            }
        });
        
        // Сохранение в память
        memory.addExperience("speech", message);
        
        // Обновление кэша
        lastSpokenMessage = message;
        lastMessageTime = System.currentTimeMillis();
        recentMessages.add(message);
        
        // Ограничиваем размер кэша
        if (recentMessages.size() > 10) {
            recentMessages.remove(0);
        }
        
        // Передача в WebSocket для веб-панели
        if (IntelligentNPCMod.getInstance() != null && 
            IntelligentNPCMod.getInstance().getWebSocketServer() != null) {
            IntelligentNPCMod.getInstance().getWebSocketServer().broadcastNPCMessage(npc.getNpcName(), message);
        }
    }
    
    private String getRandomElement(List<String> list) {
        if (list.isEmpty()) {
            return "";
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
    
    private void cleanupOldMessages() {
        long cutoff = System.currentTimeMillis() - 300000; // 5 минут
        lastPlayerInteraction.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
    
    private void updatePersonalityFromMemory() {
        // Обновляем параметры личности на основе последних воспоминаний
        chattiness = Math.max(0.1, Math.min(1.0, memory.getPersonalityTrait("friendliness") * 0.8 + 0.2));
        humor = Math.max(0.0, Math.min(1.0, memory.getPersonalityTrait("humor") * 0.8 + 0.1));
        sarcasm = Math.max(0.0, Math.min(0.8, memory.getPersonalityTrait("sarcasm") * 0.7 + 0.1));
    }
    
    // Методы для настройки поведения
    public void setChattiness(double chattiness) {
        this.chattiness = Math.max(0.0, Math.min(1.0, chattiness));
        memory.adjustPersonalityTrait("friendliness", chattiness - this.chattiness);
    }
    
    public void setHumor(double humor) {
        this.humor = Math.max(0.0, Math.min(1.0, humor));
        memory.adjustPersonalityTrait("humor", humor - this.humor);
    }
    
    public void setSarcasm(double sarcasm) {
        this.sarcasm = Math.max(0.0, Math.min(1.0, sarcasm));
        memory.adjustPersonalityTrait("sarcasm", sarcasm - this.sarcasm);
    }
    
    public void setAllowDarkHumor(boolean allow) {
        this.allowDarkHumor = allow;
    }
    
    // Геттеры
    public double getChattiness() { return chattiness; }
    public double getHumor() { return humor; }
    public double getSarcasm() { return sarcasm; }
    public boolean isAllowDarkHumor() { return allowDarkHumor; }
    public String getLastSpokenMessage() { return lastSpokenMessage; }
    public long getLastMessageTime() { return lastMessageTime; }
    public List<String> getRecentMessages() { return new ArrayList<>(recentMessages); }
    
    // Статистика
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("chattiness", chattiness);
        stats.addProperty("humor", humor);
        stats.addProperty("sarcasm", sarcasm);
        stats.addProperty("allow_dark_humor", allowDarkHumor);
        stats.addProperty("last_message_time", lastMessageTime);
        stats.addProperty("recent_messages_count", recentMessages.size());
        stats.addProperty("known_players_count", lastPlayerInteraction.size());
        return stats;
    }
}