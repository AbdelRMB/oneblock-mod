package com.oneblock.mod.economy;

import com.oneblock.mod.OneBlockMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Marché aux enchères entre joueurs.
 * Les enchères sont stockées en mémoire uniquement (perdues au redémarrage).
 *
 * /auction sell <prix>   — met l'item en main sur le marché
 * /auction list          — liste les enchères actives
 * /auction buy <id>      — achète l'item (en OneCoins)
 * /auction cancel <id>   — annule sa propre enchère
 */
public class AuctionManager {

    private static final AtomicInteger nextId = new AtomicInteger(1);

    public record AuctionEntry(
        int id,
        UUID sellerUUID,
        String sellerName,
        ItemStack item,
        int price,           // en OneCoins
        long createdTick     // tick serveur à la création
    ) {}

    private static final Map<Integer, AuctionEntry> auctions = new ConcurrentHashMap<>();

    // ─── Vendre ──────────────────────────────────────────────────────────────

    public static void sell(ServerPlayer player, int price, MinecraftServer server) {
        ItemStack hand = player.getMainHandItem();
        if (hand.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cTu ne tiens rien dans la main."));
            return;
        }
        if (price < 1) {
            player.sendSystemMessage(Component.literal("§cLe prix minimum est 1 §6⬡§c."));
            return;
        }

        ItemStack toSell = hand.copy();
        hand.setCount(0); // retire l'item de l'inventaire

        int id = nextId.getAndIncrement();
        long tick = server.overworld().getGameTime();
        AuctionEntry entry = new AuctionEntry(id, player.getUUID(),
            player.getName().getString(), toSell, price, tick);
        auctions.put(id, entry);

        // Annonce à tous les joueurs connectés
        String itemName = Component.translatable(toSell.getItem().getDescriptionId()).getString();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(
                "§6[Enchères] §f" + player.getName().getString()
                + " §7vend §e" + toSell.getCount() + "x " + itemName
                + " §7pour §e" + price + " §6⬡  §8(/auction buy " + id + ")"));
        }
    }

    // ─── Liste ───────────────────────────────────────────────────────────────

    public static void list(ServerPlayer player) {
        if (auctions.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7Aucune enchère active."));
            return;
        }
        player.sendSystemMessage(Component.literal("§6━━━━━ §eMarché aux enchères §6━━━━━"));
        for (AuctionEntry e : auctions.values()) {
            String itemName = Component.translatable(e.item().getItem().getDescriptionId()).getString();
            player.sendSystemMessage(Component.literal(
                "§8#" + e.id() + " §f" + e.sellerName()
                + " §8→ §e" + e.item().getCount() + "x " + itemName
                + " §8| §e" + e.price() + " §6⬡  §8(/auction buy " + e.id() + ")"));
        }
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    // ─── Acheter ─────────────────────────────────────────────────────────────

    public static void buy(ServerPlayer player, int id, MinecraftServer server) {
        AuctionEntry entry = auctions.get(id);
        if (entry == null) {
            player.sendSystemMessage(Component.literal("§cEnchère #" + id + " introuvable."));
            return;
        }
        if (entry.sellerUUID().equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§cTu ne peux pas acheter ta propre enchère."));
            return;
        }
        if (!CoinManager.spendCoins(player.getUUID(), entry.price(), server)) {
            player.sendSystemMessage(Component.literal(
                "§cPas assez de OneCoins (§f" + entry.price() + " §6⬡ §crequis)."));
            return;
        }

        // Donne les coins au vendeur
        CoinManager.addCoins(entry.sellerUUID(), entry.price(), server);
        auctions.remove(id);

        // Donne l'item à l'acheteur
        player.getInventory().add(entry.item().copy());

        String itemName = Component.translatable(entry.item().getItem().getDescriptionId()).getString();
        player.sendSystemMessage(Component.literal(
            "§a✓ Acheté : §f" + entry.item().getCount() + "x " + itemName
            + " §7pour §e" + entry.price() + " §6⬡"));

        // Notifie le vendeur s'il est en ligne
        ServerPlayer seller = server.getPlayerList().getPlayer(entry.sellerUUID());
        if (seller != null) {
            seller.sendSystemMessage(Component.literal(
                "§6[Enchères] §f" + player.getName().getString()
                + " §7a acheté ton §e" + itemName
                + " §7pour §e" + entry.price() + " §6⬡"));
        }
    }

    // ─── Annuler ─────────────────────────────────────────────────────────────

    public static void cancel(ServerPlayer player, int id) {
        AuctionEntry entry = auctions.get(id);
        if (entry == null) {
            player.sendSystemMessage(Component.literal("§cEnchère #" + id + " introuvable."));
            return;
        }
        if (!entry.sellerUUID().equals(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§cCe n'est pas ton enchère."));
            return;
        }
        auctions.remove(id);
        player.getInventory().add(entry.item().copy());
        player.sendSystemMessage(Component.literal("§aEnchère annulée. Item rendu dans ton inventaire."));
    }
}
