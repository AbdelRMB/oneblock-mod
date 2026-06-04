package com.oneblock.mod.boss;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.achievement.AchievementManager;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.economy.CoinManager;
import com.oneblock.mod.world.OneBlockPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Système de boss de phase — arène dédiée.
 *
 * Déroulement :
 *  1. Transition de phase majeure détectée → téléportation dans l'arène 40×40 en bedrock
 *  2. Inventaire sauvegardé, équipement : full diamant + épée diamant + pommes d'or + flèches
 *  3. Boss spawné au centre de l'arène (puissance croissante par phase)
 *  4. Victoire → retour île owner, inventaire restauré, phase débloquée (helpers renvoyés chez eux)
 *  5. Défaite → retour île, inventaire restauré, boss en attente → /boss pour réessayer
 *
 * Fonctionnalités co-op :
 *  - /boss invite <joueur>  → invite un ami à aider
 *  - /boss accept           → l'invité accepte et entre dans l'arène
 *  - Si l'owner meurt ET qu'il reste des helpers vivants → il passe en mode Spectateur
 *    et observe un des helpers encore en vie. Le combat continue.
 *  - Si l'owner meurt sans helpers (ou tous les helpers sont morts) → défaite classique.
 *  - Les helpers ne reçoivent PAS la récompense de phase à la victoire.
 *  - Les helpers reçoivent le même équipement que l'owner pour le combat.
 */
public class BossManager {

    // ─── Constantes arène ────────────────────────────────────────────────────
    private static final int ARENA_Y       = 150;
    private static final int ARENA_HALF    = 20;
    private static final int WALL_HEIGHT   = 10;

    // ─── Mobs par phase majeure ───────────────────────────────────────────────
    private static final String[] BOSS_MOBS = {
        "minecraft:zombie",
        "minecraft:skeleton",
        "minecraft:stray",
        "minecraft:elder_guardian",
        "minecraft:ravager",
        "minecraft:evoker",
        "minecraft:vindicator",
        "minecraft:husk",
        "minecraft:wither_skeleton",
        "minecraft:wither",
    };
    private static final String[] BOSS_NAMES = {
        "§cRoi Zombie",         "§7Archer des Ombres",    "§bSorcière des Glaces",
        "§9Gardien des Fonds",  "§2Ravageur",             "§2Invocateur des Marais",
        "§8Vindicateur Maudit", "§eRôdeur du Désert",     "§cSquelette du Nether",
        "§5Le Destructeur",
    };

    // ─── État par joueur (owner) ──────────────────────────────────────────────
    record FightState(OneBlockPhase oldPhase, OneBlockPhase targetPhase, int majorIdx) {}

    private static final Map<UUID, FightState>      activeFights      = new ConcurrentHashMap<>();
    private static final Map<UUID, FightState>      pendingFights     = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID>            bossToPlayer      = new ConcurrentHashMap<>();
    private static final Map<UUID, List<ItemStack>> savedInventories  = new ConcurrentHashMap<>();
    private static final Map<UUID, ItemStack[]>     savedArmor        = new ConcurrentHashMap<>();
    private static final Map<UUID, ServerBossEvent> bossBars          = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID>            activeBossEntity  = new ConcurrentHashMap<>();
    private static final Map<UUID, int[]>           arenaPositions    = new ConcurrentHashMap<>();
    private static final Queue<BlockPos>            arenaRemoveQueue  = new ConcurrentLinkedQueue<>();
    private static ServerLevel                      arenaRemoveLevel  = null;
    private static final int BLOCKS_PER_TICK = 50;

    // ─── État co-op ───────────────────────────────────────────────────────────
    /** owner UUID → ensemble des helpers UUID actuellement dans l'arène */
    private static final Map<UUID, Set<UUID>>       bossHelpers       = new ConcurrentHashMap<>();
    /** helper UUID → owner UUID */
    private static final Map<UUID, UUID>            helperOwner       = new ConcurrentHashMap<>();
    /** invitee UUID → owner UUID (invitation en attente) */
    private static final Map<UUID, UUID>            pendingInvites    = new ConcurrentHashMap<>();
    /** Inventaire sauvegardé des helpers */
    private static final Map<UUID, List<ItemStack>> helperSavedInv    = new ConcurrentHashMap<>();
    private static final Map<UUID, ItemStack[]>     helperSavedArmor  = new ConcurrentHashMap<>();
    /** Owners actuellement en mode spectateur (mort mais helpers encore vivants) */
    private static final Set<UUID>                  spectatingOwners  = ConcurrentHashMap.newKeySet();
    /** GameType original de l'owner avant de passer en spectateur */
    private static final Map<UUID, GameType>        ownerPrevGameType = new ConcurrentHashMap<>();

    // ─── API publique ─────────────────────────────────────────────────────────

    public static boolean hasBossBlocking(UUID id) {
        return activeFights.containsKey(id) || pendingFights.containsKey(id);
    }

    public static boolean isInActiveFight(UUID id) {
        return activeFights.containsKey(id);
    }

    public static boolean isHelper(UUID id) {
        return helperOwner.containsKey(id);
    }

