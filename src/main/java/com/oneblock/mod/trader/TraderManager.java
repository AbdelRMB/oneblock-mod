package com.oneblock.mod.trader;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.data.IslandExtensionManager;
import com.oneblock.mod.data.PlayerDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère le villageois marchand quotidien de chaque joueur.
 *
 * Cycle :
 *   - Un nouveau marchand apparaît chaque matin (jour Minecraft).
 *   - À son arrivée : feu d'artifice + spawn des mobs commandés la veille.
 *   - Ses trades dépendent du niveau d'extension du joueur.
 *   - Quand un joueur achète un "bon de mob", le mob est ajouté à la file
 *     de spawn et apparaîtra au prochain marchand.
 */
public class TraderManager {

    /** UUIDs des marchands actifs (playerUUID → traderEntityUUID). */
    private static final Map<UUID, UUID> activeTraders = new ConcurrentHashMap<>();

    /** IDs des marchands que nous avons spawnés (pour filtrer les trades). */
    public static final Set<UUID> managedTraderIds = ConcurrentHashMap.newKeySet();

    /** Dernier jour Minecraft où un marchand a été spawné pour ce joueur. */
    private static final Map<UUID, Long> lastTraderDay = new ConcurrentHashMap<>();

    /** Mobs en attente de spawn pour le prochain marchand (playerUUID → liste de mob keys). */
    private static final Map<UUID, List<String>> pendingMobs = new ConcurrentHashMap<>();

    // ─── Cycle journalier ────────────────────────────────────────────────────

    /**
     * Appelé chaque tick serveur.
     * Spawne un marchand si un nouveau jour a commencé pour ce joueur.
     */
    public static void tickForPlayer(ServerPlayer player, ServerLevel level) {
        UUID playerId = player.getUUID();
        long currentDay = level.getGameTime() / 24000L;
        long lastDay    = lastTraderDay.getOrDefault(playerId, -1L);

        if (currentDay > lastDay) {
            lastTraderDay.put(playerId, currentDay);
            spawnTraderForPlayer(player, level);
        }
    }

    /** Initialise le suivi journalier à la connexion d'un joueur. */
    public static void onPlayerLogin(ServerPlayer player, MinecraftServer server) {
        UUID playerId = player.getUUID();
        loadPendingMobs(playerId, server);
        // lastTraderDay reste à -1 si non chargé → forcera un spawn dès le prochain tick
    }

    /** Sauvegarde à la déconnexion. */
    public static void onPlayerLogout(UUID playerId, MinecraftServer server) {
        savePendingMobs(playerId, server);
        activeTraders.remove(playerId);
        lastTraderDay.remove(playerId);
    }

    // ─── Spawn du marchand ───────────────────────────────────────────────────

    private static void spawnTraderForPlayer(ServerPlayer player, ServerLevel level) {
        UUID playerId = player.getUUID();

        // 1. Supprimer l'ancien marchand s'il existe encore
        UUID oldTraderId = activeTraders.get(playerId);
        if (oldTraderId != null) {
            net.minecraft.world.entity.Entity old = level.getEntity(oldTraderId);
            if (old != null) old.discard();
            managedTraderIds.remove(oldTraderId);
        }

        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(playerId, level.getServer());
        BlockPos islandPos = data.blockPos;

        // 2. Spawner les mobs commandés la veille
        List<String> pending = pendingMobs.getOrDefault(playerId, List.of());
        for (String mobKey : pending) {
            spawnMob(mobKey, level, islandPos);
        }
        pendingMobs.remove(playerId);
        savePendingMobs(playerId, level.getServer());

        // 3. Lancer un feu d'artifice à la position de l'île
        launchFirework(level, islandPos);

        // 4. Spawner le villageois marchand
        int extLevel = IslandExtensionManager.getLevel(playerId);
        if (extLevel < 1) return;  // pas encore de marchand avant le niveau 1

        Villager trader = new Villager(EntityType.VILLAGER, level,
            VillagerType.PLAINS);
        trader.setVillagerData(trader.getVillagerData()
            .setProfession(VillagerProfession.CARTOGRAPHER)
            .setLevel(5));

        double spawnX = islandPos.getX() + 1.5;
        double spawnY = islandPos.getY() + 1.0;
        double spawnZ = islandPos.getZ() + 0.5;

        trader.setPos(spawnX, spawnY, spawnZ);
        trader.setPersistenceRequired(true);
        trader.setCustomName(Component.literal("§6Marchand OneBlock"));
        trader.setCustomNameVisible(true);
        trader.setNoAi(true);   // reste immobile

        // Remplace les trades par défaut
        trader.setCustomOffers(buildTrades(extLevel));

        level.addFreshEntity(trader);
        UUID traderUUID = trader.getUUID();
        activeTraders.put(playerId, traderUUID);
        managedTraderIds.add(traderUUID);

        player.sendSystemMessage(Component.literal(
            "§6✦ §eLe marchand est arrivé sur ton île ! §6✦"
        ));

        OneBlockMod.LOGGER.info("[OneBlock] Marchand spawné pour {} (ext. niveau {})", playerId, extLevel);
    }

