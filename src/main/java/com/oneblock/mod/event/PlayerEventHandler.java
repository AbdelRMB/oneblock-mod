package com.oneblock.mod.event;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.config.OneBlockConfig;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.data.PlayerDataManager.PlayerOneBlockData;
import com.oneblock.mod.data.PlayerDataManager.PhaseChangeResult;
import com.oneblock.mod.world.OneBlockPhase;
import com.oneblock.mod.world.OneBlockWorldGen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Path;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Mod.EventBusSubscriber(modid = OneBlockMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerEventHandler {

    /** File d'attente pour régénérer les blocs au tick suivant. */
    private static final Queue<Runnable> nextTickTasks = new ConcurrentLinkedQueue<>();

    /** Boss bar par joueur — affiche la progression OneBlock en haut de l'écran. */
    private static final Map<UUID, ServerBossEvent> bossBars = new ConcurrentHashMap<>();

    // ─── Tick ───────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        Runnable task;
        while ((task = nextTickTasks.poll()) != null) {
            task.run();
        }
    }

    // ─── Connexion ──────────────────────────────────────────────────────────

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

                player.teleportTo(level,
                    data.blockPos.getX() + 0.5,
                    data.blockPos.getY() + 1.1,
                    data.blockPos.getZ() + 0.5,
                    Set.of(), player.getYRot(), player.getXRot(), true
                );

                player.sendSystemMessage(
                    Component.literal("§a§lBienvenue sur OneBlock ! §7Casse ton bloc pour progresser.")
                );
            } else {
                player.teleportTo(level,
                    data.blockPos.getX() + 0.5,
                    data.blockPos.getY() + 1.1,
                    data.blockPos.getZ() + 0.5,
                    Set.of(), player.getYRot(), player.getXRot(), true
                );
            }

            // Crée ou restaure la boss bar de progression
            updateBossBar(player, data);
        });

        OneBlockMod.LOGGER.info("[OneBlock] Joueur {} connecté (nouveau: {}, bloc: {})",
            player.getName().getString(), isNewPlayer, data.blockPos);
    }

    // ─── Casse de bloc ──────────────────────────────────────────────────────

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

        // Impulsion vers le haut : le joueur monte légèrement au moment du break
        // → quand le bloc régénère au tick suivant, le joueur est au-dessus
        //   et ne se retrouve plus à l'intérieur (évite le push et la chute).
        net.minecraft.world.phys.Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x, Math.max(motion.y, 0.2), motion.z);

        // Incrémente la progression
        PhaseChangeResult result = PlayerDataManager.incrementBlocksBroken(playerId, server);
        if (result.changed) {
            OneBlockWorldGen.notifyPhaseChange(player, result.oldPhase, result.newPhase);
        }

        // Régénère le bloc au tick suivant (vanilla gère le break et les drops normalement)
        PlayerOneBlockData freshData = PlayerDataManager.getOrCreate(playerId, server);
        updateBossBar(player, freshData);

        nextTickTasks.add(() -> {
            PlayerOneBlockData updatedData = PlayerDataManager.getOrCreate(playerId, server);
            OneBlockWorldGen.regenerateBlock(level, brokenPos, updatedData);
        });
    }

    // ─── Chute dans le vide ─────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (player.getY() >= -10) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;
        if (!OneBlockConfig.SERVER.voidTeleport.get()) return;

        PlayerOneBlockData data = PlayerDataManager.getOrCreate(player.getUUID(), server);

        player.teleportTo(server.overworld(),
            data.blockPos.getX() + 0.5,
            data.blockPos.getY() + 1.1,
            data.blockPos.getZ() + 0.5,
            Set.of(), player.getYRot(), player.getXRot(), true
        );

        player.sendSystemMessage(
            Component.literal("§c§lTu es tombé dans le vide ! §r§7Retour à ton bloc.")
        );
    }

    // ─── Déconnexion ────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        // Sauvegarde des données
        PlayerOneBlockData data = PlayerDataManager.getOrCreate(player.getUUID(), server);
        PlayerDataManager.saveToDisk(data, server);

        // Retire le joueur de sa boss bar et nettoie
        UUID playerId = player.getUUID();
        ServerBossEvent bar = bossBars.remove(playerId);
        if (bar != null) {
            bar.removePlayer(player);
        }

        OneBlockMod.LOGGER.info("[OneBlock] Données sauvegardées pour {}", player.getName().getString());
    }

    // ─── Boss bar ───────────────────────────────────────────────────────────

    /**
     * Crée (ou met à jour) la boss bar de progression du joueur.
     *
     * Affichage :
     *   [Plains 1.4  —  423 / 700]
     *   [████████████░░░░░░░░░░░░]
     */
    private static void updateBossBar(ServerPlayer player, PlayerOneBlockData data) {
        OneBlockPhase phase = data.getCurrentPhase();

        // Texte
        String cleanName = phase.displayName.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
        int blocksLeft = data.getBlocksUntilNextPhase();
        String label;
        if (blocksLeft > 0) {
            label = cleanName + "  —  " + data.blocksBroken + " / " + (data.blocksBroken + blocksLeft);
        } else {
            label = cleanName + "  —  " + data.blocksBroken + " (MAX)";
        }

        // Progression dans le sous-niveau
        float progress = phase.getProgress(data.blocksBroken);

        // Couleur selon la phase
        BossEvent.BossBarColor color = getBarColor(phase);

        UUID playerId = player.getUUID();
        ServerBossEvent bar = bossBars.get(playerId);

        if (bar == null) {
            // Première connexion : crée la bar et l'associe au joueur
            bar = new ServerBossEvent(
                playerId,
                Component.literal(label),
                color,
                BossEvent.BossBarOverlay.PROGRESS
            );
            bar.setProgress(progress);
            bar.addPlayer(player);
            bossBars.put(playerId, bar);
        } else {
            // Mise à jour
            bar.setName(Component.literal(label));
            bar.setColor(color);
            bar.setProgress(progress);
        }
    }

    /** Couleur de la boss bar selon la phase courante. */
    private static BossEvent.BossBarColor getBarColor(OneBlockPhase phase) {
        String d = phase.displayName;
        if (d.startsWith("§a")) return BossEvent.BossBarColor.GREEN;   // Plains
        if (d.startsWith("§7")) return BossEvent.BossBarColor.WHITE;   // Underground
        if (d.startsWith("§b")) return BossEvent.BossBarColor.BLUE;    // Winter
        if (d.startsWith("§9")) return BossEvent.BossBarColor.BLUE;    // Ocean
        if (d.startsWith("§2")) return BossEvent.BossBarColor.GREEN;   // Jungle / Swamp
        if (d.startsWith("§8")) return BossEvent.BossBarColor.WHITE;   // Dungeon
        if (d.startsWith("§e")) return BossEvent.BossBarColor.YELLOW;  // Desert
        if (d.startsWith("§c")) return BossEvent.BossBarColor.RED;     // Nether
        if (d.startsWith("§6")) return BossEvent.BossBarColor.YELLOW;  // Plenty
        if (d.startsWith("§5")) return BossEvent.BossBarColor.PURPLE;  // The End
        return BossEvent.BossBarColor.WHITE;
    }

    // ─── Utilitaires ────────────────────────────────────────────────────────

    private static boolean hasExistingData(UUID playerId, MinecraftServer server) {
        Path file = server.getServerDirectory()
            .resolve("oneblock_data")
            .resolve(playerId.toString() + ".dat");
        return file.toFile().exists();
    }
}
