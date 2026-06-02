package com.oneblock.mod.data;

import com.oneblock.mod.OneBlockMod;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère la progression d'extension d'île par joueur.
 *
 * Niveaux (cumulatifs) :
 *   0 →    0 blocs posés   Île vide
 *   1 →   50               Ferme naissante   (vaches, moutons, cochons)
 *   2 →  150               Ferme rurale      (poulets, lapins)
 *   3 →  300               Territoire        (chevaux, ânes)
 *   4 →  500               Village           (chats, renards)
 *   5 →  800               Toundra           (ours polaires, loups)
 *   6 → 1200               Archipel          (tortues, axolotls)
 *   7 → 1800               Jungle            (pandas, perroquets, ocelots)
 *   8 → 2500               Marécage          (grenouilles, abeilles)
 *   9 → 3500               Domaine           (lamas, chèvres)
 *  10 → 5000               Nether            (striders, piglins pacifiques)
 *  11 → 7000               Mythique          (endermans neutres)
 */
public class IslandExtensionManager {

    public static final int[] THRESHOLDS = {
        0, 50, 150, 300, 500, 800, 1200, 1800, 2500, 3500, 5000, 7000
    };

    public static final String[] LEVEL_NAMES = {
        "§7Île vide",
        "§aFerme naissante",
        "§aFerme rurale",
        "§2Territoire établi",
        "§2Village émergent",
        "§bToundra arctique",
        "§9Archipel marin",
        "§2Forêt tropicale",
        "§2Marécage profond",
        "§8Domaine hanté",
        "§cRoyaume Nether",
        "§5Île Mythique"
    };

    private static final Map<UUID, Integer> blocksPlaced = new ConcurrentHashMap<>();

    // ─── Lecture ─────────────────────────────────────────────────────────────

    public static int getBlocksPlaced(UUID id) {
        return blocksPlaced.getOrDefault(id, 0);
    }

    public static int getLevel(UUID id) {
        return levelFor(getBlocksPlaced(id));
    }

    public static int levelFor(int blocks) {
        int level = 0;
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (blocks >= THRESHOLDS[i]) level = i;
        }
        return level;
    }

    public static String getLevelName(UUID id) {
        return LEVEL_NAMES[getLevel(id)];
    }

    /** Blocs restants avant le prochain niveau. -1 si niveau max. */
    public static int getBlocksUntilNextLevel(UUID id) {
        int blocks = getBlocksPlaced(id);
        int level  = levelFor(blocks);
        if (level >= THRESHOLDS.length - 1) return -1;
        return THRESHOLDS[level + 1] - blocks;
    }

    /** Progression dans le niveau actuel (0.0 → 1.0). */
    public static float getProgress(UUID id) {
        int blocks = getBlocksPlaced(id);
        int level  = levelFor(blocks);
        if (level >= THRESHOLDS.length - 1) return 1f;
        int start    = THRESHOLDS[level];
        int end      = THRESHOLDS[level + 1];
        return (float)(blocks - start) / (end - start);
    }

    // ─── Écriture ─────────────────────────────────────────────────────────────

    /**
     * Incrémente le compteur de blocs posés.
     * @return nouveau niveau si montée de niveau, -1 sinon.
     */
    public static int addBlock(UUID id, MinecraftServer server) {
        int prev      = blocksPlaced.getOrDefault(id, 0);
        int prevLevel = levelFor(prev);
        int newVal    = prev + 1;
        blocksPlaced.put(id, newVal);
        int newLevel = levelFor(newVal);
        saveToDisk(id, server);
        return newLevel != prevLevel ? newLevel : -1;
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    public static void saveToDisk(UUID id, MinecraftServer server) {
        try {
            File dir = getSaveDir(server);
            dir.mkdirs();
            Path path = dir.toPath().resolve(id + "_ext.dat");
            CompoundTag tag = new CompoundTag();
            tag.putInt("BlocksPlaced", blocksPlaced.getOrDefault(id, 0));
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde extension {}: {}", id, e.getMessage());
        }
    }

    public static void loadFromDisk(UUID id, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(id + "_ext.dat");
            if (!path.toFile().exists()) {
                blocksPlaced.put(id, 0);
                return;
            }
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            blocksPlaced.put(id, tag.getInt("BlocksPlaced").orElse(0));
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement extension {}: {}", id, e.getMessage());
            blocksPlaced.put(id, 0);
        }
    }

    public static void clearCache(UUID id) {
        blocksPlaced.remove(id);
    }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }
}