    // ─── Construction des trades ─────────────────────────────────────────────

    private static MerchantOffers buildTrades(int level) {
        MerchantOffers offers = new MerchantOffers();

        // ── Niveau 1+ ────────────────────────────────────────────────────────
        if (level >= 1) {
            // Blocs utiles
            offers.add(trade(stack(Items.DIRT, 10),   stack(Items.ICE, 4)));
            offers.add(trade(stack(Items.DIRT, 15),   stack(Items.GRASS_BLOCK, 8)));
            // Mobs
            offers.add(mobTrade(stack(Items.DIRT, 15),    "minecraft:cow"));
            offers.add(mobTrade(stack(Items.DIRT, 10),    "minecraft:sheep"));
            offers.add(mobTrade(stack(Items.DIRT, 20),    "minecraft:pig"));
            offers.add(mobTrade(stack(Items.DIRT, 25),    "minecraft:chicken"));
            offers.add(mobTrade(stack(Items.GRAVEL, 8),   "minecraft:rabbit"));
        }

        // ── Niveau 2+ ────────────────────────────────────────────────────────
        if (level >= 2) {
            offers.add(mobTrade(stack(Items.OAK_LOG, 5),   "minecraft:horse"));
            offers.add(mobTrade(stack(Items.OAK_LOG, 3),   "minecraft:donkey"));
            offers.add(mobTrade(stack(Items.OAK_LOG, 8),   "minecraft:fox"));
        }

        // ── Niveau 3+ ────────────────────────────────────────────────────────
        if (level >= 3) {
            offers.add(mobTrade(stack(Items.COAL_ORE, 3),  "minecraft:cat"));
            offers.add(trade(stack(Items.IRON_ORE, 5),     stack(Items.IRON_PICKAXE, 1)));
        }

        // ── Niveau 4+ ────────────────────────────────────────────────────────
        if (level >= 4) {
            offers.add(mobTrade(stack(Items.OAK_LOG, 12),  "minecraft:cat"));
            offers.add(trade(stack(Items.OAK_LOG, 15),     stack(Items.WHEAT_SEEDS, 16)));
        }

        // ── Niveau 5+ ────────────────────────────────────────────────────────
        if (level >= 5) {
            offers.add(mobTrade(stack(Items.ICE, 10),        "minecraft:polar_bear"));
            offers.add(mobTrade(stack(Items.SNOW_BLOCK, 8),  "minecraft:wolf"));
        }

        // ── Niveau 6+ ────────────────────────────────────────────────────────
        if (level >= 6) {
            offers.add(mobTrade(stack(Items.PRISMARINE, 5),  "minecraft:turtle"));
            offers.add(mobTrade(stack(Items.SPONGE, 3),      "minecraft:axolotl"));
            offers.add(trade(stack(Items.SPRUCE_LOG, 15),    stack(Items.IRON_PICKAXE, 1)));
        }

        // ── Niveau 7+ ────────────────────────────────────────────────────────
        if (level >= 7) {
            offers.add(mobTrade(stack(Items.JUNGLE_LOG, 5),  "minecraft:panda"));
            offers.add(mobTrade(stack(Items.BAMBOO, 8),      "minecraft:parrot"));
            offers.add(mobTrade(stack(Items.MELON, 3),       "minecraft:ocelot"));
            offers.add(trade(stack(Items.CLAY, 10),          stack(Items.ENCHANTED_BOOK, 1)));
        }

        // ── Niveau 8+ ────────────────────────────────────────────────────────
        if (level >= 8) {
            offers.add(mobTrade(stack(Items.LILY_PAD, 6),    "minecraft:frog"));
            offers.add(mobTrade(stack(Items.MOSS_BLOCK, 10), "minecraft:bee"));
            offers.add(trade(stack(Items.EMERALD_ORE, 2),    stack(Items.SADDLE, 1)));
        }

        // ── Niveau 9+ ────────────────────────────────────────────────────────
        if (level >= 9) {
            offers.add(mobTrade(stack(Items.IRON_BLOCK, 2),  "minecraft:llama"));
            offers.add(mobTrade(stack(Items.GOLD_BLOCK, 2),  "minecraft:goat"));
        }

        // ── Niveau 10+ ───────────────────────────────────────────────────────
        if (level >= 10) {
            offers.add(trade(stack(Items.NETHER_QUARTZ_ORE, 5), stack(Items.ENCHANTING_TABLE, 1)));
            offers.add(trade(stack(Items.GOLD_BLOCK, 3),         stack(Items.GOLDEN_CHESTPLATE, 1)));
        }

        // ── Niveau 11 ────────────────────────────────────────────────────────
        if (level >= 11) {
            offers.add(trade(stack(Items.PURPUR_BLOCK, 3),   stack(Items.DIAMOND_SWORD, 1)));
            offers.add(trade(stack(Items.DIAMOND_BLOCK, 1),  stack(Items.ELYTRA, 1)));
        }

        return offers;
    }

    // ─── Enregistrement d'achat de mob ───────────────────────────────────────

