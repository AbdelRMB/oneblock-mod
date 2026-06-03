package com.oneblock.mod.boss;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.achievement.AchievementManager;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.economy.CoinManager;
import com.oneblock.mod.world.OneBlockPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère les boss de phase.
 *
 * Quand le joueur franchit une frontière de phase MAJEURE (Plains → Underground, etc.),
 * un boss apparaît sur son île. La progression est bloquée tant que le boss est vivant.
 * Si le boss tue le joueur → régression au début de la dernière sous-phase.
 * Si le joueur tue le boss → la phase s'ouvre + récompense.
 */
public class BossManager {

    /** Mob spawné pour chaque transition de phase majeure (index = phase d'origine). */
    private static final String[] BOSS_MOBS = {
        "minecraft:zombie",           // Plains → Underground
        "minecraft:skeleton",         // Underground → Winter
        "minecraft:stray",            // Winter → Ocean
        "minecraft:elder_guardian",   // Ocean → Jungle
        "minecraft:ravager",          // Jungle → Swamp
        "minecraft:evoker",           // Swamp → Dungeon
        "minecraft:vindicator",       // Dungeon → Desert
        "minecraft:husk",             // Desert → Nether
        "minecraft:wither_skeleton",  // Nether → Plenty
        "minecraft:wither",           // Plenty → End
    };

    private static final String[] BOSS_NAMES = {
        "§cRoi Zombie",
        "§7Archer des Ombres",
        "§bSorcière des Glaces",
        "§9Gardien des Profondeurs",
        "§2Ravageur de la Jungle",
        "§2Sorcière des Marais",
        "§8Vindicateur Maudit",
        "§eRôdeur du Désert",
        "§cSquelette du Nether",
        "§5Le Destructeur",
    };

    /** playerUUID → bossEntityUUID */
    private static final Map<UUID, UUID>            activeBosses   = new ConcurrentHashMap<>();
    /** playerUUID → blocksBroken à restaurer si le boss tue le joueur */
    private static final Map<UUID, Integer>         revertPoints   = new ConcurrentHashMap<>();
    /** playerUUID → phase cible à débloquer quand le boss meurt */
    private static final Map<UUID, OneBlockPhase>   targetPhases   = new ConcurrentHashMap<>();
    /** playerUUID → boss bar */
    private static final Map<UUID, ServerBossEvent> bossBars       = new ConcurrentHashMap<>();
    /** bossEntityUUID → playerUUID (lookup inverse) */
    private static final Map<UUID, UUID>            bossToPlayer   = new ConcurrentHashMap<>();

    // ─── API publique ─────────────────────────────────────────────────────────

    public static boolean hasBoss(UUID playerId) {
        return activeBosses.containsKey(playerId);
    }

    /** Retourne true si l'entité bossId est bien le boss du joueur playerId. */
    public static boolean isBossOf(UUID bossId, UUID playerId) {
        UUID expected = activeBosses.get(playerId);
        return expected != null && expected.equals(bossId);
    }

    /**
     * Appelé dans PlayerEventHandler quand un changement de phase MAJEURE est détecté.
     * Spawn le boss et bloque la progression.
     * @return true si un boss a été spawné.
     */
    public static boolean onMajorPhaseTransition(ServerPlayer player, ServerLevel level,
                                                  OneBlockPhase oldPhase, OneBlockPhase newPhase) {
        if (hasBoss(player.getUUID())) return false;

        int majorIdx = oldPhase.getMajorPhaseIndex();
        if (majorIdx < 0 || majorIdx >= BOSS_MOBS.length) return false;

        // Point de régression = début de la dernière sous-phase de la phase précédente
        int revertPoint = oldPhase.getMajorPhaseStart().startCount;

        spawnBoss(player, level, majorIdx, newPhase, revertPoint);
        return true;
    }

    /**
     * Appelé quand une entité meurt (LivingDeathEvent).
     * Vérifie si c'était le boss d'un joueur.
     */
    public static void onEntityDeath(LivingEntity entity, ServerLevel level) {
        UUID bossId = entity.getUUID();
        UUID playerId = bossToPlayer.get(bossId);
        if (playerId == null) return;

        ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
        onBossKilled(playerId, player, level.getServer());
    }

    /**
     * Appelé quand le joueur meurt.
     * Si son boss est actif, il régresse.
     */
    public static void onPlayerDeath(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (!hasBoss(playerId)) return;

        int revert = revertPoints.getOrDefault(playerId, 0);
        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(playerId, (MinecraftServer) player.level().getServer());
        data.blocksBroken = revert;
        PlayerDataManager.saveToDisk(data, (MinecraftServer) player.level().getServer());

        // Supprime l'entité boss du monde
        UUID bossId = activeBosses.get(playerId);
        if (bossId != null && player.level() instanceof ServerLevel sl) {
            net.minecraft.world.entity.Entity boss = sl.getEntity(bossId);
            if (boss != null) boss.discard();
        }

        cleanBoss(playerId, player);

        player.sendSystemMessage(Component.literal(
            "§c☠ Le boss t'a eu ! §7Tu repasses au début de la phase précédente (§f"
            + revert + " §7blocs)."));
    }

