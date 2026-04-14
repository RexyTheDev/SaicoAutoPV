package me.rexy.saicopv;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
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
    private static final long CHECK_INTERVAL_MS = 30000L;
    private static final long PV_COMMAND_COOLDOWN_MS = 1000L;
    private static final long AUTO_SELL_INTERVAL_MS = 30000L;

    private int previousChestCount = -1;
    private long lastInventoryCheckAt;
    private long lastPvCommandAt;
    private long lastAutoSellAt;
    private boolean waitingForPvToOpen;
    private boolean autoSellEnabled;
    private int totalCaught;
    private final Map<String, Integer> caughtByName = new LinkedHashMap<String, Integer>();

    @EventHandler
    public void init(FMLInitializationEvent event)
    {
        MinecraftForge.EVENT_BUS.register(this);
        ClientCommandHandler.instance.registerCommand(new SaicoFishCommand());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || MC.thePlayer == null || MC.theWorld == null) {
            return;
        }

        if (waitingForPvToOpen) {
            handleOpenPv();
        }

        InventoryPlayer inventory = MC.thePlayer.inventory;
        int currentTrackedCount = countTrackedItems(inventory);
        long now = System.currentTimeMillis();

        if (autoSellEnabled && now - lastAutoSellAt >= AUTO_SELL_INTERVAL_MS) {
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

        if (now - lastInventoryCheckAt < CHECK_INTERVAL_MS) {
            return;
        }

        lastInventoryCheckAt = now;

        if (currentTrackedCount > previousChestCount && canSendPvCommand(now)) {
            MC.thePlayer.sendChatMessage("/pv");
            lastPvCommandAt = now;
            waitingForPvToOpen = true;
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

    private void handleOpenPv()
    {
        if (!(MC.currentScreen instanceof GuiChest)) {
            return;
        }

        EntityPlayer player = MC.thePlayer;
        Container openContainer = player.openContainer;

        if (!(openContainer instanceof ContainerChest)) {
            return;
        }

        ContainerChest chestContainer = (ContainerChest) openContainer;
        ItemStack stackToMove = findTrackedStack(chestContainer);

        if (stackToMove == null) {
            waitingForPvToOpen = false;
            return;
        }

        int chestSlot = findTrackedSlot(chestContainer);
        MC.playerController.windowClick(chestContainer.windowId, chestSlot, 0, 1, player);
        trackCaughtChest(stackToMove);
        player.closeScreen();
        waitingForPvToOpen = false;
        previousChestCount = countTrackedItems(player.inventory);
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

    private boolean isTrackedItem(ItemStack stack)
    {
        Item item = stack.getItem();
        return item == CHEST_ITEM || item == GOLD_INGOT_ITEM || item == PAPER_ITEM || item == BLAZE_POWDER_ITEM;
    }

    private void trackCaughtChest(ItemStack stack)
    {
        String itemName = stack.hasDisplayName() ? stack.getDisplayName() : stack.getDisplayName();
        int amount = stack.stackSize;

        totalCaught += amount;

        Integer existingAmount = caughtByName.get(itemName);
        if (existingAmount == null) {
            existingAmount = Integer.valueOf(0);
        }

        caughtByName.put(itemName, Integer.valueOf(existingAmount.intValue() + amount));
    }

    private void clearCaughtData()
    {
        totalCaught = 0;
        caughtByName.clear();
    }

    private void toggleAutoSell(ICommandSender sender)
    {
        autoSellEnabled = !autoSellEnabled;
        lastAutoSellAt = autoSellEnabled ? 0L : lastAutoSellAt;

        if (autoSellEnabled) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "SaicoFish auto-sell enabled."));
            return;
        }

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "SaicoFish auto-sell disabled."));
    }

    private void sendCaughtSummary()
    {
        if (MC.thePlayer == null) {
            return;
        }

        MC.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GOLD + "SaicoFish caught: " + EnumChatFormatting.YELLOW + totalCaught));

        if (caughtByName.isEmpty()) {
            MC.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + "No chest variants tracked yet."));
            return;
        }

        for (Map.Entry<String, Integer> entry : caughtByName.entrySet()) {
            MC.thePlayer.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + entry.getKey() + EnumChatFormatting.WHITE + ": " + EnumChatFormatting.GREEN + entry.getValue()));
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
            return "/saicofish [clear|sell]";
        }

        @Override
        public void processCommand(ICommandSender sender, String[] args)
        {
            if (args.length > 0 && "clear".equalsIgnoreCase(args[0])) {
                clearCaughtData();
                sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + "SaicoFish tracker cleared."));
                return;
            }

            if (args.length > 0 && "sell".equalsIgnoreCase(args[0])) {
                toggleAutoSell(sender);
                return;
            }

            sendCaughtSummary();
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
}