    /**
     * Appelé quand un joueur achète un "bon de mob" auprès de notre marchand.
     * Ajoute le mob à la file d'attente (il spawnera au prochain marchand).
     */
    public static void queueMobForPlayer(UUID playerId, String mobKey, MinecraftServer server) {
        pendingMobs.computeIfAbsent(playerId, k -> new ArrayList<>()).add(mobKey);
        savePendingMobs(playerId, server);
        OneBlockMod.LOGGER.info("[OneBlock] Mob en attente pour {} : {}", playerId, mobKey);
    }

    /** Renvoie la mob key encodée dans un item "bon", ou null. */
    public static String getMobKeyFromItem(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.PAPER) return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("OneBlockMob")) return null;
        return tag.getString("OneBlockMob");
    }

    // ─── Helpers de construction de trades ───────────────────────────────────

    /** Trade simple : itemIn → itemOut, 1 utilisation, 0 xp. */
    private static MerchantOffer trade(ItemStack in, ItemStack out) {
        return new MerchantOffer(in, out, 1, 0, 1.0f);
    }

    /**
     * Trade de mob : donne un item, reçoit un "bon de mob" (papier avec NBT).
     * Le mob spawnera au prochain marchand.
     */
    @SuppressWarnings("deprecation")
    private static MerchantOffer mobTrade(ItemStack cost, String mobKey) {
        ItemStack ticket = new ItemStack(Items.PAPER);
        ticket.setHoverName(Component.literal("§6Bon de livraison : §f" + mobDisplayName(mobKey)));

        // Encode le mob key dans le tag NBT (API legacy encore disponible en 1.21.x Forge)
        CompoundTag nbt = ticket.getOrCreateTag();
        nbt.putString("OneBlockMob", mobKey);

        return new MerchantOffer(cost, ticket, 1, 0, 1.0f);
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        return new ItemStack(item, count);
    }

    private static String mobDisplayName(String mobKey) {
        // Ex: "minecraft:cow" → "Cow"
        String name = mobKey.contains(":") ? mobKey.split(":")[1] : mobKey;
        return name.substring(0, 1).toUpperCase() + name.replace("_", " ").substring(1);
    }

    // ─── Spawn de mobs ────────────────────────────────────────────────────────

    private static void spawnMob(String mobKey, ServerLevel level, BlockPos nearPos) {
        try {
            Optional<EntityType<?>> typeOpt = EntityType.byString(mobKey);
            if (typeOpt.isEmpty()) {
                OneBlockMod.LOGGER.warn("[OneBlock] Type de mob inconnu : {}", mobKey);
                return;
            }
            net.minecraft.world.entity.Entity mob = typeOpt.get().create(level);
            if (mob == null) return;

            double x = nearPos.getX() + (Math.random() * 4 - 2);
            double y = nearPos.getY() + 1.0;
            double z = nearPos.getZ() + (Math.random() * 4 - 2);
            mob.setPos(x, y, z);

            level.addFreshEntity(mob);
        } catch (Exception e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur spawn mob {}: {}", mobKey, e.getMessage());
        }
    }

    // ─── Feu d'artifice ──────────────────────────────────────────────────────

    private static void launchFirework(ServerLevel level, BlockPos pos) {
        ItemStack fireworkItem = new ItemStack(Items.FIREWORK_ROCKET);
        CompoundTag itemTag  = fireworkItem.getOrCreateTag();
        CompoundTag fireworks = new CompoundTag();
        fireworks.putByte("Flight", (byte) 1);

        ListTag explosions = new ListTag();
        CompoundTag explosion = new CompoundTag();
        explosion.putByte("Type", (byte) 0);
        explosion.putIntArray("Colors", new int[]{0xFFD700, 0xFF8C00});  // or, orange
        explosions.add(explosion);
        fireworks.put("Explosions", explosions);
        itemTag.put("Fireworks", fireworks);

        net.minecraft.world.entity.projectile.FireworkRocketEntity firework =
            new net.minecraft.world.entity.projectile.FireworkRocketEntity(
                level,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                fireworkItem
            );
        level.addFreshEntity(firework);
    }

    // ─── Persistence mobs en attente ─────────────────────────────────────────

    private static void savePendingMobs(UUID playerId, MinecraftServer server) {
        try {
            File dir = getSaveDir(server);
            dir.mkdirs();
            Path path = dir.toPath().resolve(playerId + "_pending.dat");
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (String mob : pendingMobs.getOrDefault(playerId, List.of())) {
                list.add(StringTag.valueOf(mob));
            }
            tag.put("PendingMobs", list);
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde mobs en attente {}: {}", playerId, e.getMessage());
        }
    }

    private static void loadPendingMobs(UUID playerId, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(playerId + "_pending.dat");
            if (!path.toFile().exists()) return;
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ListTag list = tag.getList("PendingMobs", 8); // 8 = StringTag
            List<String> mobs = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                mobs.add(list.getString(i));
            }
            if (!mobs.isEmpty()) pendingMobs.put(playerId, mobs);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement mobs en attente {}: {}", playerId, e.getMessage());
        }
    }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }
}
