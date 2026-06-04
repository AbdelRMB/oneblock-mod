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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class PlayerDataManager {

    public static final int BLOCK_Y             = 64;
    /** Distance minimale entre deux îles (en blocs). */
    public static final int MIN_ISLAND_DISTANCE = 2000;
    /** Rayon du monde dans lequel chercher une position libre. */
    private static final int WORLD_RADIUS       = 50_000;
    /** Conserver pour compatibilité ascendante (données existantes sur l'axe X). */
    public static final int ISLAND_SPACING      = MIN_ISLAND_DISTANCE;

    private static final Random PLACEMENT_RANDOM = new Random();
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
                // Nouveau joueur → position aléatoire loin de toutes les îles existantes
                BlockPos islandPos = findRandomIslandPos(server);
                data = new PlayerOneBlockData(playerId, islandPos, 0);
                islandCounter++;
                saveGlobalCounter(server);
                OneBlockMod.LOGGER.info("[OneBlock] Île #{} créée pour {} → ({}, {})",
                    islandCounter - 1, playerId, islandPos.getX(), islandPos.getZ());
            }
            playerData.put(playerId, data);
        }
        return playerData.get(playerId);
    }

    // ─── Calcul de position ─────────────────────────────────────────────────

    /**
     * Cherche une position aléatoire dans le monde, suffisamment éloignée
     * de toutes les îles déjà existantes (cache + disque).
     * Retente jusqu'à 200 fois avant de tomber sur un fallback linéaire.
     */
    public static BlockPos findRandomIslandPos(MinecraftServer server) {
        List<BlockPos> existing = getAllIslandPositions(server);
        for (int attempt = 0; attempt < 200; attempt++) {
            int x = PLACEMENT_RANDOM.nextInt(WORLD_RADIUS * 2) - WORLD_RADIUS;
            int z = PLACEMENT_RANDOM.nextInt(WORLD_RADIUS * 2) - WORLD_RADIUS;
            BlockPos candidate = new BlockPos(x, BLOCK_Y, z);
            boolean valid = true;
            for (BlockPos p : existing) {
                double dx = candidate.getX() - p.getX();
                double dz = candidate.getZ() - p.getZ();
                if (dx * dx + dz * dz < (long) MIN_ISLAND_DISTANCE * MIN_ISLAND_DISTANCE) {
                    valid = false;
                    break;
                }
            }
            if (valid) {
                OneBlockMod.LOGGER.info("[OneBlock] Position libre trouvée en {} tentatives : ({}, {})",
                    attempt + 1, x, z);
                return candidate;
            }
        }
        // Fallback : placement linéaire classique
        OneBlockMod.LOGGER.warn("[OneBlock] Aucune position aléatoire libre trouvée, fallback linéaire.");
        return new BlockPos(islandCounter * ISLAND_SPACING, BLOCK_Y, 0);
    }

    /**
     * Renvoie toutes les positions d'îles connues (cache mémoire + scan disque).
     * Utilisé pour garantir qu'une nouvelle île ne chevauche pas une existante.
     */
    public static List<BlockPos> getAllIslandPositions(MinecraftServer server) {
        List<BlockPos> positions = new ArrayList<>();
        // D'abord le cache mémoire
        for (PlayerOneBlockData d : playerData.values()) {
            positions.add(d.blockPos);
        }
        // Puis les fichiers sur disque (joueurs non connectés)
        File saveDir = getSaveDir(server);
        if (saveDir.exists()) {
            File[] files = saveDir.listFiles((dir, name) ->
                name.endsWith(".dat") && !name.equals("global.dat"));
            if (files != null) {
                for (File f : files) {
                    try {
                        UUID id = UUID.fromString(f.getName().replace(".dat", ""));
                        if (playerData.containsKey(id)) continue; // déjà dans le cache
                        CompoundTag tag = NbtIo.readCompressed(f.toPath(), NbtAccounter.unlimitedHeap());
                        int x = tag.getInt("BlockX").orElse(Integer.MIN_VALUE);
                        int y = tag.getInt("BlockY").orElse(BLOCK_Y);
                        int z = tag.getInt("BlockZ").orElse(Integer.MIN_VALUE);
                        if (x != Integer.MIN_VALUE) positions.add(new BlockPos(x, y, z));
                    } catch (Exception ignored) {}
                }
            }
        }
        return positions;
    }

    /**
     * Déplace l'île d'un joueur vers une nouvelle position et met à jour les données.
     * La copie physique des blocs est effectuée par OneBlockWorldGen.
     */
    public static PlayerOneBlockData relocatePlayer(UUID playerId, BlockPos newPos, MinecraftServer server) {
        PlayerOneBlockData old = getOrCreate(playerId, server);
        PlayerOneBlockData updated = new PlayerOneBlockData(playerId, newPos, old.blocksBroken);
        playerData.put(playerId, updated);
        saveToDisk(updated, server);
        return updated;
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
