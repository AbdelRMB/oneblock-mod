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
 *  1. Transition de phase majeure détectée → téléportation dans l'arène 30×30 en bedrock
 *  2. Inventaire sauvegardé, équipement : full diamant + épée diamant + 5 terre
 *  3. Boss spawné au centre de l'arène (puissance croissante par phase)
 *  4. Victoire → retour île, inventaire restauré, phase débloquée
 *  5. Défaite → retour île, inventaire restauré, boss en attente → /boss pour réessayer
 *     Le OneBlock est bloqué jusqu'à victoire sur le boss.
 */
public class BossManager {

    // ─── Constantes arène ────────────────────────────────────────────────────
    private static final int ARENA_Y       = 150;   // altitude de l'arène
    private static final int ARENA_HALF    = 20;    // demi-côté → arène 40×40
    private static final int WALL_HEIGHT   = 10;    // hauteur des murs

    // ─── Mobs par phase majeure ───────────────────────────────────────────────
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
        "§cRoi Zombie",         "§7Archer des Ombres",    "§bSorcière des Glaces",
        "§9Gardien des Fonds",  "§2Ravageur",             "§2Invocateur des Marais",
        "§8Vindicateur Maudit", "§eRôdeur du Désert",     "§cSquelette du Nether",
        "§5Le Destructeur",
    };

    // ─── État par joueur ──────────────────────────────────────────────────────
    /** Paramètres d'un combat en cours ou en attente. */
    record FightState(OneBlockPhase oldPhase, OneBlockPhase targetPhase, int majorIdx) {}

    /** Combat actif (joueur dans l'arène). */
    private static final Map<UUID, FightState>    activeFights      = new ConcurrentHashMap<>();
    /** Combat en attente (joueur a perdu, peut retenter avec /boss). */
    private static final Map<UUID, FightState>    pendingFights     = new ConcurrentHashMap<>();
    /** UUID de l'entité boss → UUID du joueur. */
    private static final Map<UUID, UUID>           bossToPlayer      = new ConcurrentHashMap<>();
    /** Inventaire sauvegardé avant l'arène. */
    private static final Map<UUID, List<ItemStack>> savedInventories = new ConcurrentHashMap<>();
    /** Armure sauvegardée. */
    private static final Map<UUID, ItemStack[]>    savedArmor        = new ConcurrentHashMap<>();
    /** Boss bars actives. */
    private static final Map<UUID, ServerBossEvent> bossBars         = new ConcurrentHashMap<>();
    /** UUID entité boss active par joueur. */
    private static final Map<UUID, UUID>           activeBossEntity  = new ConcurrentHashMap<>();
    /** Position centre de l'arène par joueur [cx, cz]. */
    private static final Map<UUID, int[]>          arenaPositions    = new ConcurrentHashMap<>();
    /** File de blocs à supprimer progressivement (50 blocs/tick max). */
    private static final Queue<BlockPos>           arenaRemoveQueue  = new ConcurrentLinkedQueue<>();
    private static ServerLevel                     arenaRemoveLevel  = null;
    private static final int BLOCKS_PER_TICK = 50;

    // ─── API publique ─────────────────────────────────────────────────────────

    /** True si un combat est actif OU en attente → bloque le OneBlock. */
    public static boolean hasBossBlocking(UUID id) {
        return activeFights.containsKey(id) || pendingFights.containsKey(id);
    }

    public static boolean isBossOf(UUID bossEntityId, UUID playerId) {
        return bossEntityId.equals(activeBossEntity.get(playerId));
    }

    public static boolean isInActiveFight(UUID id) {
        return activeFights.containsKey(id);
    }

    /** Appelé lors d'une transition de phase majeure. Démarre le combat. */
    public static void onMajorPhaseTransition(ServerPlayer player, ServerLevel level,
                                               OneBlockPhase oldPhase, OneBlockPhase newPhase) {
        UUID id = player.getUUID();
        int majorIdx = oldPhase.getMajorPhaseIndex();
        if (majorIdx < 0 || majorIdx >= BOSS_MOBS.length) {
            // Pas de boss pour cette transition → débloque directement
            return;
        }

        FightState state = new FightState(oldPhase, newPhase, majorIdx);
        startFight(player, level, state);
    }

    /** Commande /boss — réessaie le combat si en attente. */
    public static void retryBoss(ServerPlayer player, ServerLevel level) {
        UUID id = player.getUUID();

        if (activeFights.containsKey(id)) {
            player.sendSystemMessage(Component.literal("§cTu es déjà dans une arène !"));
            return;
        }

        FightState state = pendingFights.get(id);

        // Auto-détection : si le OneBlock est en bedrock mais pas de boss en mémoire
        // (cas d'un redémarrage avant que le système de persistence existait)
        if (state == null) {
            PlayerDataManager.PlayerOneBlockData data =
                PlayerDataManager.getOrCreate(id, level.getServer());
            boolean oneBlockIsBedrock = level.getBlockState(data.blockPos)
                .is(Blocks.BEDROCK);

            if (oneBlockIsBedrock) {
                // Reconstruit l'état du boss à partir de la progression actuelle
                OneBlockPhase currentPhase = data.getCurrentPhase();
                int majorIdx = currentPhase.getMajorPhaseIndex();

                // Cherche la prochaine phase majeure
                int targetMajorIdx = majorIdx + 1;
                OneBlockPhase targetPhase = null;
                for (OneBlockPhase p : OneBlockPhase.values()) {
                    if (p.getMajorPhaseIndex() == targetMajorIdx) {
                        targetPhase = p;
                        break;
                    }
                }

                if (targetPhase == null || majorIdx < 0 || majorIdx >= BOSS_MOBS.length) {
                    player.sendSystemMessage(Component.literal(
                        "§cImpossible de détecter le boss. Contacte un admin."));
                    return;
                }

                state = new FightState(currentPhase, targetPhase, majorIdx);
                pendingFights.put(id, state);
                savePendingFight(id, state, level.getServer());

                player.sendSystemMessage(Component.literal(
                    "§7Boss détecté automatiquement. Lancement du combat..."));
            } else {
                player.sendSystemMessage(Component.literal("§7Aucun boss en attente pour toi."));
                return;
            }
        }

        startFight(player, level, state);
    }

    /** Joueur mine son OneBlock avec un boss en attente → message de blocage. */
    public static void notifyBlocked(ServerPlayer player) {
        if (pendingFights.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal(
                "§c⚔ Tu dois d'abord affronter le boss ! §7Tape §f/boss §7pour entrer dans l'arène."));
        }
    }

    // ─── Démarrage du combat ──────────────────────────────────────────────────

    private static void startFight(ServerPlayer player, ServerLevel level, FightState state) {
        UUID id = player.getUUID();

        activeFights.put(id, state);
        pendingFights.remove(id);

        // Sauvegarde immédiatement l'état du boss sur disque
        // → si le serveur redémarre pendant le combat, le joueur pourra refaire /boss
        savePendingFight(id, state, level.getServer());

        // 1. Sauvegarde l'inventaire
        saveInventory(player);

        // 2. Donne l'équipement d'arène
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

        // 3. Construit l'arène + remplace le OneBlock par de la bedrock
        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(id, level.getServer());
        int aX = data.blockPos.getX();
        int aZ = data.blockPos.getZ();
        buildArena(level, aX, aZ);
        arenaPositions.put(id, new int[]{aX, aZ});

        // Remplace le OneBlock par bedrock → indestructible pendant le combat
        level.setBlock(data.blockPos, Blocks.BEDROCK.defaultBlockState(), 3);

        // 4. Téléporte dans l'arène
        player.teleportTo(level, aX + 0.5, ARENA_Y + 1, aZ + 0.5,
            Set.of(), 0, 0, true);

        // 5. Spawne le boss
        spawnBoss(player, level, aX, aZ, state);

        // 6. Message + titre
        player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 70, 15));
        player.connection.send(new ClientboundSetTitleTextPacket(
            Component.literal("§c⚔ COMBAT DE PHASE ⚔")));
        player.connection.send(new ClientboundSetSubtitleTextPacket(
            Component.literal(BOSS_NAMES[state.majorIdx()] + " §7t'attend !")));
        player.sendSystemMessage(Component.literal(
            "§c⚔ §lCombat de phase ! §r§7Tue le boss pour débloquer la prochaine phase."));
        player.sendSystemMessage(Component.literal(
            "§7Tu as : §ffull diamant §7+ §f5 terre§7. Bonne chance !"));
    }

    // ─── Construction de l'arène ──────────────────────────────────────────────

    private static void buildArena(ServerLevel level, int cx, int cz) {
        int y  = ARENA_Y;
        int r  = ARENA_HALF;
        int wh = WALL_HEIGHT;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean isWall = dx == -r || dx == r || dz == -r || dz == r;

                // Sol
                level.setBlock(new BlockPos(cx + dx, y, cz + dz),
                    Blocks.BEDROCK.defaultBlockState(), 3);

                // Murs
                if (isWall) {
                    for (int dy = 1; dy <= wh; dy++) {
                        level.setBlock(new BlockPos(cx + dx, y + dy, cz + dz),
                            Blocks.BEDROCK.defaultBlockState(), 3);
                    }
                    // Toit du mur = bedrock
                    level.setBlock(new BlockPos(cx + dx, y + wh + 1, cz + dz),
                        Blocks.BEDROCK.defaultBlockState(), 3);
                } else {
                    // Intérieur : air propre
                    for (int dy = 1; dy <= wh + 1; dy++) {
                        level.setBlock(new BlockPos(cx + dx, y + dy, cz + dz),
                            Blocks.AIR.defaultBlockState(), 3);
                    }
                }
            }
        }

        // Lumière centrale (glowstone au plafond)
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                level.setBlock(new BlockPos(cx + dx, y + wh + 1, cz + dz),
                    Blocks.GLOWSTONE.defaultBlockState(), 3);
            }
        }
    }

    // ─── Spawn du boss ────────────────────────────────────────────────────────

    private static void spawnBoss(ServerPlayer player, ServerLevel level,
                                   int cx, int cz, FightState state) {
        int idx = state.majorIdx();
        String mobKey   = BOSS_MOBS[idx];
        String bossName = BOSS_NAMES[idx];
        double health   = 60  + idx * 30;
        double damage   = 5   + idx * 2;

        Optional<EntityType<?>> typeOpt = EntityType.byString(mobKey);
        if (typeOpt.isEmpty()) {
            OneBlockMod.LOGGER.warn("[OneBlock] Boss mob inconnu : {}", mobKey);
            return;
        }

        net.minecraft.world.entity.Entity entity = typeOpt.get().create(level, EntitySpawnReason.NATURAL);
        if (!(entity instanceof LivingEntity boss)) return;

        boss.setPos(cx + 0.5, ARENA_Y + 1, cz + 10); // décalé pour laisser de l'espace
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

        // Boss bar
        ServerBossEvent bar = new ServerBossEvent(playerId,
            Component.literal(bossName + "  §8[§c" + (int)health + " PV§8]"),
            BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
        bar.setProgress(1f);
        bar.addPlayer(player);
        bossBars.put(playerId, bar);
    }

    // ─── Événements ──────────────────────────────────────────────────────────

    /** Appelé depuis LivingDeathEvent quand une entité meurt. */
    public static void onEntityDeath(LivingEntity entity, ServerLevel level) {
        UUID bossId   = entity.getUUID();
        UUID playerId = bossToPlayer.get(bossId);
        if (playerId == null) return;

        level.getServer().execute(() -> {
            try {
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(playerId);
                onBossVictory(playerId, player, level.getServer());
            } catch (Exception e) {
                OneBlockMod.LOGGER.error("[OneBlock] Erreur boss victory: {}", e.getMessage());
                cleanUp(playerId, null);
            }
        });
    }

    /** Appelé depuis LivingDeathEvent quand LE BOSS tue le joueur. */
    public static void onPlayerKilledByBoss(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!activeFights.containsKey(id)) return;

        FightState state = activeFights.get(id);
        onBossDefeat(player, state, (MinecraftServer) player.level().getServer());
    }

    // ─── Victoire / Défaite ───────────────────────────────────────────────────

    private static void onBossVictory(UUID playerId, ServerPlayer player, MinecraftServer server) {
        FightState state = activeFights.get(playerId);
        if (state == null) { cleanUp(playerId, player); return; }

        // Débloque la phase
        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(playerId, server);
        data.blocksBroken = state.targetPhase().startCount;
        PlayerDataManager.saveToDisk(data, server);

        // Restaure le OneBlock + supprime le fichier boss en attente
        restoreOneBlock(server, data);
        clearPendingFightFile(playerId, server);
        pendingFights.remove(playerId);
        // Supprime l'arène au tick suivant (assure que les chunks sont bien chargés)
        final UUID pid = playerId;
        server.execute(() -> removeArena(server, pid));

        int reward = 50 + state.majorIdx() * 25;
        CoinManager.addCoins(playerId, reward, server);

        if (player != null && player.isAlive()) {
            AchievementManager.onBossKilled(player);
            teleportToIsland(player, data, server);
            restoreInventory(player);

            player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 80, 20));
            player.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal("§a§l✦ VICTOIRE ✦")));
            player.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal("§ePhase débloquée ! §7+" + reward + " §6⬡")));
            player.sendSystemMessage(Component.literal(
                "§a§l✦ Boss vaincu ! §r§7Phase débloquée. §e+" + reward + " OneCoins."));
        }

        cleanUp(playerId, player);
    }

    private static void onBossDefeat(ServerPlayer player, FightState state, MinecraftServer server) {
        UUID id = player.getUUID();

        // Passe en attente pour /boss retry (persisté sur disque)
        pendingFights.put(id, state);
        if (server != null) savePendingFight(id, state, server);

        // Despawn le boss
        UUID bossId = activeBossEntity.get(id);
        if (bossId != null && server != null) {
            ServerLevel level = server.overworld();
            net.minecraft.world.entity.Entity boss = level.getEntity(bossId);
            if (boss != null) boss.discard();
        }

        // Supprime l'arène au tick suivant
        if (server != null) {
            final UUID fid = id;
            server.execute(() -> removeArena(server, fid));
        }

        // Le OneBlock reste en BEDROCK → joueur doit faire /boss pour réessayer
        cleanUp(id, null); // nettoie les maps mais garde pendingFights

        // L'inventaire sera restauré au respawn (voir onPlayerRespawn dans PlayerEventHandler)
    }

    /** Appelé depuis PlayerEventHandler.onPlayerRespawn si le joueur était dans une arène. */
    public static void onPlayerRespawnAfterDefeat(ServerPlayer player) {
        UUID id = player.getUUID();
        if (!savedInventories.containsKey(id) && !savedArmor.containsKey(id)) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) { restoreInventory(player); return; }

        server.execute(() -> {
            restoreInventory(player);
            player.sendSystemMessage(Component.literal(
                "§c☠ Défaite ! §7Le boss t'attend encore. §fTape §e/boss §fpour réessayer."));
        });
    }

    /** True si le joueur a un inventaire sauvegardé (était dans une arène). */
    public static boolean hasArenaInventory(UUID id) {
        return savedInventories.containsKey(id);
    }

    // ─── Tick boss bar ────────────────────────────────────────────────────────

    public static void tickBossBar(ServerLevel level) {
        // Mise à jour des boss bars actives
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

        // Suppression progressive de l'arène : 50 blocs par tick max
        if (!arenaRemoveQueue.isEmpty() && arenaRemoveLevel != null) {
            int done = 0;
            while (!arenaRemoveQueue.isEmpty() && done < BLOCKS_PER_TICK) {
                BlockPos pos = arenaRemoveQueue.poll();
                if (pos != null) {
                    arenaRemoveLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    done++;
                }
            }
            if (arenaRemoveQueue.isEmpty()) {
                arenaRemoveLevel = null;
            }
        }
    }

    // ─── Nettoyage ────────────────────────────────────────────────────────────

    private static void cleanUp(UUID playerId, ServerPlayer player) {
        UUID bossId = activeBossEntity.remove(playerId);
        if (bossId != null) bossToPlayer.remove(bossId);
        activeFights.remove(playerId);

        ServerBossEvent bar = bossBars.remove(playerId);
        if (bar != null && player != null) bar.removePlayer(player);
    }

    public static void cleanupOnLogout(UUID playerId, ServerPlayer player) {
        cleanUp(playerId, player);
        savedInventories.remove(playerId);
        savedArmor.remove(playerId);
        arenaPositions.remove(playerId);
        // On garde pendingFights en mémoire ET sur disque → réessai possible à la reconnexion
    }

    // ─── Persistence du boss en attente ──────────────────────────────────────

    /** Sauvegarde l'état "boss en attente" sur disque. */
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

    /** Charge l'état "boss en attente" depuis le disque au login. */
    public static void loadPendingFight(UUID id, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(id + "_boss.dat");
            if (!path.toFile().exists()) return;

            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            int majorIdx    = tag.getInt("MajorIdx").orElse(-1);
            int targetStart = tag.getInt("TargetStartCount").orElse(-1);
            if (majorIdx < 0 || targetStart < 0) return;

            OneBlockPhase target   = OneBlockPhase.fromCount(targetStart);
            // oldPhase = dernière sous-phase de la phase majeure précédente
            OneBlockPhase oldPhase = findLastSubLevelOfMajorPhase(majorIdx);

            FightState state = new FightState(oldPhase, target, majorIdx);
            pendingFights.put(id, state);

            OneBlockMod.LOGGER.info("[OneBlock] Boss en attente chargé pour {} (phase {})",
                id, target.displayName);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement boss {}: {}", id, e.getMessage());
        }
    }

    /** Supprime le fichier de boss en attente (après victoire). */
    private static void clearPendingFightFile(UUID id, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(id + "_boss.dat");
            path.toFile().delete();
        } catch (Exception e) {
            OneBlockMod.LOGGER.warn("[OneBlock] Impossible de supprimer boss.dat : {}", e.getMessage());
        }
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
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            inv.add(player.getInventory().getItem(i).copy());
        }
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

        // Vide l'inventaire actuel (équipement d'arène)
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD,  ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.LEGS,  ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.FEET,  ItemStack.EMPTY);

        // Restaure l'inventaire
        List<ItemStack> inv = savedInventories.remove(id);
        if (inv != null) {
            for (int i = 0; i < Math.min(inv.size(), player.getInventory().getContainerSize()); i++) {
                player.getInventory().setItem(i, inv.get(i));
            }
        }

        ItemStack[] armor = savedArmor.remove(id);
        if (armor != null) {
            if (armor.length > 0) player.setItemSlot(EquipmentSlot.HEAD,  armor[0]);
            if (armor.length > 1) player.setItemSlot(EquipmentSlot.CHEST, armor[1]);
            if (armor.length > 2) player.setItemSlot(EquipmentSlot.LEGS,  armor[2]);
            if (armor.length > 3) player.setItemSlot(EquipmentSlot.FEET,  armor[3]);
        }
    }

    // ─── Utilitaires ─────────────────────────────────────────────────────────

    private static void removeArena(MinecraftServer server, UUID playerId) {
        if (server == null) return;
        int[] pos = arenaPositions.remove(playerId);
        if (pos == null) return;
        int cx = pos[0], cz = pos[1];
        int r  = ARENA_HALF;
        int wh = WALL_HEIGHT;

        // Enfile tous les blocs à supprimer — traitement progressif dans tickBossBar
        arenaRemoveLevel = server.overworld();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy <= wh + 2; dy++) {
                    arenaRemoveQueue.add(new BlockPos(cx + dx, ARENA_Y + dy, cz + dz));
                }
            }
        }
        OneBlockMod.LOGGER.info("[OneBlock] Arène mise en file de suppression ({} blocs)", arenaRemoveQueue.size());
    }

    private static void restoreOneBlock(MinecraftServer server,
                                         PlayerDataManager.PlayerOneBlockData data) {
        ServerLevel level = server.overworld();
        // Remet le bloc correspondant à la phase actuelle
        net.minecraft.world.level.block.Block block =
            data.getCurrentPhase().getRandomBlock(new java.util.Random());
        level.setBlock(data.blockPos, block.defaultBlockState(), 3);
    }

    private static void teleportToIsland(ServerPlayer player,
                                          PlayerDataManager.PlayerOneBlockData data,
                                          MinecraftServer server) {
        player.teleportTo(server.overworld(),
            data.blockPos.getX() + 0.5,
            data.blockPos.getY() + 1.5,
            data.blockPos.getZ() + 0.5,
            Set.of(), player.getYRot(), player.getXRot(), true);
    }
}