    public static boolean hasArenaInventory(UUID id) {
        return savedInventories.containsKey(id) || helperSavedInv.containsKey(id);
    }

    public static boolean isSpectatingOwner(UUID id) {
        return spectatingOwners.contains(id);
    }

    // ─── Invitations ──────────────────────────────────────────────────────────

    /**
     * L'owner envoie une invitation à un autre joueur.
     * Conditions : owner doit avoir un boss en attente (pendingFights) ou actif.
     */
    public static void sendInvite(ServerPlayer owner, ServerPlayer target) {
        UUID ownerId = owner.getUUID();
        UUID targetId = target.getUUID();

        if (!activeFights.containsKey(ownerId) && !pendingFights.containsKey(ownerId)) {
            owner.sendSystemMessage(Component.literal(
                "§cTu n'as pas de combat de boss actif à partager."));
            return;
        }
        if (targetId.equals(ownerId)) {
            owner.sendSystemMessage(Component.literal("§cTu ne peux pas t'inviter toi-même."));
            return;
        }
        if (helperOwner.containsKey(targetId) || activeFights.containsKey(targetId)) {
            owner.sendSystemMessage(Component.literal(
                "§c" + target.getName().getString() + " §cest déjà dans un combat."));
            return;
        }

        pendingInvites.put(targetId, ownerId);

        owner.sendSystemMessage(Component.literal(
            "§7Invitation envoyée à §f" + target.getName().getString() + "§7."));
        target.sendSystemMessage(Component.literal(
            "§6§l⚔ §r§e" + owner.getName().getString()
            + " §7t'invite à l'aider contre son boss de phase !"));
        target.sendSystemMessage(Component.literal(
            "§7Tape §f/boss accept §7pour rejoindre l'arène."));
    }

    /**
     * Un joueur accepte une invitation et entre dans l'arène comme helper.
     */
    public static void acceptInvite(ServerPlayer helper, MinecraftServer server) {
        UUID helperId = helper.getUUID();
        UUID ownerId  = pendingInvites.remove(helperId);

        if (ownerId == null) {
            helper.sendSystemMessage(Component.literal("§cAucune invitation en attente."));
            return;
        }

        ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null) {
            helper.sendSystemMessage(Component.literal("§cLe joueur qui t'a invité n'est plus connecté."));
            return;
        }

        // Le combat doit être actif (si seulement pending, l'owner doit d'abord faire /boss)
        FightState state = activeFights.get(ownerId);
        if (state == null) {
            helper.sendSystemMessage(Component.literal(
                "§cL'owner n'est pas encore dans l'arène. Demande-lui de taper §f/boss §cpremier."));
            return;
        }

        // Sauvegarde l'inventaire du helper et donne l'équipement d'arène
        saveHelperInventory(helper);
        equipArena(helper);

        // Enregistre le helper
        bossHelpers.computeIfAbsent(ownerId, k -> ConcurrentHashMap.newKeySet()).add(helperId);
        helperOwner.put(helperId, ownerId);

        // Téléporte dans l'arène
        int[] arenaPos = arenaPositions.get(ownerId);
        if (arenaPos != null) {
            helper.teleportTo(server.overworld(),
                arenaPos[0] + 0.5, ARENA_Y + 1, arenaPos[1] + 0.5,
                Set.of(), 0, 0, true);
        }

        // Ajoute à la boss bar de l'owner
        ServerBossEvent bar = bossBars.get(ownerId);
        if (bar != null) bar.addPlayer(helper);

