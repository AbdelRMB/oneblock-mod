package com.oneblock.mod.event;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.config.OneBlockConfig;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.data.PlayerDataManager.PlayerOneBlockData;
import com.oneblock.mod.data.PlayerDataManager.PhaseChangeResult;
import com.oneblock.mod.world.OneBlockWorldGen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = OneBlockMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerEventHandler {

    // File d'attente pour régénérer les blocs au tick suivant
    private static final Queue<Runnable> nextTickTasks = new ConcurrentLinkedQueue<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        
        Runnable task;
        while ((task = nextTickTasks.poll()) != null) {
            task.run();
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        UUID playerId = player.getUUID();
        boolean isNewPlayer = !hasExistingData(playerId, server);
        PlayerOneBlockData data = PlayerDataManager.getOrCreate(playerId, server);

        server.execute(() -> {
            ServerLevel level = server.overworld();

            if (isNewPlayer) {
                level.setBlock(data.blockPos, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
                level.setBlock(data.blockPos.below(), Blocks.BEDROCK.defaultBlockState(), 3);

                if (OneBlockConfig.SERVER.starterPlatform.get()) {
                    OneBlockWorldGen.createStarterPlatform(level, data.blockPos);
                }

                OneBlockMod.LOGGER.info("[OneBlock] Bloc placé à {}", data.blockPos);

                player.teleportTo(level,
                    data.blockPos.getX() + 0.5,
                    data.blockPos.getY() + 1.1,
                    data.blockPos.getZ() + 0.5,
                    Set.of(),
                    player.getYRot(),
                    player.getXRot(),
                    true
                );

                player.sendSystemMessage(
                    Component.literal("§a§lBienvenue sur OneBlock ! §7Casse ton bloc pour progresser.")
                );
            } else {
                player.teleportTo(level,
                    data.blockPos.getX() + 0.5,
                    data.blockPos.getY() + 1.1,
                    data.blockPos.getZ() + 0.5,
                    Set.of(),
                    player.getYRot(),
                    player.getXRot(),
                    true
                );

                int blocksLeft = data.getBlocksUntilNextPhase();
                String nextInfo = blocksLeft > 0 ? " §7| §f" + blocksLeft + " §7blocs jusqu'au prochain niveau" : "";
                player.sendSystemMessage(
                    Component.literal("§7Re-bienvenue ! §f" + data.getCurrentPhase().displayName
                        + " §7| Blocs cassés : §f" + data.blocksBroken + nextInfo)
                );
            }
        });

        OneBlockMod.LOGGER.info("[OneBlock] Joueur {} connecté (nouveau: {}, bloc: {})",
            player.getName().getString(), isNewPlayer, data.blockPos);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (event.getLevel().isClientSide()) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        UUID playerId = player.getUUID();
        PlayerOneBlockData data = PlayerDataManager.getOrCreate(playerId, server);
        BlockPos brokenPos = event.getPos();

        if (!OneBlockWorldGen.isOneBlock(brokenPos, data)) return;

        ServerLevel level = (ServerLevel) event.getLevel();
        PhaseChangeResult result = PlayerDataManager.incrementBlocksBroken(playerId, server);

        if (result.changed) {
            OneBlockWorldGen.notifyPhaseChange(player, result.oldPhase, result.newPhase);
        }

        PlayerOneBlockData freshData = PlayerDataManager.getOrCreate(playerId, server);
        int blocksLeft = freshData.getBlocksUntilNextPhase();
        String nextInfo = blocksLeft > 0 ? " §7| §f" + blocksLeft + " §7avant niveau " + (freshData.getCurrentPhase().ordinal() + 1) : "";
        player.sendSystemMessage(
            Component.literal("§7" + freshData.getCurrentPhase().displayName
                + " §7| Blocs cassés : §f" + result.totalBroken + nextInfo)
        );

        // Planifie la régénération au tick suivant (après que Minecraft ait fini de casser le bloc)
        nextTickTasks.add(() -> {
            PlayerOneBlockData updatedData = PlayerDataManager.getOrCreate(playerId, server);
            OneBlockWorldGen.regenerateBlock(level, brokenPos, updatedData);
            OneBlockMod.LOGGER.info("[OneBlock] Bloc régénéré à {}", brokenPos);
        });
    }

    @SubscribeEvent
    public static void onPlayerFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.getY() >= -10) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;
        if (!OneBlockConfig.SERVER.voidTeleport.get()) return;

        PlayerOneBlockData data = PlayerDataManager.getOrCreate(player.getUUID(), server);
        ServerLevel level = server.overworld();

        player.teleportTo(level,
            data.blockPos.getX() + 0.5,
            data.blockPos.getY() + 1.1,
            data.blockPos.getZ() + 0.5,
            Set.of(),
            player.getYRot(),
            player.getXRot(),
            true
        );

        player.sendSystemMessage(
            Component.literal("§c§lTu es tombé dans le vide ! §r§7Retour à ton bloc.")
        );
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        PlayerOneBlockData data = PlayerDataManager.getOrCreate(player.getUUID(), server);
        PlayerDataManager.saveToDisk(data, server);

        OneBlockMod.LOGGER.info("[OneBlock] Données sauvegardées pour {}", player.getName().getString());
    }

    private static boolean hasExistingData(UUID playerId, MinecraftServer server) {
        Path file = server.getServerDirectory()
            .resolve("oneblock_data")
            .resolve(playerId.toString() + ".dat");
        return file.toFile().exists();
    }
}
