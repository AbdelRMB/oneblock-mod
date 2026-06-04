package com.oneblock.mod.economy;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * GUI inventaire du shop OneCoins — fonctionne comme le TraderMenu.
 * Cliquer sur un item = acheter l'article avec les OneCoins du joueur.
 */
public class ShopMenu extends AbstractContainerMenu {

    public static final int SIZE = 54;

    record ShopEntry(int cost, ItemStack result, String label) {}

    private static final List<ShopEntry> ENTRIES = List.of(
        new ShopEntry(10,  stack(Items.WHEAT,             16), "Blé x16"),
        new ShopEntry(10,  stack(Items.WHEAT_SEEDS,       16), "Graines de blé x16"),
        new ShopEntry(12,  stack(Items.CARROT,            16), "Carotte x16"),
        new ShopEntry(12,  stack(Items.POTATO,             8), "Pomme de Terre x8"),
        new ShopEntry(15,  stack(Items.ICE,                4), "Glace x4"),
        new ShopEntry(18,  stack(Items.SUGAR_CANE,         8), "Canne à sucre x8"),
        new ShopEntry(20,  stack(Items.IRON_ORE,           1), "Minerai de fer"),
        new ShopEntry(25,  stack(Items.COAL,               8), "Charbon x8"),
        new ShopEntry(35,  stack(Items.GOLD_ORE,           1), "Minerai d'or"),
        new ShopEntry(50,  stack(Items.IRON_INGOT,         4), "Lingot de fer x4"),
        new ShopEntry(80,  stack(Items.EMERALD,            1), "Émeraude"),
        new ShopEntry(100, stack(Items.DIAMOND,            1), "Diamant"),
        new ShopEntry(120, stack(Items.GOLD_INGOT,         4), "Lingot d'or x4"),
        new ShopEntry(150, stack(Items.ENCHANTING_TABLE,   1), "Table d'enchantement"),
        new ShopEntry(200, stack(Items.SADDLE,             1), "Selle"),
        new ShopEntry(250, stack(Items.ELYTRA,             1), "Élytre"),
        new ShopEntry(500, stack(Items.NETHERITE_SCRAP,    1), "Scrap Netherite"),
        new ShopEntry(600, stack(Items.DIAMOND_SWORD,      1), "Épée en diamant"),
        // ── Livres ────────────────────────────────────────────────────────────
        new ShopEntry(5,   stack(Items.BOOK,               1), "Livre"),
        new ShopEntry(15,  stack(Items.BOOK,               8), "Livre x8"),
        new ShopEntry(30,  stack(Items.BOOKSHELF,          1), "Bibliothèque"),
        new ShopEntry(60,  stack(Items.WRITABLE_BOOK,      1), "Livre et plume")
    );

    private final SimpleContainer inv;
    private final UUID playerUUID;

    private ShopMenu(int id, Inventory playerInv, SimpleContainer inv, UUID playerUUID) {
        super(MenuType.GENERIC_9x6, id);
        this.inv = inv;
        this.playerUUID = playerUUID;

        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 9; col++) {
                final int idx = col + row * 9;
                addSlot(new Slot(inv, idx, 8 + col * 18, 18 + row * 18) {
                    @Override public boolean mayPlace(ItemStack s) { return false; }
                    @Override public boolean mayPickup(Player p)   { return idx < ENTRIES.size(); }
                    @Override
                    public void onTake(Player player, ItemStack taken) {
                        taken.setCount(0); // empêche de prendre l'item d'affichage
                        if (player instanceof ServerPlayer sp && idx < ENTRIES.size()) {
                            buyEntry(sp, idx);
                            // Remet l'item d'affichage dans le slot
                            ShopEntry e = ENTRIES.get(idx);
                            ItemStack display = e.result().copy();
                            display.set(DataComponents.CUSTOM_NAME, Component.literal(
                                "§e" + e.label() + " §8| §6" + e.cost() + " ⬡"
                            ));
                            inv.setItem(idx, display);
                            broadcastChanges();
                        }
                    }
                });
            }
        }

        // Inventaire joueur
        for (int row = 0; row < 3; row++)
            for (int col = 0; col < 9; col++)
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 139 + row * 18));
        for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInv, col, 8 + col * 18, 197));
    }

    @Override public ItemStack quickMoveStack(Player p, int i) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player p) { return true; }

    // ─── Ouverture ───────────────────────────────────────────────────────────

    public static void open(ServerPlayer player) {
        SimpleContainer inv = new SimpleContainer(SIZE);

        // Fond verre gris
        ItemStack glass = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        glass.set(DataComponents.CUSTOM_NAME, Component.literal("§8 "));
        for (int i = 0; i < SIZE; i++) inv.setItem(i, glass.copy());

        // Items
        fillItems(inv, player.getUUID());

        player.openMenu(new SimpleMenuProvider(
            (id, playerInv, p) -> new ShopMenu(id, playerInv, inv, player.getUUID()),
            Component.literal("§6✦ OneShop ✦  §e" + CoinManager.getCoins(player.getUUID()) + " §6⬡")
        ));
    }

    private static void fillItems(SimpleContainer inv, UUID id) {
        for (int i = 0; i < ENTRIES.size() && i < SIZE; i++) {
            ShopEntry e = ENTRIES.get(i);
            ItemStack display = e.result().copy();
            display.set(DataComponents.CUSTOM_NAME, Component.literal(
                "§e" + e.label() + " §8| §6" + e.cost() + " ⬡"
            ));
            inv.setItem(i, display);
        }
    }

    // ─── Achat ───────────────────────────────────────────────────────────────

    private static void buyEntry(ServerPlayer player, int idx) {
        ShopEntry entry = ENTRIES.get(idx);
        UUID id = player.getUUID();
        var server = (net.minecraft.server.MinecraftServer) player.level().getServer();

        if (!CoinManager.spendCoins(id, entry.cost(), server)) {
            player.sendSystemMessage(Component.literal(
                "§cPas assez de §6⬡ §c(besoin : §f" + entry.cost()
                + "§c, tu as : §f" + CoinManager.getCoins(id) + "§c)."));
            return;
        }
        player.getInventory().add(entry.result().copy());
        player.sendSystemMessage(Component.literal(
            "§a✓ §f" + entry.label() + " §7acheté — §7Solde : §e"
            + CoinManager.getCoins(id) + " §6⬡"));
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        return new ItemStack(item, count);
    }
}