    /** Met à jour la boss bar avec les PV actuels de l'entité. */
    public static void tickBossBar(ServerLevel level) {
        for (Map.Entry<UUID, UUID> entry : activeBosses.entrySet()) {
            UUID playerId = entry.getKey();
            UUID bossId   = entry.getValue();

            net.minecraft.world.entity.Entity bossEntity = level.getEntity(bossId);
            ServerBossEvent bar = bossBars.get(playerId);

            if (bar == null) continue;

            if (bossEntity instanceof LivingEntity living) {
                float pct = living.getHealth() / living.getMaxHealth();
                bar.setProgress(Math.max(0f, Math.min(1f, pct)));
            } else {
                // Boss introuvable → il est peut-être mort via un autre moyen
                cleanBoss(playerId, level.getServer().getPlayerList().getPlayer(playerId));
            }
        }
    }

    // ─── Spawn ───────────────────────────────────────────────────────────────

    private static void spawnBoss(ServerPlayer player, ServerLevel level,
                                   int phaseIdx, OneBlockPhase targetPhase, int revertPoint) {
        String mobKey  = BOSS_MOBS[phaseIdx];
        String bossName = BOSS_NAMES[phaseIdx];

        // Santé et dégâts croissants par phase
        double health = 60 + phaseIdx * 30;
        double damage = 5  + phaseIdx * 2;

        Optional<EntityType<?>> typeOpt = EntityType.byString(mobKey);
        if (typeOpt.isEmpty()) {
            OneBlockMod.LOGGER.warn("[OneBlock] Boss introuvable : {}", mobKey);
            return;
        }

        net.minecraft.world.entity.Entity entity = typeOpt.get().create(level, EntitySpawnReason.NATURAL);
        if (!(entity instanceof LivingEntity boss)) return;

        // Position sur le point le plus haut de l'île
        BlockPos islandPos = PlayerDataManager.getOrCreate(player.getUUID(),
            (MinecraftServer) player.level().getServer()).blockPos;
        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            islandPos.getX(), islandPos.getZ());
        boss.setPos(islandPos.getX() + 0.5, topY + 0.5, islandPos.getZ() + 0.5);

        // Attributs boostés
        if (boss.getAttribute(Attributes.MAX_HEALTH) != null)
            boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        boss.setHealth((float) health);
        if (boss.getAttribute(Attributes.ATTACK_DAMAGE) != null)
            boss.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(damage);

        boss.setCustomName(Component.literal(bossName));
        boss.setCustomNameVisible(true);
        if (boss instanceof net.minecraft.world.entity.Mob mob) {
            mob.setPersistenceRequired();
        }

        level.addFreshEntity(boss);

        UUID bossUUID   = boss.getUUID();
        UUID playerUUID = player.getUUID();

        activeBosses.put(playerUUID, bossUUID);
        revertPoints.put(playerUUID, revertPoint);
        targetPhases.put(playerUUID, targetPhase);
        bossToPlayer.put(bossUUID, playerUUID);

        // Boss bar
        ServerBossEvent bar = new ServerBossEvent(
            playerUUID,
            Component.literal(bossName + " §8— §7" + (int)health + " PV"),
            BossEvent.BossBarColor.RED,
            BossEvent.BossBarOverlay.NOTCHED_10
        );
        bar.setProgress(1f);
        bar.addPlayer(player);
        bossBars.put(playerUUID, bar);

        // Titre plein écran
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 60, 15));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§c⚔ BOSS APPARU ⚔")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal(bossName + " §7— Tue-le pour avancer !")));

        player.sendSystemMessage(Component.literal(
            "§c§l⚔ " + bossName + " §r§7est apparu sur ton île !"));
        player.sendSystemMessage(Component.literal(
            "§7Santé : §c" + (int)health + " PV §8| §7Dégâts : §c" + (int)damage
            + " §7| Si tu meurs → §frégression au bloc §c" + revertPoint));
    }

    // ─── Boss tué ────────────────────────────────────────────────────────────

    private static void onBossKilled(UUID playerId, ServerPlayer player, MinecraftServer server) {
        OneBlockPhase target = targetPhases.getOrDefault(playerId, null);

        if (target != null && player != null) {
            // Débloque la phase
            PlayerDataManager.PlayerOneBlockData data =
                PlayerDataManager.getOrCreate(playerId, server);
            data.blocksBroken = target.startCount;
            PlayerDataManager.saveToDisk(data, server);

            // Récompense coins
            int reward = 50 + target.getMajorPhaseIndex() * 25;
            CoinManager.addCoins(playerId, reward, server);

            // Achievement boss
            AchievementManager.onBossKilled(player);

            // Notification
            player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 70, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("§a§l✦ BOSS VAINCU ✦")));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("§e+" + reward + " OneCoins §7| §aPhase débloquée !")));

            player.sendSystemMessage(Component.literal(
                "§a§l✦ Boss vaincu ! §r§7+" + reward + " OneCoins. Phase débloquée !"));
        }

        cleanBoss(playerId, player);
    }

    private static void cleanBoss(UUID playerId, ServerPlayer player) {
        UUID bossId = activeBosses.remove(playerId);
        if (bossId != null) bossToPlayer.remove(bossId);
        revertPoints.remove(playerId);
        targetPhases.remove(playerId);

        ServerBossEvent bar = bossBars.remove(playerId);
        if (bar != null && player != null) bar.removePlayer(player);
    }

    public static void cleanupOnLogout(UUID playerId, ServerPlayer player) {
        cleanBoss(playerId, player);
    }
}
