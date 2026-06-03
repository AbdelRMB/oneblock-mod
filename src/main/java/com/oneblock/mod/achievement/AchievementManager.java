package com.oneblock.mod.achievement;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.economy.CoinManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Succès OneBlock — affichés comme des advancements Minecraft dans le chat.
 *
 * Déclenchés par :
 *   - Blocs cassés : 100, 500, 1000, 2500, 5000, 10000
 *   - Phase majeure atteinte (Plains, Underground, ..., End)
 *   - Premier bloc rare
 *   - Premier boss tué
 *   - Prestige obtenu
 */
public class AchievementManager {

    public enum Achievement {
        // Blocs cassés
        BREAK_100     ("§aPremiers Pas",          "Casse 100 blocs OneBlock",            20),
        BREAK_500     ("§aBûcheron",               "Casse 500 blocs OneBlock",            50),
        BREAK_1000    ("§6Minéralier",             "Casse 1000 blocs OneBlock",           100),
        BREAK_2500    ("§6Chasseur de Ressources", "Casse 2500 blocs OneBlock",           150),
        BREAK_5000    ("§cLégende",                "Casse 5000 blocs OneBlock",           250),
        BREAK_10000   ("§5Maître du Bloc",         "Casse 10 000 blocs OneBlock",         500),
        // Phases majeures
        REACH_UNDERGROUND("§7Le Souterrain",     "Atteins la phase Underground",          30),
        REACH_WINTER  ("§bTundra",                "Atteins la phase Winter",               40),
        REACH_OCEAN   ("§9Marin d'Eau Douce",     "Atteins la phase Ocean",               50),
        REACH_JUNGLE  ("§2Explorateur",           "Atteins la phase Jungle",              60),
        REACH_SWAMP   ("§2Fils des Marais",       "Atteins la phase Swamp",               70),
        REACH_DUNGEON ("§8Seigneur des Cryptes",  "Atteins la phase Dungeon",             80),
        REACH_DESERT  ("§eSable et Sueur",        "Atteins la phase Desert",              90),
        REACH_NETHER  ("§cPorte de l'Enfer",      "Atteins la phase Nether",             100),
        REACH_PLENTY  ("§6Abondance",             "Atteins la phase Plenty",             120),
        REACH_END     ("§5L'Horizon Final",       "Atteins la phase The End",            200),
        // Événements
        FIRST_RARE    ("§6✦ Bloc Rare",           "Obtiens ton premier bloc rare",        25),
        FIRST_BOSS    ("§c⚔ Tueur de Boss",       "Tue ton premier boss de phase",        75),
        PRESTIGE_1    ("§5✦ Prestige I",          "Accomplis ton premier prestige",       300);

        public final String title;
        public final String description;
        public final int coinReward;

        Achievement(String title, String description, int coinReward) {
            this.title = title;
            this.description = description;
            this.coinReward = coinReward;
        }
    }

    private static final Map<UUID, Set<Achievement>> unlocked = new ConcurrentHashMap<>();

    // ─── Déclenchement ───────────────────────────────────────────────────────

    public static void checkBlocksBroken(ServerPlayer player, int total) {
        if (total >= 100)   unlock(player, Achievement.BREAK_100);
        if (total >= 500)   unlock(player, Achievement.BREAK_500);
        if (total >= 1000)  unlock(player, Achievement.BREAK_1000);
        if (total >= 2500)  unlock(player, Achievement.BREAK_2500);
        if (total >= 5000)  unlock(player, Achievement.BREAK_5000);
        if (total >= 10000) unlock(player, Achievement.BREAK_10000);
    }

    public static void checkPhaseReached(ServerPlayer player, int majorPhaseIndex) {
        Achievement[] phaseAch = {
            null, // Plains = départ
            Achievement.REACH_UNDERGROUND,
            Achievement.REACH_WINTER,
            Achievement.REACH_OCEAN,
            Achievement.REACH_JUNGLE,
            Achievement.REACH_SWAMP,
            Achievement.REACH_DUNGEON,
            Achievement.REACH_DESERT,
            Achievement.REACH_NETHER,
            Achievement.REACH_PLENTY,
            Achievement.REACH_END,
        };
        if (majorPhaseIndex >= 0 && majorPhaseIndex < phaseAch.length
                && phaseAch[majorPhaseIndex] != null) {
            unlock(player, phaseAch[majorPhaseIndex]);
        }
    }

    public static void onRareBlock(ServerPlayer player)  { unlock(player, Achievement.FIRST_RARE); }
    public static void onBossKilled(ServerPlayer player) { unlock(player, Achievement.FIRST_BOSS); }
    public static void onPrestige(ServerPlayer player)   { unlock(player, Achievement.PRESTIGE_1); }

    // ─── Unlock ──────────────────────────────────────────────────────────────

    private static void unlock(ServerPlayer player, Achievement ach) {
        UUID id = player.getUUID();
        Set<Achievement> set = unlocked.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet());
        if (!set.add(ach)) return; // déjà obtenu

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        saveToDisk(id, server);

        CoinManager.addCoins(id, ach.coinReward, server);

        // Affichage style advancement
        player.sendSystemMessage(Component.literal(
            "§6[§eSuccès §6✦§6] §f" + ach.title));
        player.sendSystemMessage(Component.literal(
            "  §7" + ach.description + " §8— §e+" + ach.coinReward + " §6⬡"));
    }

    // ─── Affichage ───────────────────────────────────────────────────────────

    public static void showAchievements(ServerPlayer player) {
        UUID id = player.getUUID();
        Set<Achievement> set = unlocked.getOrDefault(id, Set.of());
        int total = Achievement.values().length;
        int found = set.size();

        player.sendSystemMessage(Component.literal(
            "§6━━━━━ §eSuccès OneBlock §6━━━━━ §7(" + found + "/" + total + ")"));
        for (Achievement ach : Achievement.values()) {
            boolean has = set.contains(ach);
            player.sendSystemMessage(Component.literal(
                (has ? "§a✔ " : "§8✗ §7") + ach.title
                + " §8— §7" + ach.description
                + (has ? "" : "  §8(§e+" + ach.coinReward + " §6⬡§8)")));
        }
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    public static void saveToDisk(UUID id, MinecraftServer server) {
        try {
            File dir = getSaveDir(server); dir.mkdirs();
            CompoundTag tag = new CompoundTag();
            Set<Achievement> set = unlocked.getOrDefault(id, Set.of());
            for (Achievement ach : set) tag.putBoolean(ach.name(), true);
            NbtIo.writeCompressed(tag, dir.toPath().resolve(id + "_achievements.dat"));
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde achievements: {}", e.getMessage());
        }
    }

    public static void loadFromDisk(UUID id, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(id + "_achievements.dat");
            if (!path.toFile().exists()) return;
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            Set<Achievement> set = ConcurrentHashMap.newKeySet();
            for (Achievement ach : Achievement.values()) {
                if (tag.getBoolean(ach.name()).orElse(false)) set.add(ach);
            }
            unlocked.put(id, set);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement achievements: {}", e.getMessage());
        }
    }

    public static void clearCache(UUID id) { unlocked.remove(id); }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }
}
