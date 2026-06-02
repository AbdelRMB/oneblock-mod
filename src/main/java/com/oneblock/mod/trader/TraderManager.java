package com.oneblock.mod.trader;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.data.IslandExtensionManager;
import com.oneblock.mod.data.PlayerDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TraderManager {

    private static final Map<UUID, Long>         lastTraderDay = new ConcurrentHashMap<>();
    private static final Map<UUID, List<String>> pendingMobs   = new ConcurrentHashMap<>();

    // ─── Trade entry ─────────────────────────────────────────────────────────

    public record TradeEntry(
        int id,
        String label,
        ItemStack cost,
        String mobKey,        // null si pas un trade de mob
        ItemStack itemResult  // null si trade de mob
    ) {}

    // ─── Cycle journalier ────────────────────────────────────────────────────

    public static void tickForPlayer(ServerPlayer player, ServerLevel level) {
        UUID playerId   = player.getUUID();
        long currentDay = level.getGameTime() / 24000L;
        long lastDay    = lastTraderDay.getOrDefault(playerId, -1L);
        if (currentDay > lastDay) {
            lastTraderDay.put(playerId, currentDay);
            onNewDay(player, level);
        }
    }

    public static void onPlayerLogin(ServerPlayer player, MinecraftServer server) {
        loadPendingMobs(player.getUUID(), server);
    }

    public static void onPlayerLogout(UUID playerId, MinecraftServer server) {
        savePendingMobs(playerId, server);
        lastTraderDay.remove(playerId);
    }

    // ─── Nouveau jour ────────────────────────────────────────────────────────

    private static void onNewDay(ServerPlayer player, ServerLevel level) {
        UUID playerId = player.getUUID();
        int extLevel  = IslandExtensionManager.getLevel(playerId);

        // Spawn les mobs commandés la veille
        List<String> pending = pendingMobs.remove(playerId);
        if (pending != null && !pending.isEmpty()) {
            PlayerDataManager.PlayerOneBlockData data =
                PlayerDataManager.getOrCreate(playerId, level.getServer());
            for (String mobKey : pending) spawnMob(mobKey, level, data.blockPos);
            savePendingMobs(playerId, level.getServer());
        }

        if (extLevel < 1) return;

        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendSystemMessage(Component.literal("  §e✦ §6Le Marchand OneBlock est arrivé ! §e✦"));
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendSystemMessage(Component.literal("  §aTapez §f/trader §apour voir les offres."));
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendSystemMessage(Component.literal(""));
    }

    // ─── GUI inventaire ──────────────────────────────────────────────────────

    public static void openTraderGui(ServerPlayer player) {
        UUID playerId = player.getUUID();
        int extLevel  = IslandExtensionManager.getLevel(playerId);

        if (extLevel < 1) {
            player.sendSystemMessage(Component.literal(
                "§cMarchand non disponible (niveau d'extension 1 requis)."));
            return;
        }

        List<TradeEntry> trades = buildTrades(extLevel);
        SimpleContainer inv    = new SimpleContainer(TraderMenu.TRADER_SIZE);

        // Fond verre gris
        ItemStack glass = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        glass.set(DataComponents.CUSTOM_NAME, Component.literal("§8 "));
        for (int i = 0; i < TraderMenu.TRADER_SIZE; i++) inv.setItem(i, glass.copy());

        // Offres
        for (int i = 0; i < Math.min(trades.size(), TraderMenu.TRADER_SIZE); i++) {
            inv.setItem(i, buildDisplayItem(trades.get(i)));
        }

        player.openMenu(new SimpleMenuProvider(
            (id, playerInv, p) -> new TraderMenu(id, playerInv, inv, trades),
            Component.literal("§6✦ Marchand OneBlock §6✦")
        ));
    }

    public static ItemStack buildDisplayItemPublic(TradeEntry entry) {
        return buildDisplayItem(entry);
    }

    private static ItemStack buildDisplayItem(TradeEntry entry) {
        ItemStack display = entry.mobKey() != null
            ? new ItemStack(Items.EGG)
            : entry.itemResult().copy();

        String costDesc = entry.cost().getCount() + "x "
            + Component.translatable(entry.cost().getItem().getDescriptionId()).getString();

        String label = "§e" + entry.label()
            + " §8| §7Cout : §f" + costDesc
            + (entry.mobKey() != null ? "  §8(livre demain)" : "");

        display.set(DataComponents.CUSTOM_NAME, Component.literal(label));
        return display;
    }

    // ─── Achat ───────────────────────────────────────────────────────────────

    public static void processBuy(ServerPlayer player, int tradeId) {
        UUID playerId  = player.getUUID();
        int extLevel   = IslandExtensionManager.getLevel(playerId);
        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;

        if (extLevel < 1) {
            player.sendSystemMessage(Component.literal("§cMarchand non disponible."));
            return;
        }

        List<TradeEntry> trades = buildTrades(extLevel);
        Optional<TradeEntry> opt = trades.stream().filter(t -> t.id() == tradeId).findFirst();
        if (opt.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cOffre introuvable."));
            return;
        }

        TradeEntry entry = opt.get();
        ItemStack cost   = entry.cost();

        // Vérifie l'inventaire
        int invSize = player.getInventory().getContainerSize();
        int found = 0;
        for (int i = 0; i < invSize; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() == cost.getItem()) found += s.getCount();
        }
        if (found < cost.getCount()) {
            String costName = Component.translatable(cost.getItem().getDescriptionId()).getString();
            player.sendSystemMessage(Component.literal(
                "§cPas assez de §f" + costName + " §c(besoin : " + cost.getCount() + ", tu as : " + found + ")."));
            return;
        }

        // Retire les items
        int toRemove = cost.getCount();
        for (int i = 0; i < invSize && toRemove > 0; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (s.getItem() == cost.getItem()) {
                int take = Math.min(s.getCount(), toRemove);
                s.shrink(take);
                toRemove -= take;
            }
        }

        // Résultat
        if (entry.mobKey() != null) {
            pendingMobs.computeIfAbsent(playerId, k -> new ArrayList<>()).add(entry.mobKey());
            savePendingMobs(playerId, server);
            player.sendSystemMessage(Component.literal(
                "§a✓ §f" + entry.label() + " §7sera livre demain."));
        } else if (entry.itemResult() != null) {
            player.getInventory().add(entry.itemResult().copy());
            String resultName = Component.translatable(
                entry.itemResult().getItem().getDescriptionId()).getString();
            player.sendSystemMessage(Component.literal(
                "§a✓ §fRecu : §e" + entry.itemResult().getCount() + "x " + resultName));
        }
    }

    // ─── Trades par niveau ───────────────────────────────────────────────────

    public static List<TradeEntry> buildTrades(int level) {
        List<TradeEntry> list = new ArrayList<>();
        int id = 1;

        if (level >= 1) {
            list.add(mob(id++, "Vache",       stack(Items.DIRT, 15),   "minecraft:cow"));
            list.add(mob(id++, "Mouton",      stack(Items.DIRT, 10),   "minecraft:sheep"));
            list.add(mob(id++, "Cochon",      stack(Items.DIRT, 20),   "minecraft:pig"));
            list.add(mob(id++, "Poulet",      stack(Items.DIRT, 25),   "minecraft:chicken"));
            list.add(mob(id++, "Lapin",       stack(Items.GRAVEL, 8),  "minecraft:rabbit"));
            list.add(item(id++, "Glace x4",   stack(Items.DIRT, 10),   stack(Items.ICE, 4)));
            list.add(item(id++, "Herbe x8",   stack(Items.DIRT, 15),   stack(Items.GRASS_BLOCK, 8)));
        }
        if (level >= 2) {
            list.add(mob(id++, "Cheval",      stack(Items.OAK_LOG, 5), "minecraft:horse"));
            list.add(mob(id++, "Ane",         stack(Items.OAK_LOG, 3), "minecraft:donkey"));
            list.add(mob(id++, "Renard",      stack(Items.OAK_LOG, 8), "minecraft:fox"));
        }
        if (level >= 3) {
            list.add(mob(id++, "Chat",        stack(Items.COAL_ORE, 3),  "minecraft:cat"));
            list.add(item(id++, "Pioche fer", stack(Items.IRON_ORE, 5),  stack(Items.IRON_PICKAXE, 1)));
        }
        if (level >= 4) {
            list.add(item(id++, "Graines x16", stack(Items.OAK_LOG, 15), stack(Items.WHEAT_SEEDS, 16)));
        }
        if (level >= 5) {
            list.add(mob(id++, "Ours polaire", stack(Items.ICE, 10),       "minecraft:polar_bear"));
            list.add(mob(id++, "Loup",         stack(Items.SNOW_BLOCK, 8), "minecraft:wolf"));
        }
        if (level >= 6) {
            list.add(mob(id++, "Tortue",      stack(Items.PRISMARINE, 5),  "minecraft:turtle"));
            list.add(mob(id++, "Axolotl",     stack(Items.SPONGE, 3),      "minecraft:axolotl"));
        }
        if (level >= 7) {
            list.add(mob(id++, "Panda",       stack(Items.JUNGLE_LOG, 5),  "minecraft:panda"));
            list.add(mob(id++, "Perroquet",   stack(Items.BAMBOO, 8),      "minecraft:parrot"));
            list.add(mob(id++, "Ocelot",      stack(Items.MELON, 3),       "minecraft:ocelot"));
        }
        if (level >= 8) {
            list.add(mob(id++, "Grenouille",  stack(Items.LILY_PAD, 6),    "minecraft:frog"));
            list.add(mob(id++, "Abeille",     stack(Items.MOSS_BLOCK, 10), "minecraft:bee"));
            list.add(item(id++, "Selle",      stack(Items.EMERALD_ORE, 2), stack(Items.SADDLE, 1)));
        }
        if (level >= 9) {
            list.add(mob(id++, "Lama",        stack(Items.IRON_BLOCK, 2),  "minecraft:llama"));
            list.add(mob(id++, "Chevre",      stack(Items.GOLD_BLOCK, 2),  "minecraft:goat"));
        }
        if (level >= 10) {
            list.add(item(id++, "Table enchant.", stack(Items.NETHER_QUARTZ_ORE, 5), stack(Items.ENCHANTING_TABLE, 1)));
            list.add(item(id++, "Plastron or",    stack(Items.GOLD_BLOCK, 3),         stack(Items.GOLDEN_CHESTPLATE, 1)));
        }
        if (level >= 11) {
            list.add(item(id++, "Epee diamant", stack(Items.PURPUR_BLOCK, 3),   stack(Items.DIAMOND_SWORD, 1)));
            list.add(item(id,   "Elytre",        stack(Items.DIAMOND_BLOCK, 1), stack(Items.ELYTRA, 1)));
        }

        return list;
    }

    private static TradeEntry mob(int id, String label, ItemStack cost, String mobKey) {
        return new TradeEntry(id, label, cost, mobKey, null);
    }
    private static TradeEntry item(int id, String label, ItemStack cost, ItemStack result) {
        return new TradeEntry(id, label, cost, null, result);
    }
    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        return new ItemStack(item, count);
    }

    // ─── Spawn mob ───────────────────────────────────────────────────────────

    private static void spawnMob(String mobKey, ServerLevel level, BlockPos nearPos) {
        try {
            Optional<EntityType<?>> typeOpt = EntityType.byString(mobKey);
            if (typeOpt.isEmpty()) { OneBlockMod.LOGGER.warn("[OneBlock] Mob inconnu : {}", mobKey); return; }

            net.minecraft.world.entity.Entity mob =
                typeOpt.get().create(level, EntitySpawnReason.NATURAL);
            if (mob == null) return;

            mob.setPos(
                nearPos.getX() + (Math.random() * 4 - 2),
                nearPos.getY() + 1.0,
                nearPos.getZ() + (Math.random() * 4 - 2)
            );
            level.addFreshEntity(mob);
        } catch (Exception e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur spawn mob {}: {}", mobKey, e.getMessage());
        }
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    private static void savePendingMobs(UUID playerId, MinecraftServer server) {
        try {
            File dir = getSaveDir(server);
            dir.mkdirs();
            Path path = dir.toPath().resolve(playerId + "_pending.dat");
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (String mob : pendingMobs.getOrDefault(playerId, List.of()))
                list.add(StringTag.valueOf(mob));
            tag.put("PendingMobs", list);
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde pending {}: {}", playerId, e.getMessage());
        }
    }

    private static void loadPendingMobs(UUID playerId, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(playerId + "_pending.dat");
            if (!path.toFile().exists()) return;
            CompoundTag tag  = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ListTag list      = tag.getList("PendingMobs").orElse(new ListTag());
            List<String> mobs = new ArrayList<>();
            for (int i = 0; i < list.size(); i++)
                list.getString(i).ifPresent(mobs::add);
            if (!mobs.isEmpty()) pendingMobs.put(playerId, mobs);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement pending {}: {}", playerId, e.getMessage());
        }
    }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }
}