        owner.sendSystemMessage(Component.literal(
            "§a§f" + helper.getName().getString() + " §a a rejoint ton combat !"));
        helper.sendSystemMessage(Component.literal(
            "§a✓ Tu aides §f" + owner.getName().getString()
            + " §a contre §f" + BOSS_NAMES[state.majorIdx()] + "§a !"));
        helper.sendSystemMessage(Component.literal(
            "§7Note : tu ne recevras pas la récompense de phase si le boss est vaincu."));
    }

    // ─── Démarrage du combat ──────────────────────────────────────────────────

    public static void onMajorPhaseTransition(ServerPlayer player, ServerLevel level,
                                               OneBlockPhase oldPhase, OneBlockPhase newPhase) {
        UUID id = player.getUUID();
        int majorIdx = oldPhase.getMajorPhaseIndex();
        if (majorIdx < 0 || majorIdx >= BOSS_MOBS.length) return;

        FightState state = new FightState(oldPhase, newPhase, majorIdx);
        startFight(player, level, state);
    }

    public static void retryBoss(ServerPlayer player, ServerLevel level) {
        UUID id = player.getUUID();

        if (activeFights.containsKey(id)) {
            player.sendSystemMessage(Component.literal("§cTu es déjà dans une arène !"));
            return;
        }

        FightState state = pendingFights.get(id);

        if (state == null) {
            PlayerDataManager.PlayerOneBlockData data =
                PlayerDataManager.getOrCreate(id, level.getServer());
            boolean oneBlockIsBedrock = level.getBlockState(data.blockPos).is(Blocks.BEDROCK);

            if (oneBlockIsBedrock) {
                OneBlockPhase currentPhase = data.getCurrentPhase();
                int majorIdx = currentPhase.getMajorPhaseIndex();
                int targetMajorIdx = majorIdx + 1;
                OneBlockPhase targetPhase = null;
                for (OneBlockPhase p : OneBlockPhase.values()) {
                    if (p.getMajorPhaseIndex() == targetMajorIdx) { targetPhase = p; break; }
                }
                if (targetPhase == null || majorIdx < 0 || majorIdx >= BOSS_MOBS.length) {
                    player.sendSystemMessage(Component.literal("§cImpossible de détecter le boss. Contacte un admin."));
                    return;
                }
                state = new FightState(currentPhase, targetPhase, majorIdx);
                pendingFights.put(id, state);
                savePendingFight(id, state, level.getServer());
                player.sendSystemMessage(Component.literal("§7Boss détecté automatiquement. Lancement du combat..."));
            } else {
                player.sendSystemMessage(Component.literal("§7Aucun boss en attente pour toi."));
                return;
            }
        }

        startFight(player, level, state);
    }

    public static void notifyBlocked(ServerPlayer player) {
        if (pendingFights.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal(
                "§c⚔ Tu dois d'abord affronter le boss ! §7Tape §f/boss §7pour entrer dans l'arène."));
        }
    }

    private static void startFight(ServerPlayer player, ServerLevel level, FightState state) {
        UUID id = player.getUUID();

        activeFights.put(id, state);
        pendingFights.remove(id);
        savePendingFight(id, state, level.getServer());

        saveInventory(player);
        equipArena(player);
        player.setHealth(player.getMaxHealth());

        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(id, level.getServer());
        int aX = data.blockPos.getX();
        int aZ = data.blockPos.getZ();
        buildArena(level, aX, aZ);
        arenaPositions.put(id, new int[]{aX, aZ});

        level.setBlock(data.blockPos, Blocks.BEDROCK.defaultBlockState(), 3);
        player.teleportTo(level, aX + 0.5, ARENA_Y + 1, aZ + 0.5, Set.of(), 0, 0, true);

        spawnBoss(player, level, aX, aZ, state);

        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 70, 15));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§c⚔ COMBAT DE PHASE ⚔")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal(BOSS_NAMES[state.majorIdx()] + " §7t'attend !")));
        player.sendSystemMessage(Component.literal(
            "§c⚔ §lCombat de phase ! §r§7Tue le boss pour débloquer la prochaine phase."));
        player.sendSystemMessage(Component.literal(
            "§7Utilise §f/boss invite <joueur> §7pour inviter un ami à t'aider."));
    }

    /** Donne l'équipement d'arène standard (utilisé pour l'owner et les helpers). */
    private static void equipArena(ServerPlayer player) {
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD,  new ItemStack(Items.DIAMOND_HELMET));
        player.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        player.setItemSlot(EquipmentSlot.LEGS,  new ItemStack(Items.DIAMOND_LEGGINGS));
        player.setItemSlot(EquipmentSlot.FEET,  new ItemStack(Items.DIAMOND_BOOTS));
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().setItem(1, new ItemStack(Items.BOW));
        player.getInventory().setItem(2, new ItemStack(Items.ARROW, 64));
        player.getInventory().setItem(3, new ItemStack(Items.ARROW, 64));
        player.getInventory().setItem(4, new ItemStack(Items.GOLDEN_APPLE, 5));
        player.getInventory().setItem(5, new ItemStack(Items.DIRT, 2));
        player.setHealth(player.getMaxHealth());
    }

    // ─── Événements mort ──────────────────────────────────────────────────────

    /** Appelé depuis LivingDeathEvent quand une entité (non-joueur) meurt. */
    public static void onEntityDeath(LivingEntity entity, ServerLevel level) {
        UUID bossId   = entity.getUUID();
        UUID ownerId  = bossToPlayer.get(bossId);
        if (ownerId == null) return;

        level.getServer().execute(() -> {
            try {
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerId);
                onBossVictory(ownerId, owner, level.getServer());
            } catch (Exception e) {
                OneBlockMod.LOGGER.error("[OneBlock] Erreur boss victory: {}", e.getMessage());
                cleanUpFight(ownerId, null);
            }
        });
    }

    /**
     * Appelé depuis LivingDeathEvent quand un joueur meurt en arène.
     * - Si c'est un helper → le retire de l'arène, restaure inventaire au respawn.
     * - Si c'est l'owner avec des helpers vivants → passe en spectateur.
     * - Si c'est l'owner sans helper (ou tous morts) → défaite classique.
     */
    public static void onPlayerKilledByBoss(ServerPlayer player) {
        UUID id = player.getUUID();

        // ── Cas helper ────────────────────────────────────────────────────────
        if (helperOwner.containsKey(id)) {
            handleHelperDeath(player);
            return;
        }

        // ── Cas owner ─────────────────────────────────────────────────────────
        if (!activeFights.containsKey(id)) return;

        Set<UUID> helpers = bossHelpers.get(id);
        boolean hasLiveHelpers = helpers != null && !helpers.isEmpty()
            && hasLivingHelper(id, (MinecraftServer) player.level().getServer());

        if (hasLiveHelpers) {
            // Marque pour passer en spectateur au respawn
            spectatingOwners.add(id);
            ownerPrevGameType.put(id, player.gameMode.getGameModeForPlayer());
            // Message aux helpers
            broadcastToHelpers(id, (MinecraftServer) player.level().getServer(),
                "§e" + player.getName().getString()
                + " §7est mort ! Continuez le combat à sa place !");
        } else {
            // Défaite classique
            FightState state = activeFights.get(id);
            onBossDefeat(player, state, (MinecraftServer) player.level().getServer());
        }
    }

    /**
     * Appelé depuis PlayerRespawnEvent.
     * Si l'owner est en attente de spectateur → téléporte dans l'arène en spectateur.
     */
    public static void onPlayerRespawnAfterDefeat(ServerPlayer player) {
        UUID id = player.getUUID();

        // ── Cas owner spectateur ───────────────────────────────────────────────
        if (spectatingOwners.contains(id)) {
            MinecraftServer server = (MinecraftServer) player.level().getServer();
            // Vide l'inventaire d'arène (inutile en spectateur)
            player.getInventory().clearContent();

            server.execute(() -> {
                // Passe en spectateur
                player.setGameMode(GameType.SPECTATOR);

                // Cherche un helper vivant à observer
                Set<UUID> helpers = bossHelpers.get(id);
                if (helpers != null) {
                    for (UUID hId : helpers) {
                        ServerPlayer helper = server.getPlayerList().getPlayer(hId);
                        if (helper != null && helper.isAlive()) {
                            player.setCamera(helper);
                            break;
                        }
                    }
                }

                // Téléporte quand même dans l'arène (fallback si setCamera échoue)
                int[] arenaPos = arenaPositions.get(id);
                if (arenaPos != null) {
                    player.teleportTo(server.overworld(),
                        arenaPos[0] + 0.5, ARENA_Y + 1, arenaPos[1] + 0.5,
                        Set.of(), 0, 0, true);
                }

                player.sendSystemMessage(Component.literal(
                    "§c☠ Tu es mort ! §7Tu observes le combat. Si tous tes alliés tombent, c'est la défaite."));
            });
            return;
        }

        // ── Cas owner défaite classique ou helper ─────────────────────────────
        if (!savedInventories.containsKey(id) && !savedArmor.containsKey(id)
                && !helperSavedInv.containsKey(id) && !helperSavedArmor.containsKey(id)) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) { restoreInventory(player); restoreHelperInventory(player); return; }

        server.execute(() -> {
            if (helperOwner.containsKey(id)) {
                restoreHelperInventory(player);
                teleportHelperHome(player, server);
                player.sendSystemMessage(Component.literal(
                    "§c☠ Tu as été éliminé de l'arène. Tu es retourné sur ton île."));
            } else {
                restoreInventory(player);
                player.sendSystemMessage(Component.literal(
                    "§c☠ Défaite ! §7Le boss t'attend encore. §fTape §e/boss §fpour réessayer."));
            }
        });
    }

    // ─── Victoire / Défaite ───────────────────────────────────────────────────

    private static void onBossVictory(UUID ownerId, ServerPlayer owner, MinecraftServer server) {
        FightState state = activeFights.get(ownerId);
        if (state == null) { cleanUpFight(ownerId, owner); return; }

        // Débloque la phase pour l'OWNER uniquement
        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(ownerId, server);
        data.blocksBroken = state.targetPhase().startCount;
        PlayerDataManager.saveToDisk(data, server);

        restoreOneBlock(server, data);
        clearPendingFightFile(ownerId, server);
        pendingFights.remove(ownerId);
        final UUID pid = ownerId;
        server.execute(() -> removeArena(server, pid));

        int reward = 50 + state.majorIdx() * 25;
        CoinManager.addCoins(ownerId, reward, server);

        // ── Gestion de l'owner ─────────────────────────────────────────────────
        if (spectatingOwners.remove(ownerId)) {
            // Owner était en spectateur → restaure son mode de jeu
            GameType prev = ownerPrevGameType.remove(ownerId);
            if (owner != null) {
                owner.setGameMode(prev != null ? prev : GameType.SURVIVAL);
                owner.setCamera(null); // reprend sa propre vue
            }
        }

        if (owner != null && owner.isAlive()) {
            AchievementManager.onBossKilled(owner);
            teleportToIsland(owner, data, server);
            restoreInventory(owner);
            sendVictoryMessage(owner, reward);
        }

        // ── Gestion des helpers ────────────────────────────────────────────────
        Set<UUID> helpers = bossHelpers.remove(ownerId);
        if (helpers != null) {
            for (UUID hId : helpers) {
                ServerPlayer helper = server.getPlayerList().getPlayer(hId);
                helperOwner.remove(hId);
                // Retire de la boss bar
                ServerBossEvent bar = bossBars.get(ownerId);
                if (bar != null && helper != null) bar.removePlayer(helper);

                if (helper != null && helper.isAlive()) {
                    restoreHelperInventory(helper);
                    teleportHelperHome(helper, server);
                    helper.sendSystemMessage(Component.literal(
                        "§a§l✦ Boss vaincu ! §r§7Tu es retourné sur ton île. Bien joué !"));
                }
            }
        }

        cleanUpFight(ownerId, owner);
    }

    private static void onBossDefeat(ServerPlayer player, FightState state, MinecraftServer server) {
        UUID id = player.getUUID();

        pendingFights.put(id, state);
        if (server != null) savePendingFight(id, state, server);

        // Despawn le boss
        UUID bossId = activeBossEntity.get(id);
        if (bossId != null && server != null) {
            net.minecraft.world.entity.Entity boss = server.overworld().getEntity(bossId);
            if (boss != null) boss.discard();
        }

        if (server != null) {
            final UUID fid = id;
            server.execute(() -> removeArena(server, fid));
        }

        // Renvoie les helpers chez eux (défaite commune)
        dismissHelpers(id, server, "§c☠ Défaite ! Le joueur qui t'avait invité a perdu. Tu es retourné sur ton île.");

        cleanUpFight(id, null);
        // L'inventaire owner sera restauré au respawn via onPlayerRespawnAfterDefeat
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Un helper meurt → on le retire de l'arène (il sera traité au respawn). */
    private static void handleHelperDeath(ServerPlayer helper) {
        UUID helperId = helper.getUUID();
        UUID ownerId  = helperOwner.get(helperId); // on garde la relation pour le respawn

        // Retire le helper de l'ensemble des actifs
        Set<UUID> helpers = bossHelpers.get(ownerId);
        if (helpers != null) helpers.remove(helperId);

        // Prévient l'owner (et les autres helpers)
        if (ownerId != null) {
            MinecraftServer server = (MinecraftServer) helper.level().getServer();
            ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
            if (owner != null) {
                owner.sendSystemMessage(Component.literal(
                    "§c" + helper.getName().getString() + " §7a été éliminé de l'arène !"));
            }

            // Si l'owner est en spectateur ET qu'il n'y a plus de helper vivant → défaite
            if (spectatingOwners.contains(ownerId)) {
                boolean anyAlive = hasLivingHelper(ownerId, server);
                if (!anyAlive) {
                    // Plus personne pour se battre → défaite
                    FightState state = activeFights.get(ownerId);
                    if (state != null && owner != null) {
                        owner.sendSystemMessage(Component.literal(
                            "§c§lTous tes alliés sont tombés. Défaite !"));
                        spectatingOwners.remove(ownerId);
                        GameType prev = ownerPrevGameType.remove(ownerId);
                        owner.setGameMode(prev != null ? prev : GameType.SURVIVAL);
                        owner.setCamera(null);
                        onBossDefeat(owner, state, server);
                    }
                } else {
                    // Il reste des helpers → change de caméra si besoin
                    if (owner != null) {
                        switchSpectatorCamera(owner, ownerId, server);
                        owner.sendSystemMessage(Component.literal(
                            "§e" + helper.getName().getString() + " §7est tombé. Camera changée."));
                    }
                }
            }
        }
        // L'inventaire du helper sera restauré dans onPlayerRespawnAfterDefeat
    }

    /** Renvoie tous les helpers chez eux avec un message. */
    private static void dismissHelpers(UUID ownerId, MinecraftServer server, String message) {
        Set<UUID> helpers = bossHelpers.remove(ownerId);
        if (helpers == null || server == null) return;
        for (UUID hId : helpers) {
            ServerPlayer helper = server.getPlayerList().getPlayer(hId);
            helperOwner.remove(hId);
            if (helper != null && helper.isAlive()) {
                restoreHelperInventory(helper);
                teleportHelperHome(helper, server);
                helper.sendSystemMessage(Component.literal(message));
            } else if (helper == null) {
                // Helper déconnecté : nettoie juste les maps
                helperSavedInv.remove(hId);
                helperSavedArmor.remove(hId);
            }
        }
    }

    /** Vérifie si au moins un helper est encore en vie dans l'arène. */
    private static boolean hasLivingHelper(UUID ownerId, MinecraftServer server) {
        Set<UUID> helpers = bossHelpers.get(ownerId);
        if (helpers == null || helpers.isEmpty()) return false;
        for (UUID hId : helpers) {
            ServerPlayer h = server.getPlayerList().getPlayer(hId);
            if (h != null && h.isAlive()) return true;
        }
        return false;
    }

    /** Change la caméra du spectateur vers un helper encore vivant. */
    private static void switchSpectatorCamera(ServerPlayer owner, UUID ownerId, MinecraftServer server) {
        Set<UUID> helpers = bossHelpers.get(ownerId);
        if (helpers == null) return;
        for (UUID hId : helpers) {
            ServerPlayer h = server.getPlayerList().getPlayer(hId);
            if (h != null && h.isAlive()) {
                owner.setCamera(h);
                return;
            }
        }
        owner.setCamera(null); // aucun helper vivant
    }

    /** Envoie un message à tous les helpers actifs d'un owner. */
    private static void broadcastToHelpers(UUID ownerId, MinecraftServer server, String msg) {
        Set<UUID> helpers = bossHelpers.get(ownerId);
        if (helpers == null || server == null) return;
        for (UUID hId : helpers) {
            ServerPlayer h = server.getPlayerList().getPlayer(hId);
            if (h != null) h.sendSystemMessage(Component.literal(msg));
        }
    }

    // ─── Tick boss bar ────────────────────────────────────────────────────────

    public static void tickBossBar(ServerLevel level) {
        for (Map.Entry<UUID, UUID> entry : activeBossEntity.entrySet()) {
            UUID playerId = entry.getKey();
            UUID bossId   = entry.getValue();
            ServerBossEvent bar = bossBars.get(playerId);
            if (bar == null) continue;
            net.minecraft.world.entity.Entity e = level.getEntity(bossId);
            if (e instanceof LivingEntity living) {
                bar.setProgress(Math.max(0f, living.getHealth() / living.getMaxHealth()));
            }
        }

        if (!arenaRemoveQueue.isEmpty() && arenaRemoveLevel != null) {
            int done = 0;
            while (!arenaRemoveQueue.isEmpty() && done < BLOCKS_PER_TICK) {
                BlockPos pos = arenaRemoveQueue.poll();
                if (pos != null) { arenaRemoveLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 2); done++; }
            }
            if (arenaRemoveQueue.isEmpty()) arenaRemoveLevel = null;
        }
    }

    // ─── Nettoyage ────────────────────────────────────────────────────────────

    private static void cleanUpFight(UUID ownerId, ServerPlayer owner) {
        UUID bossId = activeBossEntity.remove(ownerId);
        if (bossId != null) bossToPlayer.remove(bossId);
        activeFights.remove(ownerId);
        ServerBossEvent bar = bossBars.remove(ownerId);
        if (bar != null && owner != null) bar.removePlayer(owner);
    }

    public static void cleanupOnLogout(UUID playerId, ServerPlayer player) {
        // Si c'est un helper qui se déconnecte
        if (helperOwner.containsKey(playerId)) {
            UUID ownerId = helperOwner.remove(playerId);
            Set<UUID> helpers = bossHelpers.get(ownerId);
            if (helpers != null) helpers.remove(playerId);
            helperSavedInv.remove(playerId);
            helperSavedArmor.remove(playerId);

            MinecraftServer server = player != null ? (MinecraftServer) player.level().getServer() : null;
            if (server != null) {
                ServerPlayer owner = server.getPlayerList().getPlayer(ownerId);
                if (owner != null)
                    owner.sendSystemMessage(Component.literal(
                        "§c" + (player != null ? player.getName().getString() : "Un allié")
                        + " §7s'est déconnecté de l'arène."));
                // Si owner spectateur et plus de helpers → défaite
                if (spectatingOwners.contains(ownerId) && !hasLivingHelper(ownerId, server)) {
                    spectatingOwners.remove(ownerId);
                    GameType prev = ownerPrevGameType.remove(ownerId);
                    FightState state = activeFights.get(ownerId);
                    if (owner != null && state != null) {
                        owner.setGameMode(prev != null ? prev : GameType.SURVIVAL);
                        owner.setCamera(null);
                        onBossDefeat(owner, state, server);
                    }
                }
            }
            return;
        }

        // C'est l'owner qui se déconnecte
        cleanUpFight(playerId, player);
        savedInventories.remove(playerId);
        savedArmor.remove(playerId);
        arenaPositions.remove(playerId);
        spectatingOwners.remove(playerId);
        ownerPrevGameType.remove(playerId);
        pendingInvites.entrySet().removeIf(e -> e.getValue().equals(playerId));
    }

    // ─── Persistence ──────────────────────────────────────────────────────────

    private static void savePendingFight(UUID id, FightState state, MinecraftServer server) {
        try {
            File dir = getSaveDir(server); dir.mkdirs();
            CompoundTag tag = new CompoundTag();
            tag.putInt("MajorIdx",         state.majorIdx());
            tag.putInt("TargetStartCount", state.targetPhase().startCount);
            NbtIo.writeCompressed(tag, dir.toPath().resolve(id + "_boss.dat"));
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde boss {}: {}", id, e.getMessage());
        }
    }

    public static void loadPendingFight(UUID id, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(id + "_boss.dat");
            if (!path.toFile().exists()) return;
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            int majorIdx    = tag.getInt("MajorIdx").orElse(-1);
            int targetStart = tag.getInt("TargetStartCount").orElse(-1);
            if (majorIdx < 0 || targetStart < 0) return;
            OneBlockPhase target   = OneBlockPhase.fromCount(targetStart);
            OneBlockPhase oldPhase = findLastSubLevelOfMajorPhase(majorIdx);
            pendingFights.put(id, new FightState(oldPhase, target, majorIdx));
            OneBlockMod.LOGGER.info("[OneBlock] Boss en attente chargé pour {} (phase {})", id, target.displayName);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement boss {}: {}", id, e.getMessage());
        }
    }

    private static void clearPendingFightFile(UUID id, MinecraftServer server) {
        try { getSaveDir(server).toPath().resolve(id + "_boss.dat").toFile().delete(); }
        catch (Exception ignored) {}
    }

    private static OneBlockPhase findLastSubLevelOfMajorPhase(int majorIdx) {
        OneBlockPhase last = OneBlockPhase.PLAINS_1;
        for (OneBlockPhase p : OneBlockPhase.values()) {
            if (p.getMajorPhaseIndex() == majorIdx) last = p;
        }
        return last;
    }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }

    // ─── Inventaire ──────────────────────────────────────────────────────────

    private static void saveInventory(ServerPlayer player) {
        UUID id = player.getUUID();
        List<ItemStack> inv = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            inv.add(player.getInventory().getItem(i).copy());
        savedInventories.put(id, inv);
        savedArmor.put(id, new ItemStack[]{
            player.getItemBySlot(EquipmentSlot.HEAD).copy(),
            player.getItemBySlot(EquipmentSlot.CHEST).copy(),
            player.getItemBySlot(EquipmentSlot.LEGS).copy(),
            player.getItemBySlot(EquipmentSlot.FEET).copy(),
        });
    }

    private static void restoreInventory(ServerPlayer player) {
        UUID id = player.getUUID();
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD,  ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.LEGS,  ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.FEET,  ItemStack.EMPTY);
        List<ItemStack> inv = savedInventories.remove(id);
        if (inv != null) {
            for (int i = 0; i < Math.min(inv.size(), player.getInventory().getContainerSize()); i++)
                player.getInventory().setItem(i, inv.get(i));
        }
        ItemStack[] armor = savedArmor.remove(id);
        if (armor != null) {
            if (armor.length > 0) player.setItemSlot(EquipmentSlot.HEAD,  armor[0]);
            if (armor.length > 1) player.setItemSlot(EquipmentSlot.CHEST, armor[1]);
            if (armor.length > 2) player.setItemSlot(EquipmentSlot.LEGS,  armor[2]);
            if (armor.length > 3) player.setItemSlot(EquipmentSlot.FEET,  armor[3]);
        }
    }

    private static void saveHelperInventory(ServerPlayer player) {
        UUID id = player.getUUID();
        List<ItemStack> inv = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++)
            inv.add(player.getInventory().getItem(i).copy());
        helperSavedInv.put(id, inv);
        helperSavedArmor.put(id, new ItemStack[]{
            player.getItemBySlot(EquipmentSlot.HEAD).copy(),
            player.getItemBySlot(EquipmentSlot.CHEST).copy(),
            player.getItemBySlot(EquipmentSlot.LEGS).copy(),
            player.getItemBySlot(EquipmentSlot.FEET).copy(),
        });
    }

    private static void restoreHelperInventory(ServerPlayer player) {
        UUID id = player.getUUID();
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD,  ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.LEGS,  ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.FEET,  ItemStack.EMPTY);
        List<ItemStack> inv = helperSavedInv.remove(id);
        if (inv != null) {
            for (int i = 0; i < Math.min(inv.size(), player.getInventory().getContainerSize()); i++)
                player.getInventory().setItem(i, inv.get(i));
        }
        ItemStack[] armor = helperSavedArmor.remove(id);
        if (armor != null) {
            if (armor.length > 0) player.setItemSlot(EquipmentSlot.HEAD,  armor[0]);
            if (armor.length > 1) player.setItemSlot(EquipmentSlot.CHEST, armor[1]);
            if (armor.length > 2) player.setItemSlot(EquipmentSlot.LEGS,  armor[2]);
            if (armor.length > 3) player.setItemSlot(EquipmentSlot.FEET,  armor[3]);
        }
    }

    // ─── Utilitaires ─────────────────────────────────────────────────────────

    private static void teleportHelperHome(ServerPlayer helper, MinecraftServer server) {
        UUID helperId = helperOwner.remove(helper.getUUID());
        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(helper.getUUID(), server);
        helper.teleportTo(server.overworld(),
            data.blockPos.getX() + 0.5,
            data.blockPos.getY() + 1.5,
            data.blockPos.getZ() + 0.5,
            Set.of(), helper.getYRot(), helper.getXRot(), true);
    }

    private static void buildArena(ServerLevel level, int cx, int cz) {
        int y = ARENA_Y, r = ARENA_HALF, wh = WALL_HEIGHT;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                level.setBlock(new BlockPos(cx+dx, y-1, cz+dz), Blocks.BEDROCK.defaultBlockState(), 3);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                level.setBlock(new BlockPos(cx+dx, y, cz+dz), Blocks.BEDROCK.defaultBlockState(), 3);
        for (int dx = -(r-1); dx <= r-1; dx++)
            for (int dz = -(r-1); dz <= r-1; dz++)
                for (int dy = 1; dy <= wh+2; dy++)
                    level.setBlock(new BlockPos(cx+dx, y+dy, cz+dz), Blocks.AIR.defaultBlockState(), 3);
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (dx != -r && dx != r && dz != -r && dz != r) continue;
                for (int dy = 1; dy <= wh; dy++)
                    level.setBlock(new BlockPos(cx+dx, y+dy, cz+dz), Blocks.BEDROCK.defaultBlockState(), 3);
            }
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                level.setBlock(new BlockPos(cx+dx, y+wh+1, cz+dz), Blocks.BEDROCK.defaultBlockState(), 3);
        for (int dx = -r+2; dx <= r-2; dx += 4)
            for (int dz = -r+2; dz <= r-2; dz += 4)
                level.setBlock(new BlockPos(cx+dx, y+wh+1, cz+dz), Blocks.GLOWSTONE.defaultBlockState(), 3);
        for (int dx = -(r-1); dx <= r-1; dx++)
            for (int dz = -(r-1); dz <= r-1; dz++) {
                boolean checker = (dx+dz+100) % 2 == 0;
                level.setBlock(new BlockPos(cx+dx, y, cz+dz),
                    (checker ? Blocks.CHISELED_STONE_BRICKS : Blocks.POLISHED_BASALT).defaultBlockState(), 3);
            }
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                level.setBlock(new BlockPos(cx+dx, y, cz+dz), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        level.setBlock(new BlockPos(cx, y, cz), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        int[][] corners = {{-(r-1),-(r-1)},{-(r-1),r-1},{r-1,-(r-1)},{r-1,r-1}};
        for (int[] c : corners) {
            for (int dy = 1; dy <= wh; dy++)
                level.setBlock(new BlockPos(cx+c[0], y+dy, cz+c[1]), Blocks.BEDROCK.defaultBlockState(), 3);
            level.setBlock(new BlockPos(cx+c[0], y+wh, cz+c[1]), Blocks.GLOWSTONE.defaultBlockState(), 3);
        }
        int lava = r-4;
        for (int[] lc : new int[][]{{-lava,-lava},{-lava,lava},{lava,-lava},{lava,lava}})
            for (int ddx = -1; ddx <= 1; ddx++)
                for (int ddz = -1; ddz <= 1; ddz++)
                    level.setBlock(new BlockPos(cx+lc[0]+ddx, y, cz+lc[1]+ddz), Blocks.LAVA.defaultBlockState(), 3);
    }

    private static void spawnBoss(ServerPlayer player, ServerLevel level, int cx, int cz, FightState state) {
        int idx = state.majorIdx();
        String bossName = BOSS_NAMES[idx];
        double health   = 60 + idx * 30;
        double damage   = 5  + idx * 2;
        Optional<EntityType<?>> typeOpt = EntityType.byString(BOSS_MOBS[idx]);
        if (typeOpt.isEmpty()) { OneBlockMod.LOGGER.warn("[OneBlock] Boss mob inconnu : {}", BOSS_MOBS[idx]); return; }
        net.minecraft.world.entity.Entity entity = typeOpt.get().create(level, EntitySpawnReason.NATURAL);
        if (!(entity instanceof LivingEntity boss)) return;
        boss.setPos(cx+0.5, ARENA_Y+1, cz+10);
        boss.setCustomName(Component.literal(bossName));
        boss.setCustomNameVisible(true);
        if (boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) != null)
            boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(health);
        boss.setHealth((float) health);
        if (boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) != null)
            boss.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE).setBaseValue(damage);
        if (boss instanceof net.minecraft.world.entity.Mob mob) mob.setPersistenceRequired();
        level.addFreshEntity(boss);
        UUID playerId = player.getUUID();
        activeBossEntity.put(playerId, boss.getUUID());
        bossToPlayer.put(boss.getUUID(), playerId);
        ServerBossEvent bar = new ServerBossEvent(playerId,
            Component.literal(bossName + "  §8[§c" + (int)health + " PV§8]"),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        bar.setProgress(1f);
        bar.addPlayer(player);
        bossBars.put(playerId, bar);
    }

    private static void sendVictoryMessage(ServerPlayer player, int reward) {
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 80, 20));
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§a§l✦ VICTOIRE ✦")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal("§ePhase débloquée ! §7+" + reward + " §6⬡")));
        player.sendSystemMessage(Component.literal(
            "§a§l✦ Boss vaincu ! §r§7Phase débloquée. §e+" + reward + " OneCoins."));
    }

    private static void removeArena(MinecraftServer server, UUID playerId) {
        if (server == null) return;
        int[] pos = arenaPositions.remove(playerId);
        if (pos == null) return;
        ServerLevel level = server.overworld();
        for (int dx = -ARENA_HALF; dx <= ARENA_HALF; dx++)
            for (int dz = -ARENA_HALF; dz <= ARENA_HALF; dz++)
                for (int dy = -1; dy <= WALL_HEIGHT+2; dy++)
                    level.setBlock(new BlockPos(pos[0]+dx, ARENA_Y+dy, pos[1]+dz),
                        Blocks.AIR.defaultBlockState(), 2);
    }

    private static void restoreOneBlock(MinecraftServer server, PlayerDataManager.PlayerOneBlockData data) {
        net.minecraft.world.level.block.Block block =
            data.getCurrentPhase().getRandomBlock(new java.util.Random());
        server.overworld().setBlock(data.blockPos, block.defaultBlockState(), 3);
    }

    private static void teleportToIsland(ServerPlayer player,
                                          PlayerDataManager.PlayerOneBlockData data,
                                          MinecraftServer server) {
        player.teleportTo(server.overworld(),
            data.blockPos.getX()+0.5, data.blockPos.getY()+1.5, data.blockPos.getZ()+0.5,
            Set.of(), player.getYRot(), player.getXRot(), true);
    }
}
