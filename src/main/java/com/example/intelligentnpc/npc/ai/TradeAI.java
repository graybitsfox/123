package com.example.intelligentnpc.npc.ai;

import com.example.intelligentnpc.IntelligentNPCMod;
import com.example.intelligentnpc.npc.NPCEntity;
import com.example.intelligentnpc.npc.NPCMemory;
import com.google.gson.JsonObject;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TradeAI {
    private final NPCEntity npc;
    private final NPCMemory memory;
    
    // Торговые предложения и состояние
    private final Map<String, TradeOffer> availableOffers = new ConcurrentHashMap<>();
    private final Map<String, Double> itemValues = new ConcurrentHashMap<>();
    private final List<ActiveTrade> activeTrades = new ArrayList<>();
    
    // Настройки торговли
    private double profitMargin = 0.2; // 20% наценка
    private int maxTradesPerDay = 10;
    private boolean allowBargaining = true;
    private double charismaBonus = 0.1; // Бонус от навыка общения
    
    // Статистика
    private int totalTrades = 0;
    private double totalProfit = 0.0;
    private int failedTrades = 0;
    
    // Базовые цены предметов
    static {
        // Инициализация будет происходить в конструкторе
    }
    
    public TradeAI(NPCEntity npc) {
        this.npc = npc;
        this.memory = npc.getMemory();
        
        initializeItemValues();
        loadTradingSkillsFromMemory();
        generateInitialOffers();
    }
    
    public void registerGoals(GoalSelector goalSelector) {
        goalSelector.add(5, new FindTradingPartnerGoal());
        goalSelector.add(6, new ProcessTradeGoal());
    }
    
    private void initializeItemValues() {
        // Базовые цены для основных предметов
        itemValues.put("minecraft:diamond", 100.0);
        itemValues.put("minecraft:iron_ingot", 10.0);
        itemValues.put("minecraft:gold_ingot", 25.0);
        itemValues.put("minecraft:emerald", 50.0);
        itemValues.put("minecraft:coal", 1.0);
        itemValues.put("minecraft:wood", 0.5);
        itemValues.put("minecraft:stone", 0.1);
        itemValues.put("minecraft:wheat", 2.0);
        itemValues.put("minecraft:bread", 3.0);
        itemValues.put("minecraft:cooked_beef", 5.0);
        itemValues.put("minecraft:apple", 1.5);
        itemValues.put("minecraft:book", 8.0);
        itemValues.put("minecraft:paper", 1.0);
        itemValues.put("minecraft:leather", 4.0);
        itemValues.put("minecraft:wool", 2.0);
    }
    
    private void loadTradingSkillsFromMemory() {
        int tradingSkill = memory.getSkillLevel("trading");
        
        // Улучшение торговых способностей с ростом навыка
        if (tradingSkill >= 10) {
            maxTradesPerDay = 15;
            profitMargin = 0.15; // Меньше наценка, больше клиентов
        }
        if (tradingSkill >= 20) {
            maxTradesPerDay = 20;
            charismaBonus = 0.2;
            profitMargin = 0.1;
        }
        
        IntelligentNPCMod.LOGGER.debug("TradeAI loaded with trading skill level: {}", tradingSkill);
    }
    
    private void generateInitialOffers() {
        // Создание базовых торговых предложений
        String role = npc.getRole().toLowerCase();
        
        switch (role) {
            case "trader", "merchant" -> {
                createOffer("iron_tools", "железные инструменты", 
                    List.of("minecraft:iron_ingot:3"), List.of("minecraft:iron_pickaxe:1"));
                createOffer("food_package", "продуктовый набор",
                    List.of("minecraft:emerald:2"), List.of("minecraft:bread:5", "minecraft:cooked_beef:3"));
            }
            case "farmer" -> {
                createOffer("crop_seeds", "семена",
                    List.of("minecraft:emerald:1"), List.of("minecraft:wheat_seeds:8", "minecraft:carrot:4"));
                createOffer("fresh_food", "свежие продукты",
                    List.of("minecraft:iron_ingot:1"), List.of("minecraft:bread:3", "minecraft:apple:2"));
            }
            case "blacksmith" -> {
                createOffer("iron_armor", "железная броня",
                    List.of("minecraft:iron_ingot:8"), List.of("minecraft:iron_chestplate:1"));
                createOffer("weapon_upgrade", "улучшение оружия",
                    List.of("minecraft:diamond:2"), List.of("minecraft:diamond_sword:1"));
            }
            default -> {
                createOffer("basic_trade", "базовый обмен",
                    List.of("minecraft:coal:8"), List.of("minecraft:iron_ingot:1"));
            }
        }
    }
    
    private void createOffer(String id, String name, List<String> required, List<String> offered) {
        TradeOffer offer = new TradeOffer(id, name, required, offered);
        availableOffers.put(id, offer);
    }
    
    public void tick() {
        // Обновление активных торгов
        updateActiveTrades();
        
        // Анализ рынка и обновление цен
        if (npc.age % 1200 == 0) { // Каждую минуту
            analyzeMarketPrices();
        }
        
        // Генерация новых предложений
        if (npc.age % 6000 == 0) { // Каждые 5 минут
            generateContextualOffers();
        }
    }
    
    public void handleTradeCommand(String command, ServerPlayerEntity player) {
        try {
            TradeRequest request = parseTradeCommand(command, player);
            if (request != null) {
                processTradeRequest(request, player);
            }
        } catch (Exception e) {
            player.sendMessage(Text.literal("§cОшибка при обработке торговой команды: " + e.getMessage()), false);
            IntelligentNPCMod.LOGGER.error("Error processing trade command: {}", e.getMessage());
        }
    }
    
    private TradeRequest parseTradeCommand(String command, ServerPlayerEntity player) {
        String lowerCommand = command.toLowerCase();
        
        if (lowerCommand.contains("покажи") || lowerCommand.contains("show") || 
            lowerCommand.contains("список") || lowerCommand.contains("list")) {
            return new TradeRequest("list", null, null, player.getUuid());
        }
        
        if (lowerCommand.contains("купить") || lowerCommand.contains("buy")) {
            // Парсинг команды покупки
            return new TradeRequest("buy", extractItemFromCommand(command), null, player.getUuid());
        }
        
        if (lowerCommand.contains("продать") || lowerCommand.contains("sell")) {
            // Парсинг команды продажи
            return new TradeRequest("sell", extractItemFromCommand(command), null, player.getUuid());
        }
        
        if (lowerCommand.contains("обменять") || lowerCommand.contains("exchange")) {
            return new TradeRequest("exchange", null, null, player.getUuid());
        }
        
        return null;
    }
    
    private String extractItemFromCommand(String command) {
        // Простое извлечение предмета из команды
        String[] words = command.toLowerCase().split(" ");
        for (String word : words) {
            if (itemValues.containsKey("minecraft:" + word)) {
                return "minecraft:" + word;
            }
        }
        return null;
    }
    
    private void processTradeRequest(TradeRequest request, ServerPlayerEntity player) {
        switch (request.type) {
            case "list" -> showAvailableOffers(player);
            case "buy" -> processBuyRequest(request, player);
            case "sell" -> processSellRequest(request, player);
            case "exchange" -> processExchangeRequest(request, player);
        }
    }
    
    private void showAvailableOffers(ServerPlayerEntity player) {
        player.sendMessage(Text.literal("§6=== Торговые предложения " + npc.getNpcName() + " ==="), false);
        
        if (availableOffers.isEmpty()) {
            player.sendMessage(Text.literal("§eНа данный момент предложений нет"), false);
            return;
        }
        
        int index = 1;
        for (TradeOffer offer : availableOffers.values()) {
            String offerText = String.format("§e%d. §f%s", index++, offer.name);
            player.sendMessage(Text.literal(offerText), false);
            
            String requiredText = "  §7Требуется: §f" + String.join(", ", offer.requiredItems);
            player.sendMessage(Text.literal(requiredText), false);
            
            String offeredText = "  §7Предлагаю: §f" + String.join(", ", offer.offeredItems);
            player.sendMessage(Text.literal(offeredText), false);
        }
        
        npc.getChatAI().say("Выбирай что понравится, " + player.getName().getString() + "!");
    }
    
    private void processBuyRequest(TradeRequest request, ServerPlayerEntity player) {
        if (request.itemId == null) {
            player.sendMessage(Text.literal("§cУкажите, что хотите купить"), false);
            return;
        }
        
        // Поиск подходящего предложения
        TradeOffer offer = findOfferForItem(request.itemId);
        if (offer == null) {
            player.sendMessage(Text.literal("§cУ меня нет этого товара"), false);
            npc.getChatAI().say("Извини, " + player.getName().getString() + ", этого у меня нет");
            return;
        }
        
        // Проверка наличия требуемых предметов у игрока
        if (!playerHasRequiredItems(player, offer.requiredItems)) {
            player.sendMessage(Text.literal("§cУ вас недостаточно ресурсов для этой сделки"), false);
            npc.getChatAI().say("Не хватает ресурсов, " + player.getName().getString());
            return;
        }
        
        // Выполнение сделки
        executeTrade(player, offer);
    }
    
    private void processSellRequest(TradeRequest request, ServerPlayerEntity player) {
        player.sendMessage(Text.literal("§eФункция продажи пока не реализована"), false);
        npc.getChatAI().say("Пока что я только продаю, " + player.getName().getString());
    }
    
    private void processExchangeRequest(TradeRequest request, ServerPlayerEntity player) {
        player.sendMessage(Text.literal("§eПокажите мне свои товары, и я предложу обмен"), false);
        npc.getChatAI().say("Давайте посмотрим, что у вас есть для обмена");
    }
    
    private TradeOffer findOfferForItem(String itemId) {
        return availableOffers.values().stream()
            .filter(offer -> offer.offeredItems.stream().anyMatch(item -> item.contains(itemId)))
            .findFirst()
            .orElse(null);
    }
    
    private boolean playerHasRequiredItems(ServerPlayerEntity player, List<String> requiredItems) {
        // TODO: Реализовать проверку инвентаря игрока
        // Пока что считаем, что у игрока всегда есть нужные предметы
        return true;
    }
    
    private void executeTrade(ServerPlayerEntity player, TradeOffer offer) {
        // TODO: Реализовать реальный обмен предметами
        
        // Запись сделки в память
        String tradeDescription = String.format("Trade with %s: %s for %s", 
            player.getName().getString(), 
            String.join(", ", offer.requiredItems),
            String.join(", ", offer.offeredItems));
        
        double tradeValue = calculateTradeValue(offer);
        memory.recordTrade(player.getName().getString(), 
            String.join(", ", offer.requiredItems),
            String.join(", ", offer.offeredItems),
            tradeValue);
        
        // Обновление статистики
        totalTrades++;
        totalProfit += tradeValue * profitMargin;
        
        // Уведомления
        player.sendMessage(Text.literal("§aСделка успешно завершена!"), false);
        npc.getChatAI().say("Отличная сделка, " + player.getName().getString() + "! Обращайтесь еще!");
        
        // Опыт и навыки
        memory.increaseSkill("trading", 2);
        memory.addExperience("trading", tradeDescription);
        
        IntelligentNPCMod.LOGGER.info("NPC {} completed trade with {}: {}", 
            npc.getNpcName(), player.getName().getString(), tradeDescription);
    }
    
    private double calculateTradeValue(TradeOffer offer) {
        double requiredValue = offer.requiredItems.stream()
            .mapToDouble(this::getItemValue)
            .sum();
        
        double offeredValue = offer.offeredItems.stream()
            .mapToDouble(this::getItemValue)
            .sum();
        
        return Math.min(requiredValue, offeredValue);
    }
    
    private double getItemValue(String itemString) {
        String[] parts = itemString.split(":");
        if (parts.length >= 2) {
            String itemId = parts[0] + ":" + parts[1];
            double baseValue = itemValues.getOrDefault(itemId, 1.0);
            
            // Учитываем количество
            if (parts.length >= 3) {
                try {
                    int quantity = Integer.parseInt(parts[2]);
                    return baseValue * quantity;
                } catch (NumberFormatException e) {
                    return baseValue;
                }
            }
            
            return baseValue;
        }
        
        return itemValues.getOrDefault(itemString, 1.0);
    }
    
    private void updateActiveTrades() {
        activeTrades.removeIf(trade -> 
            System.currentTimeMillis() - trade.startTime > 300000); // 5 минут
    }
    
    private void analyzeMarketPrices() {
        // Анализ последних сделок для корректировки цен
        List<NPCMemory.TradeRecord> recentTrades = memory.getTradeHistory();
        
        if (recentTrades.size() >= 5) {
            double averageValue = memory.getAverageTradeValue();
            
            // Корректировка наценки в зависимости от успешности торгов
            if (totalTrades > failedTrades * 2) {
                profitMargin = Math.min(0.3, profitMargin + 0.01);
            } else {
                profitMargin = Math.max(0.05, profitMargin - 0.01);
            }
        }
        
        memory.addExperience("trading", "Analyzed market prices");
    }
    
    private void generateContextualOffers() {
        // Генерация предложений на основе времени суток, биома, потребностей
        long timeOfDay = npc.getWorld().getTimeOfDay() % 24000;
        
        if (timeOfDay > 13000 && timeOfDay < 23000) { // Ночь
            // Предложения для ночи: факелы, кровати
            createOffer("night_safety", "ночная безопасность",
                List.of("minecraft:coal:4"), List.of("minecraft:torch:8"));
        } else {
            // Дневные предложения: инструменты, еда
            createOffer("day_work", "рабочие инструменты",
                List.of("minecraft:iron_ingot:2"), List.of("minecraft:iron_shovel:1"));
        }
        
        // Предложения в зависимости от биома
        String biome = npc.getWorld().getBiome(npc.getBlockPos()).getIdAsString();
        if (biome.contains("desert")) {
            createOffer("desert_survival", "выживание в пустыне",
                List.of("minecraft:emerald:1"), List.of("minecraft:water_bucket:1"));
        }
    }
    
    // Поиск торговых партнеров
    private List<VillagerEntity> findNearbyVillagers() {
        return npc.getWorld().getEntitiesByClass(
            VillagerEntity.class, npc.getBoundingBox().expand(16), v -> true);
    }
    
    private List<PlayerEntity> findNearbyPlayers() {
        return npc.getWorld().getEntitiesByClass(
            PlayerEntity.class, npc.getBoundingBox().expand(12), p -> true);
    }
    
    // Геттеры и сеттеры
    public Map<String, TradeOffer> getAvailableOffers() { return new HashMap<>(availableOffers); }
    public int getTotalTrades() { return totalTrades; }
    public double getTotalProfit() { return totalProfit; }
    public int getFailedTrades() { return failedTrades; }
    public double getProfitMargin() { return profitMargin; }
    
    public void setProfitMargin(double margin) {
        this.profitMargin = Math.max(0.0, Math.min(0.5, margin));
    }
    
    public void setMaxTradesPerDay(int maxTrades) {
        this.maxTradesPerDay = Math.max(1, Math.min(50, maxTrades));
    }
    
    public void setAllowBargaining(boolean allow) {
        this.allowBargaining = allow;
    }
    
    // Статистика
    public JsonObject getStatistics() {
        JsonObject stats = new JsonObject();
        stats.addProperty("total_trades", totalTrades);
        stats.addProperty("total_profit", totalProfit);
        stats.addProperty("failed_trades", failedTrades);
        stats.addProperty("success_rate", totalTrades > 0 ? (double)(totalTrades - failedTrades) / totalTrades : 0.0);
        stats.addProperty("available_offers", availableOffers.size());
        stats.addProperty("profit_margin", profitMargin);
        stats.addProperty("max_trades_per_day", maxTradesPerDay);
        stats.addProperty("allow_bargaining", allowBargaining);
        return stats;
    }
    
    // Внутренние классы
    public static class TradeOffer {
        public final String id;
        public final String name;
        public final List<String> requiredItems;
        public final List<String> offeredItems;
        public final long creationTime;
        
        public TradeOffer(String id, String name, List<String> requiredItems, List<String> offeredItems) {
            this.id = id;
            this.name = name;
            this.requiredItems = new ArrayList<>(requiredItems);
            this.offeredItems = new ArrayList<>(offeredItems);
            this.creationTime = System.currentTimeMillis();
        }
    }
    
    private static class TradeRequest {
        final String type;
        final String itemId;
        final Integer quantity;
        final UUID playerId;
        
        TradeRequest(String type, String itemId, Integer quantity, UUID playerId) {
            this.type = type;
            this.itemId = itemId;
            this.quantity = quantity;
            this.playerId = playerId;
        }
    }
    
    private static class ActiveTrade {
        final UUID playerId;
        final String offerId;
        final long startTime;
        
        ActiveTrade(UUID playerId, String offerId) {
            this.playerId = playerId;
            this.offerId = offerId;
            this.startTime = System.currentTimeMillis();
        }
    }
    
    // AI Goals
    private class FindTradingPartnerGoal extends Goal {
        private PlayerEntity targetPlayer;
        
        public FindTradingPartnerGoal() {
            this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
        }
        
        @Override
        public boolean canStart() {
            List<PlayerEntity> players = findNearbyPlayers();
            if (!players.isEmpty()) {
                targetPlayer = players.get(0);
                return true;
            }
            return false;
        }
        
        @Override
        public void start() {
            if (targetPlayer != null) {
                npc.getChatAI().say("Эй, " + targetPlayer.getName().getString() + "! Хотите что-нибудь купить?");
            }
        }
        
        @Override
        public void tick() {
            if (targetPlayer != null) {
                npc.getLookControl().lookAt(targetPlayer);
                
                if (npc.squaredDistanceTo(targetPlayer) > 16) {
                    npc.getNavigation().startMovingTo(
                        targetPlayer.getX(), targetPlayer.getY(), targetPlayer.getZ(), 0.8);
                }
            }
        }
        
        @Override
        public boolean shouldContinue() {
            return targetPlayer != null && targetPlayer.isAlive() && 
                   npc.squaredDistanceTo(targetPlayer) < 64;
        }
    }
    
    private class ProcessTradeGoal extends Goal {
        public ProcessTradeGoal() {
            this.setControls(EnumSet.of(Control.MOVE));
        }
        
        @Override
        public boolean canStart() {
            return !activeTrades.isEmpty();
        }
        
        @Override
        public void tick() {
            // Обработка активных торгов
            for (ActiveTrade trade : new ArrayList<>(activeTrades)) {
                // TODO: Реализовать логику обработки активных торгов
            }
        }
    }
}