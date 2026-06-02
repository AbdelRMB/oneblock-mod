package com.oneblock.mod.data;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.world.OneBlockPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerDataManager {

    /**
     * Distance entre deux îles.
     * 2000 blocs = safe même à render distance 32 chunks (512 blocs max de visibilité).
     * Îles disposées sur l'axe X : île 0 → X=0, île 1 → X=2000, île 2 → X=4000...
     */
    public static final int ISLAND_SPACING = 2000;
    public static final int BLOCK_Y        = 64;

    private static final Map<UUID, PlayerOneBlockData> playerData = new HashMap<>();

    /**
     * Compteur global persisté dans global.dat (indépendant des fichiers joueurs).
     * -1 signifie "pas encore chargé depuis le disque".
     */
    private static int islandCounter = -1;

    // ─── Accès principal ────────────────────────────────────────────────────

    public static PlayerOneBlockData getOrCreate(UUID playerId, MinecraftServer server) {
        ensureCounterLoaded(server);

        if (!playerData.containsKey(playerId)) {
            PlayerOneBlockData data = loadFromDisk(playerId, server);
            if (data == null) {
                // Nouveau joueur → lui attribuer la prochaine île libre
                BlockPos islandPos = calculateNextIslandPos();
                data = new PlayerOneBlockData(playerId, islandPos, 0);
                islandCounter++;
                saveGlobalCounter(server);
                OneBlockMod.LOGGER.info("[OneBlock] Île #{} créée pour {} → X={}",
                    islandCounter - 1, playerId, islandPos.getX());
            }
            playerData.put(playerId, data);
        }
        return playerData.get(playerId);
    }

    // ─── Calcul de position ─────────────────────────────────────────────────

    private static BlockPos calculateNextIslandPos() {
        return new BlockPos(islandCounter * ISLAND_SPACING, BLOCK_Y, 0);
    }

    // ─── Compteur global (global.dat) ───────────────────────────────────────

    /**
     * Charge le compteur depuis global.dat une seule fois par session serveur.
     * Garantit qu'aucune île n'est assignée deux fois, même après redémarrage.
     */
    private static void ensureCounterLoaded(MinecraftServer server) {
        if (islandCounter >= 0) return;
        try {
            Path globalFile = getSaveDir(server).toPath().resolve("global.dat");
            if (globalFile.toFile().exists()) {
                CompoundTag tag = NbtIo.readCompressed(globalFile, NbtAccounter.unlimitedHeap());
                islandCounter = tag.getInt("IslandCounter").orElse(0);
                OneBlockMod.LOGGER.info("[OneBlock] Compteur global chargé : {} île(s) existante(s)", islandCounter);
            } else {
                islandCounter = 0;
                OneBlockMod.LOGGER.info("[OneBlock] Nouveau serveur — compteur initialisé à 0");
            }
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur lecture global.dat : {}", e.getMessage());
            islandCounter = 0;
        }
    }

    private static void saveGlobalCounter(MinecraftServer server) {
        try {
            File saveDir = getSaveDir(server);
            saveDir.mkdirs();
            Path globalFile = saveDir.toPath().resolve("global.dat");
            CompoundTag tag = new CompoundTag();
            tag.putInt("IslandCounter", islandCounter);
            NbtIo.writeCompressed(tag, globalFile);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde global.dat : {}", e.getMessage());
        }
    }

    // ─── Progression ────────────────────────────────────────────────────────

    public static PhaseChangeResult incrementBlocksBroken(UUID playerId, MinecraftServer server) {
        PlayerOneBlockData data = getOrCreate(playerId, server);
        OneBlockPhase oldPhase = data.getCurrentPhase();
        data.blocksBroken++;
        OneBlockPhase newPhase = data.getCurrentPhase();
        saveToDisk(data, server);
        boolean phaseChanged = oldPhase != newPhase;
        return new PhaseChangeResult(phaseChanged, oldPhase, newPhase, data.blocksBroken);
    }

    // ─── Persistence joueur ─────────────────────────────────────────────────

    public static void saveToDisk(PlayerOneBlockData data, MinecraftServer server) {
        try {
            File saveDir = getSaveDir(server);
            saveDir.mkdirs();
            Path filePath = saveDir.toPath().resolve(data.playerId.toString() + ".dat");

            CompoundTag tag = new CompoundTag();
            tag.putInt("BlocksBroken", data.blocksBroken);
            tag.putInt("BlockX", data.blockPos.getX());
            tag.putInt("BlockY", data.blockPos.getY());
            tag.putInt("BlockZ", data.blockPos.getZ());

            NbtIo.writeCompressed(tag, filePath);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde {}: {}", data.playerId, e.getMessage());
        }
    }

    private static PlayerOneBlockData loadFromDisk(UUID playerId, MinecraftServer server) {
        try {
            Path filePath = getSaveDir(server).toPath().resolve(playerId.toString() + ".dat");
            if (!filePath.toFile().exists()) return null;

            CompoundTag tag = NbtIo.readCompressed(filePath, NbtAccounter.unlimitedHeap());
            int blocksBroken = tag.getInt("BlocksBroken").orElse(0);
            int x = tag.getInt("BlockX").orElse(0);
            int y = tag.getInt("BlockY").orElse(BLOCK_Y);
            int z = tag.getInt("BlockZ").orElse(0);

            return new PlayerOneBlockData(playerId, new BlockPos(x, y, z), blocksBroken);

        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement {}: {}", playerId, e.getMessage());
            return null;
        }
    }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }

    public static void clearCache() {
        playerData.clear();
        islandCounter = -1;  // force le rechargement depuis global.dat à la prochaine utilisation
    }

    // ─── Classes internes ───────────────────────────────────────────────────

    public static class PlayerOneBlockData {
        public final UUID playerId;
        public final BlockPos blockPos;
        public int blocksBroken;

        public PlayerOneBlockData(UUID playerId, BlockPos blockPos, int blocksBroken) {
            this.playerId = playerId;
            this.blockPos = blockPos;
            this.blocksBroken = blocksBroken;
        }

        public OneBlockPhase getCurrentPhase() {
            return OneBlockPhase.fromCount(blocksBroken);
        }

        public int getBlocksUntilNextPhase() {
            OneBlockPhase current = getCurrentPhase();
            if (current.isLastPhase()) return -1;
            OneBlockPhase[] phases = OneBlockPhase.values();
            int idx = current.ordinal();
            if (idx + 1 < phases.length) {
                return phases[idx + 1].startCount - blocksBroken;
            }
            return -1;
        }
    }

    public static class PhaseChangeResult {
        public final boolean changed;
        public final OneBlockPhase oldPhase;
        public final OneBlockPhase newPhase;
        public final int totalBroken;

        public PhaseChangeResult(boolean changed, OneBlockPhase oldPhase, OneBlockPhase newPhase, int totalBroken) {
            this.changed = changed;
            this.oldPhase = oldPhase;
            this.newPhase = newPhase;
            this.totalBroken = totalBroken;
        }
    }
}
