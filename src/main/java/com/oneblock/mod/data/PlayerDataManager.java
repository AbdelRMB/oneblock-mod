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

    public static final int ISLAND_SPACING = 200;
    public static final int BLOCK_Y = 64;

    private static final Map<UUID, PlayerOneBlockData> playerData = new HashMap<>();
    private static int islandCounter = 0;

    public static PlayerOneBlockData getOrCreate(UUID playerId, MinecraftServer server) {
        if (!playerData.containsKey(playerId)) {
            PlayerOneBlockData data = loadFromDisk(playerId, server);
            if (data == null) {
                BlockPos islandPos = calculateNextIslandPos();
                data = new PlayerOneBlockData(playerId, islandPos, 0);
                islandCounter++;
                OneBlockMod.LOGGER.info("[OneBlock] Nouvelle île créée pour {} en {}", playerId, islandPos);
            }
            playerData.put(playerId, data);
        }
        return playerData.get(playerId);
    }

    private static BlockPos calculateNextIslandPos() {
        int x = islandCounter * ISLAND_SPACING;
        return new BlockPos(x, BLOCK_Y, 0);
    }

    public static PhaseChangeResult incrementBlocksBroken(UUID playerId, MinecraftServer server) {
        PlayerOneBlockData data = getOrCreate(playerId, server);
        OneBlockPhase oldPhase = data.getCurrentPhase();
        data.blocksBroken++;
        OneBlockPhase newPhase = data.getCurrentPhase();
        saveToDisk(data, server);
        boolean phaseChanged = oldPhase != newPhase;
        return new PhaseChangeResult(phaseChanged, oldPhase, newPhase, data.blocksBroken);
    }

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
            tag.putInt("IslandIndex", islandCounter);

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
            tag.getInt("IslandIndex").ifPresent(idx ->
                islandCounter = Math.max(islandCounter, idx)
            );

            BlockPos pos = new BlockPos(x, y, z);
            return new PlayerOneBlockData(playerId, pos, blocksBroken);

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
        islandCounter = 0;
    }

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
