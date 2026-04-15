package me.rexy.saicopv;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(modid = Saicopv.MODID, version = Saicopv.VERSION)
public class Saicopv
{
    public static final String MODID = "saicopv";
    public static final String VERSION = "1.0";

    private static final Minecraft MC = Minecraft.getMinecraft();
    private static final Item CHEST_ITEM = Item.getItemFromBlock(Blocks.chest);
    private static final Item GOLD_INGOT_ITEM = Items.gold_ingot;
    private static final Item PAPER_ITEM = Items.paper;
    private static final Item BLAZE_POWDER_ITEM = Items.blaze_powder;
    private static final Item QUARTZ_ITEM = Items.quartz;
    private static final long PV_COMMAND_COOLDOWN_MS = 1000L;
    private static final long PV_RETRY_INTERVAL_MS = 2000L;
    private static final int MAX_PV_RETRIES = 5;
    private static final long DEFAULT_CHECK_INTERVAL_MS = 30000L;
    private static final long DEFAULT_AUTO_SELL_INTERVAL_MS = 30000L;
    private static final long FAST_PV_MOVE_DELAY_MS = 50L;
    private static final long SLOW_PV_MOVE_DELAY_MIN_MS = 250L;
    private static final long SLOW_PV_MOVE_DELAY_MAX_MS = 650L;
    private static final long MIN_INTERVAL_MS = 1000L;
    private static final String CONFIG_FILE_NAME = "saicopv.properties";

    private int previousChestCount = -1;
    private long lastInventoryCheckAt;
    private long lastPvCommandAt;
    private long lastAutoSellAt;
    private long lastPvRetryAt;
    private long nextPvMoveAt;
    private long checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
    private long autoSellIntervalMs = DEFAULT_AUTO_SELL_INTERVAL_MS;
    private boolean waitingForPvToOpen;
    private int pvTicksOpen;
    private int pvItemsMovedThisSession;
    private int pvRetryCount;
    private boolean autoSellEnabled;
    private int totalCaught;
    private int totalCaughtAllTime;
    private boolean slowPvTransferEnabled;
    private File configFile;
    private final Random random = new Random();
    private final Map<String, Integer> caughtByNameSession = new LinkedHashMap<String, Integer>();
    private final Map<String, Integer> caughtByNameAllTime = new LinkedHashMap<String, Integer>();

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        File configDir = new File(MC.mcDataDir, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }

        configFile = new File(configDir, CONFIG_FILE_NAME);
        loadConfig();
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new SaicoFishCommand());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || MC.thePlayer == null || MC.theWorld == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (shouldWaitForOpenGui()) {
            return;
        }

        if (waitingForPvToOpen) {
            handleOpenPv(now);
        }

        InventoryPlayer inventory = MC.thePlayer.inventory;
        int currentTrackedCount = countTrackedItems(inventory);

        if (autoSellEnabled && isHoldingFishingRod() && now - lastAutoSellAt >= autoSellIntervalMs) {
            MC.thePlayer.sendChatMessage("/sell inv");
            lastAutoSellAt = now;
        }

        if (!isHoldingFishingRod()) {
            previousChestCount = currentTrackedCount;
            return;
        }

        if (previousChestCount < 0) {
            previousChestCount = currentTrackedCount;
            lastInventoryCheckAt = now;
            return;
        }

        if (now - lastInventoryCheckAt < checkIntervalMs) {
            return;
        }

        lastInventoryCheckAt = now;

        if (currentTrackedCount > previousChestCount && canSendPvCommand(now)) {
            sendClientMessage(EnumChatFormatting.YELLOW + "SaicoFish detected tracked loot. Opening /pv...");
            MC.thePlayer.sendChatMessage("/pv");
            lastPvCommandAt = now;
            lastPvRetryAt = now;
            waitingForPvToOpen = true;
            pvTicksOpen = 0;
            pvItemsMovedThisSession = 0;
            pvRetryCount = 0;
            nextPvMoveAt = 0L;
        }

        previousChestCount = currentTrackedCount;
    }

    private boolean isHoldingFishingRod()
    {
        ItemStack heldItem = MC.thePlayer.getHeldItem();
        return heldItem != null && heldItem.getItem() == Items.fishing_rod;
    }

    private int countTrackedItems(InventoryPlayer inventory)
    {
        int trackedItemCount = 0;

        for (ItemStack stack : inventory.mainInventory) {
            if (stack != null && isTrackedItem(stack)) {
                trackedItemCount += stack.stackSize;
            }
        }

        return trackedItemCount;
    }

    private boolean canSendPvCommand(long now)
    {
        return now - lastPvCommandAt >= PV_COMMAND_COOLDOWN_MS;
    }

    private boolean shouldWaitForOpenGui()
    {
        GuiScreen currentScreen = MC.currentScreen;
        if (currentScreen == null) {
            return false;
        }

        if (currentScreen instanceof GuiChat || currentScreen instanceof GuiIngameMenu || currentScreen instanceof GuiChest) {
            return false;
        }

        return true;
    }

    private void handleOpenPv(long now)
    {
        if (!(MC.currentScreen instanceof GuiChest)) {
            if (now - lastPvRetryAt >= PV_RETRY_INTERVAL_MS) {
                if (pvRetryCount >= MAX_PV_RETRIES) {
                    sendClientMessage(EnumChatFormatting.RED + "SaicoFish could not open /pv automatically. Try /pv manually.");
                    waitingForPvToOpen = false;
                    pvTicksOpen = 0;
                    pvItemsMovedThisSession = 0;
                    pvRetryCount = 0;
                    return;
                }

                pvRetryCount++;
                lastPvRetryAt = now;
                sendClientMessage(EnumChatFormatting.YELLOW + "SaicoFish retrying /pv... (" + pvRetryCount + "/" + MAX_PV_RETRIES + ")");
                MC.thePlayer.sendChatMessage("/pv");
            }
            return;
        }

        EntityPlayer player = MC.thePlayer;
        Container openContainer = player.openContainer;

        if (!(openContainer instanceof ContainerChest)) {
            return;
        }

        pvRetryCount = 0;
        pvTicksOpen++;
        ContainerChest chestContainer = (ContainerChest) openContainer;
        if (pvTicksOpen < 2) {
            return;
        }

        if (nextPvMoveAt > now) {
            return;
        }

        ItemStack stackToMove = findTrackedStack(chestContainer);
        if (stackToMove == null) {
            sendClientMessage(EnumChatFormatting.GREEN + "SaicoFish moved " + pvItemsMovedThisSession + " tracked item(s) to PV.");
            player.closeScreen();
            waitingForPvToOpen = false;
            pvTicksOpen = 0;
            pvItemsMovedThisSession = 0;
            pvRetryCount = 0;
            previousChestCount = countTrackedItems(player.inventory);
            return;
        }

        int trackedSlot = findTrackedSlot(chestContainer);
        if (trackedSlot < 0) {
            return;
        }

        MC.playerController.windowClick(chestContainer.windowId, trackedSlot, 0, 1, player);
        trackCaughtChest(stackToMove);
        pvItemsMovedThisSession += stackToMove.stackSize;
        nextPvMoveAt = now + getPvMoveDelayMs();
        sendClientMessage(EnumChatFormatting.AQUA + "Moved to PV: " + EnumChatFormatting.WHITE + stackToMove.getDisplayName() + EnumChatFormatting.GRAY + " x" + stackToMove.stackSize);
        previousChestCount = countTrackedItems(player.inventory);
    }

    private boolean isTrackedItem(ItemStack stack)
    {
        Item item = stack.getItem();
        return item == CHEST_ITEM || item == GOLD_INGOT_ITEM || item == PAPER_ITEM || item == BLAZE_POWDER_ITEM || item == QUARTZ_ITEM;
    }

    private int findTrackedSlot(ContainerChest chestContainer)
    {
        for (Slot slot : chestContainer.inventorySlots) {
            if (slot.inventory instanceof InventoryPlayer && slot.getHasStack()) {
                ItemStack stack = slot.getStack();
                if (stack != null && isTrackedItem(stack)) {
                    return slot.slotNumber;
                }
            }
        }

        return -1;
    }

    private ItemStack findTrackedStack(ContainerChest chestContainer)
    {
        for (Slot slot : chestContainer.inventorySlots) {
            if (slot.inventory instanceof InventoryPlayer && slot.getHasStack()) {
                ItemStack stack = slot.getStack();
                if (stack != null && isTrackedItem(stack)) {
                    return stack.copy();
                }
            }
        }

        return null;
    }

    private void trackCaughtChest(ItemStack stack)
    {
        String itemName = stack.hasDisplayName() ? stack.getDisplayName() : stack.getDisplayName();
        int amount = stack.stackSize;

        totalCaught += amount;
        totalCaughtAllTime += amount;

        incrementCaughtMap(caughtByNameSession, itemName, amount);
        incrementCaughtMap(caughtByNameAllTime, itemName, amount);
        saveConfig();
    }

    private void incrementCaughtMap(Map<String, Integer> caughtMap, String itemName, int amount)
    {
        Integer existingAmount = caughtMap.get(itemName);
        if (existingAmount == null) {
            existingAmount = Integer.valueOf(0);
        }

        caughtMap.put(itemName, Integer.valueOf(existingAmount.intValue() + amount));
    }

    private void clearCaughtData()
    {
        totalCaught = 0;
        caughtByNameSession.clear();
    }

    private void toggleAutoSell(ICommandSender sender)
    {
        autoSellEnabled = !autoSellEnabled;
        lastAutoSellAt = autoSellEnabled ? 0L : lastAutoSellAt;
        saveConfig();

        if (autoSellEnabled) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "SaicoFish auto-sell enabled."));
            return;
        }

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "SaicoFish auto-sell disabled."));
    }

    private void sendCaughtSummary()
    {
        sendCaughtSummary("SaicoFish caught this session: ", totalCaught, caughtByNameSession);
    }

    private void sendAllTimeCaughtSummary()
    {
        sendCaughtSummary("SaicoFish caught all time: ", totalCaughtAllTime, caughtByNameAllTime);
    }

    private void sendCaughtSummary(String title, int total, Map<String, Integer> caughtMap)
    {
        if (MC.thePlayer == null) {
            return;
        }

        MC.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + title + EnumChatFormatting.YELLOW + total));

        if (caughtMap.isEmpty()) {
            MC.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "No tracked item variants recorded yet."));
            return;
        }

        for (Map.Entry<String, Integer> entry : caughtMap.entrySet()) {
            MC.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + entry.getKey() + EnumChatFormatting.WHITE + ": " + EnumChatFormatting.GREEN + entry.getValue()));
        }
    }

    private long getPvMoveDelayMs()
    {
        if (!slowPvTransferEnabled) {
            return FAST_PV_MOVE_DELAY_MS;
        }

        long range = SLOW_PV_MOVE_DELAY_MAX_MS - SLOW_PV_MOVE_DELAY_MIN_MS;
        return SLOW_PV_MOVE_DELAY_MIN_MS + (long) (random.nextDouble() * (range + 1L));
    }

    private void setInterval(ICommandSender sender, String type, String secondsText)
    {
        int seconds;

        try {
            seconds = Integer.parseInt(secondsText);
        } catch (NumberFormatException exception) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Use a whole number of seconds."));
            return;
        }

        if (seconds < 1) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Seconds must be at least 1."));
            return;
        }

        long intervalMs = Math.max(MIN_INTERVAL_MS, seconds * 1000L);

        if ("sell".equalsIgnoreCase(type)) {
            autoSellIntervalMs = intervalMs;
            saveConfig();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "SaicoFish auto-sell interval set to " + seconds + "s."));
            return;
        }

        if ("check".equalsIgnoreCase(type)) {
            checkIntervalMs = intervalMs;
            saveConfig();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "SaicoFish loot check interval set to " + seconds + "s."));
            return;
        }

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Unknown interval type. Use sell or check."));
    }

    private void setPvMode(ICommandSender sender, String mode)
    {
        if ("slow".equalsIgnoreCase(mode)) {
            slowPvTransferEnabled = true;
            saveConfig();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "SaicoFish PV mode set to slow."));
            return;
        }

        if ("fast".equalsIgnoreCase(mode)) {
            slowPvTransferEnabled = false;
            saveConfig();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "SaicoFish PV mode set to fast."));
            return;
        }

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Unknown PV mode. Use fast or slow."));
    }

    private void sendSettingsSummary()
    {
        sendClientMessage(EnumChatFormatting.GOLD + "SaicoFish settings:");
        sendClientMessage(EnumChatFormatting.YELLOW + "Check interval: " + EnumChatFormatting.WHITE + (checkIntervalMs / 1000L) + "s");
        sendClientMessage(EnumChatFormatting.YELLOW + "Sell interval: " + EnumChatFormatting.WHITE + (autoSellIntervalMs / 1000L) + "s");
        sendClientMessage(EnumChatFormatting.YELLOW + "PV mode: " + EnumChatFormatting.WHITE + (slowPvTransferEnabled ? "slow" : "fast"));
        sendClientMessage(EnumChatFormatting.YELLOW + "Auto-sell: " + EnumChatFormatting.WHITE + (autoSellEnabled ? "enabled" : "disabled"));
    }

    private void loadConfig()
    {
        Properties properties = new Properties();

        if (configFile.exists()) {
            FileInputStream inputStream = null;
            try {
                inputStream = new FileInputStream(configFile);
                properties.load(inputStream);
            } catch (IOException ignored) {
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        checkIntervalMs = getLongProperty(properties, "checkIntervalMs", DEFAULT_CHECK_INTERVAL_MS);
        autoSellIntervalMs = getLongProperty(properties, "autoSellIntervalMs", DEFAULT_AUTO_SELL_INTERVAL_MS);
        autoSellEnabled = Boolean.parseBoolean(properties.getProperty("autoSellEnabled", "false"));
        slowPvTransferEnabled = Boolean.parseBoolean(properties.getProperty("slowPvTransferEnabled", "false"));
        totalCaughtAllTime = (int) getLongProperty(properties, "totalCaughtAllTime", 0L);
        loadCaughtMap(properties, "alltime.", caughtByNameAllTime);
    }

    private void saveConfig()
    {
        if (configFile == null) {
            return;
        }

        Properties properties = new Properties();
        properties.setProperty("checkIntervalMs", String.valueOf(checkIntervalMs));
        properties.setProperty("autoSellIntervalMs", String.valueOf(autoSellIntervalMs));
        properties.setProperty("autoSellEnabled", String.valueOf(autoSellEnabled));
        properties.setProperty("slowPvTransferEnabled", String.valueOf(slowPvTransferEnabled));
        properties.setProperty("totalCaughtAllTime", String.valueOf(totalCaughtAllTime));
        saveCaughtMap(properties, "alltime.", caughtByNameAllTime);

        FileOutputStream outputStream = null;
        try {
            outputStream = new FileOutputStream(configFile);
            properties.store(outputStream, "SaicoFish settings");
        } catch (IOException ignored) {
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void loadCaughtMap(Properties properties, String prefix, Map<String, Integer> targetMap)
    {
        targetMap.clear();

        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                String itemName = key.substring(prefix.length());
                targetMap.put(itemName, Integer.valueOf((int) getLongProperty(properties, key, 0L)));
            }
        }
    }

    private void saveCaughtMap(Properties properties, String prefix, Map<String, Integer> caughtMap)
    {
        for (Map.Entry<String, Integer> entry : caughtMap.entrySet()) {
            properties.setProperty(prefix + entry.getKey(), String.valueOf(entry.getValue()));
        }
    }

    private long getLongProperty(Properties properties, String key, long defaultValue)
    {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }

        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private void sendClientMessage(String message)
    {
        if (MC.thePlayer != null) {
            MC.thePlayer.addChatMessage(new ChatComponentText(message));
        }
    }

    private class SaicoFishCommand extends CommandBase
    {
        @Override
        public String getCommandName()
        {
            return "saicofish";
        }

        @Override
        public String getCommandUsage(ICommandSender sender)
        {
            return "/saicofish [help|list|alltime|clear|sell|interval <sell|check> <seconds>|pvmode <fast|slow>|settings]";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args)
        {
            if (args.length == 0 || "help".equalsIgnoreCase(args[0])) {
                sendHelp(sender);
                return;
            }

            if (args.length > 0 && "clear".equalsIgnoreCase(args[0])) {
                clearCaughtData();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "SaicoFish tracker cleared."));
                return;
            }

            if (args.length > 0 && "list".equalsIgnoreCase(args[0])) {
                sendCaughtSummary();
                return;
            }

            if (args.length > 0 && "alltime".equalsIgnoreCase(args[0])) {
                sendAllTimeCaughtSummary();
                return;
            }

            if (args.length > 0 && "sell".equalsIgnoreCase(args[0])) {
                toggleAutoSell(sender);
                return;
            }

            if (args.length > 0 && "settings".equalsIgnoreCase(args[0])) {
                sendSettingsSummary();
                return;
            }

            if (args.length > 2 && "interval".equalsIgnoreCase(args[0])) {
                setInterval(sender, args[1], args[2]);
                return;
            }

            if (args.length > 1 && "pvmode".equalsIgnoreCase(args[0])) {
                setPvMode(sender, args[1]);
                return;
            }

            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "Unknown subcommand. Use /saicofish help"));
        }

        @Override
        public int getRequiredPermissionLevel()
        {
            return 0;
        }

        @Override
        public boolean canCommandSenderUseCommand(ICommandSender sender)
        {
            return true;
        }
    }

    private void sendHelp(ICommandSender sender)
    {
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "SaicoFish Commands"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish help" + EnumChatFormatting.GRAY + " - show this menu"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish list" + EnumChatFormatting.GRAY + " - caught this session"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish alltime" + EnumChatFormatting.GRAY + " - caught all time"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish clear" + EnumChatFormatting.GRAY + " - clear session stats"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish sell" + EnumChatFormatting.GRAY + " - toggle auto-sell"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish interval sell <seconds>" + EnumChatFormatting.GRAY + " - set auto-sell interval"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish interval check <seconds>" + EnumChatFormatting.GRAY + " - set loot check interval"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish pvmode fast|slow" + EnumChatFormatting.GRAY + " - PV transfer style"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW + "/saicofish settings" + EnumChatFormatting.GRAY + " - show current settings"));
    }
}

