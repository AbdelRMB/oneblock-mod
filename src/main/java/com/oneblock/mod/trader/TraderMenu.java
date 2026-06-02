package com.oneblock.mod.trader;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Container du marchand — s'affiche comme un grand coffre (6 lignes).
 *
 * Lignes 1-5 : offres du marchand (slots 0-44, affichage uniquement).
 * Ligne 6    : bordure de séparation en verre gris (slots 45-53).
 *
 * Cliquer sur une offre appelle TraderManager.processBuy().
 * Impossible de prendre ou déposer des items dans les slots du marchand.
 */
public class TraderMenu extends AbstractContainerMenu {

    public static final int TRADER_SIZE   = 54;  // 6 lignes × 9
    public static final int TRADER_ROWS   = 6;
    public static final int TRADER_COLS   = 9;

    private final SimpleContainer         traderInv;
    private final List<TraderManager.TradeEntry> trades;

    public TraderMenu(int containerId, Inventory playerInv,
                      SimpleContainer traderInv,
                      List<TraderManager.TradeEntry> trades) {
        super(MenuType.GENERIC_9x6, containerId);
        this.traderInv = traderInv;
        this.trades    = trades;

        // ── Slots marchand (lignes 1-6) ──────────────────────────────────────
        for (int row = 0; row < TRADER_ROWS; row++) {
            for (int col = 0; col < TRADER_COLS; col++) {
                final int index = col + row * TRADER_COLS;
                addSlot(new Slot(traderInv, index, 8 + col * 18, 18 + row * 18) {
                    @Override public boolean mayPickup(Player p)     { return false; }
                    @Override public boolean mayPlace(ItemStack s)   { return false; }
                });
            }
        }

        // ── Inventaire joueur (3 lignes) ─────────────────────────────────────
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9,
                    8 + col * 18, 139 + row * 18));
            }
        }

        // ── Barre d'action ───────────────────────────────────────────────────
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 197));
        }
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        // Bloc de slots du marchand : traiter l'achat, pas de déplacement d'item
        if (slotId >= 0 && slotId < TRADER_SIZE) {
            ItemStack item = traderInv.getItem(slotId);
            if (!item.isEmpty() && player instanceof ServerPlayer serverPlayer) {
                // Chaque slot correspond à un trade dans la liste (dans l'ordre)
                if (slotId < trades.size()) {
                    TraderManager.processBuy(serverPlayer, trades.get(slotId).id());
                }
            }
            return; // empêche tout mouvement d'item
        }
        super.clicked(slotId, button, clickType, player);
    }

    /** Désactive le shift-clic (empêche de transférer les items du marchand). */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) { return true; }
}
