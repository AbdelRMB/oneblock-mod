package com.oneblock.mod.event;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.challenge.ChallengeManager;
import com.oneblock.mod.data.IslandExtensionManager;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.data.PlayerDataManager.PlayerOneBlockData;
import com.oneblock.mod.trader.TraderManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère :
 *  - Le comptage des blocs posés (progression d'extension).
 *  - La barre boss d'extension (2e barre en haut de l'écran).
 *  - Le cycle journalier du villageois marchand.
 *  - La détection des achats de mobs auprès du marchand.
 */
@Mod.EventBusSubscriber(modid = OneBlockMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class IslandExpansionHandler {

    /** Distance max depuis le centre de l'île pour compter un bloc posé. */
    private static final int ISLAND_RADIUS = 500;

    /** Boss bars d'extension par joueur. */
    private static final Map<UUID, ServerBossEvent> extensionBars = new ConcurrentHashMap<>();

    // ─── Tick ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerLevel overworld  = server.overworld();

        // Vérifie le cycle journalier pour chaque joueur connecté (toutes les 40 ticks = 2 s)
        if (overworld.getGameTime() % 40 != 0) return;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            TraderManager.tickForPlayer(player, overworld);
        }
    }

    // ─── Connexion ────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        UUID playerId = player.getUUID();

        // Charge les données d'extension
        IslandExtensionManager.loadFromDisk(playerId, server);
        TraderManager.onPlayerLogin(player, server);

        // Crée la barre boss d'extension
        updateExtensionBar(player);
    }

    // ─── Déconnexion ─────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        UUID playerId = player.getUUID();

        IslandExtensionManager.saveToDisk(playerId, server);
        TraderManager.onPlayerLogout(playerId, server);

        // Retire la barre boss
        ServerBossEvent bar = extensionBars.remove(playerId);
        if (bar != null) bar.removePlayer(player);

        IslandExtensionManager.clearCache(playerId);
    }

    // ─── Bloc posé ────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (event.getLevel().isClientSide()) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        UUID playerId = player.getUUID();
        PlayerOneBlockData data = PlayerDataManager.getOrCreate(playerId, server);

        // Compte uniquement les blocs posés à proximité de l'île
        BlockPos placed  = event.getPos();
        BlockPos islandCenter = data.blockPos;
        double dist = Math.sqrt(
            Math.pow(placed.getX() - islandCenter.getX(), 2) +
            Math.pow(placed.getZ() - islandCenter.getZ(), 2)
        );
        if (dist > ISLAND_RADIUS) return;

        int newLevel = IslandExtensionManager.addBlock(playerId, server);

        // Challenge : progression place
        ChallengeManager.onBlockPlaced(player);

        if (newLevel >= 0) {
            // Montée de niveau !
            String levelName = IslandExtensionManager.LEVEL_NAMES[newLevel]
                .replaceAll("§[0-9a-fk-orA-FK-OR]", "");
            player.sendSystemMessage(Component.literal(
                "§a§l↑ Extension débloquée : §r§e" + levelName
                + " §7(niveau §f" + newLevel + "§7)"
            ));
            OneBlockMod.LOGGER.info("[OneBlock] Joueur {} passe au niveau d'extension {}", playerId, newLevel);
        }

        // Met à jour la barre boss
        updateExtensionBar(player);
    }

    // ─── Barre boss d'extension ───────────────────────────────────────────────

    private static void updateExtensionBar(ServerPlayer player) {
        UUID playerId = player.getUUID();

        int level     = IslandExtensionManager.getLevel(playerId);
        int blocks    = IslandExtensionManager.getBlocksPlaced(playerId);
        int remaining = IslandExtensionManager.getBlocksUntilNextLevel(playerId);
        float progress = IslandExtensionManager.getProgress(playerId);

        String rawName = IslandExtensionManager.getLevelName(playerId)
            .replaceAll("§[0-9a-fk-orA-FK-OR]", "");

        String label;
        if (remaining > 0) {
            label = "Île · " + rawName + "  —  " + blocks + " / " + (blocks + remaining);
        } else {
            label = "Île · " + rawName + "  —  " + blocks + " blocs (MAX)";
        }

        BossEvent.BossBarColor color = getExtensionColor(level);

        ServerBossEvent bar = extensionBars.get(playerId);
        if (bar == null) {
            bar = new ServerBossEvent(
                player.getUUID(),
                Component.literal(label),
                color,
                BossEvent.BossBarOverlay.PROGRESS
            );
            bar.setProgress(progress);
            bar.addPlayer(player);
            extensionBars.put(playerId, bar);
        } else {
            bar.setName(Component.literal(label));
            bar.setColor(color);
            bar.setProgress(progress);
        }
    }

    private static BossEvent.BossBarColor getExtensionColor(int level) {
        return switch (level) {
            case 0          -> BossEvent.BossBarColor.WHITE;
            case 1, 2, 3    -> BossEvent.BossBarColor.GREEN;
            case 4          -> BossEvent.BossBarColor.GREEN;
            case 5          -> BossEvent.BossBarColor.BLUE;
            case 6          -> BossEvent.BossBarColor.BLUE;
            case 7, 8       -> BossEvent.BossBarColor.GREEN;
            case 9          -> BossEvent.BossBarColor.WHITE;
            case 10         -> BossEvent.BossBarColor.RED;
            default         -> BossEvent.BossBarColor.PURPLE;
        };
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
