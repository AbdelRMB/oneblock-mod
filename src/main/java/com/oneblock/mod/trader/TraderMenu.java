package com.oneblock.mod.trader;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Container du marchand — s'affiche comme un grand coffre (6 lignes).
 *
 * Cliquer sur un slot d'offre déclenche l'achat via Slot.onTake().
 * L'item est immédiatement remis en place après le clic (pas de déplacement réel).
 */
public class TraderMenu extends AbstractContainerMenu {

    public static final int TRADER_SIZE = 54;  // 6 lignes × 9
    public static final int TRADER_ROWS = 6;
    public static final int TRADER_COLS = 9;

    private final SimpleContainer traderInv;
    private final List<TraderManager.TradeEntry> trades;

    public TraderMenu(int containerId, Inventory playerInv,
                      SimpleContainer traderInv,
                      List<TraderManager.TradeEntry> trades) {
        super(MenuType.GENERIC_9x6, containerId);
        this.traderInv = traderInv;
        this.trades    = trades;

        // ── Slots marchand ───────────────────────────────────────────────────
        for (int row = 0; row < TRADER_ROWS; row++) {
            for (int col = 0; col < TRADER_COLS; col++) {
                final int index = col + row * TRADER_COLS;
                addSlot(new Slot(traderInv, index, 8 + col * 18, 18 + row * 18) {

                    @Override
                    public boolean mayPlace(ItemStack s) { return false; }

                    @Override
                    public boolean mayPickup(Player p) {
                        // Autoriser le clic uniquement sur les vrais slots de trade
                        return index < trades.size();
                    }

                    @Override
                    public void onTake(Player player, ItemStack stack) {
                        // Traite l'achat côté serveur
                        if (player instanceof ServerPlayer sp) {
                            TraderManager.processBuy(sp, trades.get(index).id());
                        }
                        // Remet l'item en place (le joueur ne garde rien)
                        traderInv.setItem(index,
                            TraderManager.buildDisplayItemPublic(trades.get(index)));
                    }
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
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // désactive le shift-clic
    }

    @Override
    public boolean stillValid(Player player) { return true; }
}
