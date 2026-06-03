package com.oneblock.mod.event;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.challenge.ChallengeManager;
import com.oneblock.mod.config.OneBlockConfig;
import com.oneblock.mod.data.CollectionTracker;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.data.PlayerDataManager.PlayerOneBlockData;
import com.oneblock.mod.data.PlayerDataManager.PhaseChangeResult;
import com.oneblock.mod.world.OneBlockPhase;
import com.oneblock.mod.world.OneBlockWorldGen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Path;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
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

    private static final Random RARE_RANDOM = new Random();

    /** 5 minutes = 6000 ticks. */
    private static final int AUTO_SAVE_INTERVAL = 6000;

    // ─── Tick ───────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        Runnable task;
        while ((task = nextTickTasks.poll()) != null) {
            task.run();
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long gameTime = server.overworld().getGameTime();

        // ── Auto-save toutes les 5 minutes ──────────────────────────────────
        if (gameTime % AUTO_SAVE_INTERVAL == 0) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                PlayerOneBlockData d = PlayerDataManager.getOrCreate(p.getUUID(), server);
                PlayerDataManager.saveToDisk(d, server);
                CollectionTracker.saveToDisk(p.getUUID(), server);
            }
        }

        // ── Challenges : vérification du jour pour chaque joueur ─────────────
        if (gameTime % 40 == 0) {
            long currentDay = gameTime / 24000L;
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                ChallengeManager.tick(p, currentDay);
            }
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

        // Charge la collection et les données challenges
        CollectionTracker.loadFromDisk(playerId, server);

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

        // Impulsion vers le haut (évite push/chute lors de la régénération)
        net.minecraft.world.phys.Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x, Math.max(motion.y, 0.2), motion.z);

        // ── Collection : enregistre le bloc cassé ────────────────────────────
        CollectionTracker.recordBlock(playerId, event.getState().getBlock(), server);

        // ── Incrémente la progression ─────────────────────────────────────────
        PhaseChangeResult result = PlayerDataManager.incrementBlocksBroken(playerId, server);
        if (result.changed) {
            OneBlockWorldGen.notifyPhaseChange(player, result.oldPhase, result.newPhase);
        }

        // ── Challenge : progression break ─────────────────────────────────────
        ChallengeManager.onBlockBroken(player);

        // ── Bloc rare (1% de chance) ──────────────────────────────────────────
        if (RARE_RANDOM.nextFloat() < 0.01f) {
            triggerRareEvent(player, level, brokenPos, data.getCurrentPhase());
        }

        // ── HUD + régénération ────────────────────────────────────────────────
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

    // ─── Respawn après mort ──────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        PlayerOneBlockData data = PlayerDataManager.getOrCreate(player.getUUID(), server);
        ServerLevel overworld   = server.overworld();

        // Téléporte au tick suivant (après que le jeu ait fini son propre traitement du respawn)
        server.execute(() -> player.teleportTo(
            overworld,
            data.blockPos.getX() + 0.5,
            data.blockPos.getY() + 1.5,   // légèrement au-dessus du bloc
            data.blockPos.getZ() + 0.5,
            Set.of(), player.getYRot(), player.getXRot(), true
        ));
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

        UUID playerId = player.getUUID();

        // Sauvegarde collection
        CollectionTracker.saveToDisk(playerId, server);
        CollectionTracker.clearCache(playerId);

        // Nettoie les challenges en mémoire
        ChallengeManager.clearPlayer(playerId);

        // Retire la boss bar
        ServerBossEvent bar = bossBars.remove(playerId);
        if (bar != null) bar.removePlayer(player);

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

    // ─── Bloc rare ───────────────────────────────────────────────────────────

    /**
     * Déclenche un événement "bloc rare" : particules + message + bonus d'items.
     * Probabilité : 1% à chaque break de OneBlock.
     */
    private static void triggerRareEvent(ServerPlayer player, ServerLevel level,
                                          BlockPos pos, OneBlockPhase phase) {
        // Particules dorées autour du bloc
        level.sendParticles(
            ParticleTypes.TOTEM_OF_UNDYING,
            pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 0.5,
            30, 0.3, 0.5, 0.3, 0.1
        );

        // Message
        player.sendSystemMessage(Component.literal(
            "§6§l✦ BLOC RARE ! §r§e+bonus selon ta phase §6§l✦"));

        // Bonus d'items selon la phase
        ItemStack bonus = getRareBonus(phase);
        player.getInventory().add(bonus.copy());
        player.sendSystemMessage(Component.literal(
            "§7Tu as reçu : §e" + bonus.getCount() + "x "
            + Component.translatable(bonus.getItem().getDescriptionId()).getString()));
    }

    private static ItemStack getRareBonus(OneBlockPhase phase) {
        int ordinal = phase.ordinal();
        if (ordinal <= 3)  return new ItemStack(Items.IRON_INGOT, 4);
        if (ordinal <= 7)  return new ItemStack(Items.GOLD_INGOT, 3);
        if (ordinal <= 11) return new ItemStack(Items.DIAMOND, 1);
        if (ordinal <= 15) return new ItemStack(Items.EMERALD, 2);
        if (ordinal <= 19) return new ItemStack(Items.DIAMOND, 2);
        return new ItemStack(Items.NETHERITE_SCRAP, 1);
    }

    // ─── Utilitaires ────────────────────────────────────────────────────────

    private static boolean hasExistingData(UUID playerId, MinecraftServer server) {
        Path file = server.getServerDirectory()
            .resolve("oneblock_data")
            .resolve(playerId.toString() + ".dat");
        return file.toFile().exists();
    }
}
