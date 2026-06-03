package com.oneblock.mod.prestige;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.achievement.AchievementManager;
import com.oneblock.mod.data.PlayerDataManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Système de Prestige.
 *
 * Quand le joueur est en phase END_4 (dernière phase), il peut taper /prestige
 * pour recommencer à 0 avec un bonus permanent.
 *
 * Bonus par niveau de prestige :
 *   +20% chances de bloc rare par prestige (cumulable jusqu'à 5 fois)
 *   +5 OneCoins par bloc cassé par prestige
 *   Préfixe de couleur unique dans le titre
 */
public class PrestigeManager {

    private static final String[] PRESTIGE_PREFIXES = {
        "",           // 0 = pas de prestige
        "§e✦ ",       // 1 = or
        "§b✦✦ ",      // 2 = cyan
        "§d✦✦✦ ",     // 3 = violet
        "§c✦✦✦✦ ",   // 4 = rouge
        "§6✦✦✦✦✦ ",  // 5 = or foncé (max)
    };

    private static final Map<UUID, Integer> prestigeLevel = new ConcurrentHashMap<>();

    // ─── Accès ───────────────────────────────────────────────────────────────

    public static int getPrestige(UUID id) {
        return prestigeLevel.getOrDefault(id, 0);
    }

    public static String getPrestigePrefix(UUID id) {
        int lvl = Math.min(getPrestige(id), PRESTIGE_PREFIXES.length - 1);
        return PRESTIGE_PREFIXES[lvl];
    }

    /** Bonus de chance de bloc rare (0.0 → 0.10 max). */
    public static float getRareBonusChance(UUID id) {
        return Math.min(getPrestige(id) * 0.02f, 0.10f);
    }

    /** Bonus de OneCoins par bloc cassé. */
    public static int getCoinBonusPerBreak(UUID id) {
        return getPrestige(id) * 5;
    }

    // ─── Prestige ────────────────────────────────────────────────────────────

    public static void doPrestige(ServerPlayer player) {
        MinecraftServer server = (MinecraftServer) player.level().getServer();
        UUID id = player.getUUID();

        PlayerDataManager.PlayerOneBlockData data = PlayerDataManager.getOrCreate(id, server);

        // Vérifie que le joueur est bien en phase finale
        if (!data.getCurrentPhase().isLastPhase()) {
            player.sendSystemMessage(Component.literal(
                "§cTu dois atteindre la fin de la phase The End avant de prestiger !"));
            return;
        }

        int current = getPrestige(id);
        if (current >= 5) {
            player.sendSystemMessage(Component.literal("§cPrestige maximum (5) déjà atteint !"));
            return;
        }

        // Reset de la progression
        data.blocksBroken = 0;
        PlayerDataManager.saveToDisk(data, server);

        // Incrément prestige
        prestigeLevel.put(id, current + 1);
        saveToDisk(id, server);

        // Reset de l'île
        ServerLevel level = server.overworld();
        level.setBlock(data.blockPos, Blocks.GRASS_BLOCK.defaultBlockState(), 3);

        // Notification
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§6§l✦ PRESTIGE " + (current + 1) + " ✦")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§7Tu recommences avec un bonus permanent !")));

        String prefix = PRESTIGE_PREFIXES[Math.min(current + 1, PRESTIGE_PREFIXES.length - 1)];
        player.sendSystemMessage(Component.literal(
            prefix + "§6Prestige " + (current + 1) + " §7obtenu !"));
        player.sendSystemMessage(Component.literal(
            "§7Bonus : §e+" + (int)((getRareBonusChance(id)) * 100) + "% bloc rare"
            + " | §e+" + getCoinBonusPerBreak(id) + " §6⬡§7/bloc"));

        AchievementManager.onPrestige(player);

        OneBlockMod.LOGGER.info("[OneBlock] Prestige {} pour {}", current + 1, player.getName().getString());
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    public static void saveToDisk(UUID id, MinecraftServer server) {
        try {
            File dir = getSaveDir(server); dir.mkdirs();
            CompoundTag tag = new CompoundTag();
            tag.putInt("Prestige", getPrestige(id));
            NbtIo.writeCompressed(tag, dir.toPath().resolve(id + "_prestige.dat"));
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde prestige: {}", e.getMessage());
        }
    }

    public static void loadFromDisk(UUID id, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(id + "_prestige.dat");
            if (!path.toFile().exists()) { prestigeLevel.put(id, 0); return; }
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            prestigeLevel.put(id, tag.getInt("Prestige").orElse(0));
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement prestige: {}", e.getMessage());
            prestigeLevel.put(id, 0);
        }
    }

    public static void clearCache(UUID id) { prestigeLevel.remove(id); }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }
}
