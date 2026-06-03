package com.oneblock.mod.world;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.data.PlayerDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Random;
import java.util.Set;

public class OneBlockWorldGen {

    private static final Random RANDOM = new Random();

    public static void initializePlayerIsland(ServerPlayer player, PlayerDataManager.PlayerOneBlockData data) {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = data.blockPos;

        level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
        level.setBlock(pos.below(1), Blocks.BEDROCK.defaultBlockState(), 3);

        player.teleportTo(level,
            pos.getX() + 0.5,
            pos.getY() + 1.1,
            pos.getZ() + 0.5,
            Set.of(),
            player.getYRot(),
            player.getXRot(),
            true
        );

        OneBlockMod.LOGGER.info("[OneBlock] Île initialisée pour {} en {}", player.getName().getString(), pos);
    }

    public static void regenerateBlock(ServerLevel level, BlockPos pos,
                                        PlayerDataManager.PlayerOneBlockData data) {
        OneBlockPhase phase = data.getCurrentPhase();
        Block newBlock = phase.getRandomBlock(RANDOM);
        level.setBlock(pos, newBlock.defaultBlockState(), 3);
    }

    public static boolean isOneBlock(BlockPos pos, PlayerDataManager.PlayerOneBlockData data) {
        return pos.equals(data.blockPos);
    }

    public static void createStarterPlatform(ServerLevel level, BlockPos centerPos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos platePos = centerPos.below(1).offset(dx, 0, dz);
                if (dx == 0 && dz == 0) continue;
                if (level.isEmptyBlock(platePos)) {
                    level.setBlock(platePos, Blocks.GLASS.defaultBlockState(), 3);
                }
            }
        }
    }

    /**
     * Affiche un grand titre plein écran quand la phase change.
     * Titre    : nom de la nouvelle phase (coloré)
     * Sous-titre : description courte
     */
    public static void notifyPhaseChange(ServerPlayer player, OneBlockPhase oldPhase, OneBlockPhase newPhase) {
        String rawName = newPhase.displayName.replaceAll("§[0-9a-fk-orA-FK-OR]", "");

        // Timing : 10 ticks fade-in, 80 ticks visible, 20 ticks fade-out
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§6§l✦ " + newPhase.displayName + " §6§l✦")
        ));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§7Nouvelle phase débloquée !")
        ));

        // Message dans le chat aussi (reste consultable)
        player.sendSystemMessage(Component.literal(
            "§6§l✦ §eNouvelle phase : §r" + newPhase.displayName
            + " §8(était : " + oldPhase.displayName + "§8)"
        ));
    }

    public static void sendPlayerStats(ServerPlayer player, PlayerDataManager.PlayerOneBlockData data) {
        OneBlockPhase phase = data.getCurrentPhase();
        int untilNext = data.getBlocksUntilNextPhase();

        player.sendSystemMessage(Component.literal("§b§l── OneBlock Stats ──"));
        player.sendSystemMessage(Component.literal("§7Phase actuelle : " + phase.displayName));
        player.sendSystemMessage(Component.literal("§7Blocs cassés : §f" + data.blocksBroken));

        if (untilNext > 0) {
            player.sendSystemMessage(Component.literal("§7Prochaine phase dans : §f" + untilNext + " blocs"));
        } else {
            player.sendSystemMessage(Component.literal("§a§lPhase finale atteinte !"));
        }
    }
}
