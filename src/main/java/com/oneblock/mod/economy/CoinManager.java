package com.oneblock.mod.economy;

import com.oneblock.mod.OneBlockMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monnaie OneBlock (OneCoins).
 *
 * Gains :
 *   +1  par bloc cassé
 *   +5  lors d'un bloc rare (1%)
 *   +10 par challenge complété
 *   +50+ sur boss tué (selon phase)
 *
 * Dépenses : /shop, marchand (certains items)
 */
public class CoinManager {

    private static final Map<UUID, Integer> coins = new ConcurrentHashMap<>();

    // ─── Accès ───────────────────────────────────────────────────────────────

    public static int getCoins(UUID id) {
        return coins.getOrDefault(id, 0);
    }

    public static boolean hasEnough(UUID id, int amount) {
        return getCoins(id) >= amount;
    }

    public static void addCoins(UUID id, int amount, MinecraftServer server) {
        coins.merge(id, amount, Integer::sum);
        saveToDisk(id, server);
    }

    public static boolean spendCoins(UUID id, int amount, MinecraftServer server) {
        if (!hasEnough(id, amount)) return false;
        coins.merge(id, -amount, Integer::sum);
        saveToDisk(id, server);
        return true;
    }

    // ─── Shop ────────────────────────────────────────────────────────────────

    public static void showShop(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("§6━━━━━━ §e✦ OneShop §e✦ §6━━━━━━"));
        player.sendSystemMessage(Component.literal(
            "§7Ton solde : §e" + getCoins(player.getUUID()) + " §6OneCoins"));
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendSystemMessage(Component.literal("§8/shop buy <id>"));
        player.sendSystemMessage(Component.literal("§7#1  §f16x Blé           §8→ §e10 §6⬡"));
        player.sendSystemMessage(Component.literal("§7#2  §f16x Carotte        §8→ §e12 §6⬡"));
        player.sendSystemMessage(Component.literal("§7#3  §f8x  Pomme de Terre §8→ §e12 §6⬡"));
        player.sendSystemMessage(Component.literal("§7#4  §f4x  Glace          §8→ §e15 §6⬡"));
        player.sendSystemMessage(Component.literal("§7#5  §f1x  Minerai de fer §8→ §e20 §6⬡"));
        player.sendSystemMessage(Component.literal("§7#6  §f1x  Minerai d'or   §8→ §e35 §6⬡"));
        player.sendSystemMessage(Component.literal("§7#7  §f1x  Diamant        §8→ §e100 §6⬡"));
        player.sendSystemMessage(Component.literal("§7#8  §f1x  Émeraude       §8→ §e80 §6⬡"));
        player.sendSystemMessage(Component.literal("§7#9  §f1x  Scrap Netherite§8→ §e500 §6⬡"));
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    public static void processBuy(ServerPlayer player, int id) {
        UUID playerId = player.getUUID();
        MinecraftServer server = (MinecraftServer) player.level().getServer();

        record ShopEntry(int cost, net.minecraft.world.item.Item item, int count) {}
        ShopEntry[] shop = {
            new ShopEntry(10,  net.minecraft.world.item.Items.WHEAT,           16),
            new ShopEntry(12,  net.minecraft.world.item.Items.CARROT,          16),
            new ShopEntry(12,  net.minecraft.world.item.Items.POTATO,           8),
            new ShopEntry(15,  net.minecraft.world.item.Items.ICE,              4),
            new ShopEntry(20,  net.minecraft.world.item.Items.IRON_ORE,         1),
            new ShopEntry(35,  net.minecraft.world.item.Items.GOLD_ORE,         1),
            new ShopEntry(100, net.minecraft.world.item.Items.DIAMOND,          1),
            new ShopEntry(80,  net.minecraft.world.item.Items.EMERALD,          1),
            new ShopEntry(500, net.minecraft.world.item.Items.NETHERITE_SCRAP,  1),
        };

        if (id < 1 || id > shop.length) {
            player.sendSystemMessage(Component.literal("§cArticle #" + id + " inexistant."));
            return;
        }
        ShopEntry entry = shop[id - 1];
        if (!spendCoins(playerId, entry.cost(), server)) {
            player.sendSystemMessage(Component.literal(
                "§cPas assez de OneCoins (§f" + entry.cost() + " §6⬡ §crequis, tu as §f"
                + getCoins(playerId) + " §6⬡§c)."));
            return;
        }
        player.getInventory().add(new net.minecraft.world.item.ItemStack(entry.item(), entry.count()));
        player.sendSystemMessage(Component.literal(
            "§a✓ Achat effectué ! §7-" + entry.cost() + " §6⬡ §7| Solde : §e"
            + getCoins(playerId) + " §6⬡"));
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    public static void saveToDisk(UUID id, MinecraftServer server) {
        try {
            File dir = getSaveDir(server); dir.mkdirs();
            CompoundTag tag = new CompoundTag();
            tag.putInt("Coins", coins.getOrDefault(id, 0));
            NbtIo.writeCompressed(tag, dir.toPath().resolve(id + "_coins.dat"));
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde coins {}: {}", id, e.getMessage());
        }
    }

    public static void loadFromDisk(UUID id, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(id + "_coins.dat");
            if (!path.toFile().exists()) { coins.put(id, 0); return; }
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            coins.put(id, tag.getInt("Coins").orElse(0));
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement coins {}: {}", id, e.getMessage());
            coins.put(id, 0);
        }
    }

    public static void clearCache(UUID id) { coins.remove(id); }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }
}
