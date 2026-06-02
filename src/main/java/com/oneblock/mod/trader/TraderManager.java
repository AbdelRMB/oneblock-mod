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
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gère le marchand quotidien de chaque joueur.
 *
 * Le marchand est virtuel (pas d'entité spawnée) : quand un nouveau jour
 * commence, le joueur reçoit un message avec des liens cliquables pour
 * acheter. La commande /trader affiche l'interface de commerce.
 *
 * Acheter un mob → il rejoint la file d'attente → spawne le lendemain.
 */
public class TraderManager {

    /** Dernier jour Minecraft où le marchand est apparu. */
    private static final Map<UUID, Long> lastTraderDay = new ConcurrentHashMap<>();

    /** Mobs en attente de spawn (playerUUID → liste de clés EntityType). */
    private static final Map<UUID, List<String>> pendingMobs = new ConcurrentHashMap<>();

    // ─── Entrée de trade ─────────────────────────────────────────────────────

    /**
     * Décrit un échange proposé par le marchand.
     * Si mobKey != null, acheter ce trade ajoute le mob à la file de spawn.
     */
    public record TradeEntry(
        int id,
        String label,
        ItemStack cost,
        String mobKey,        // null si ce n'est pas un trade de mob
        ItemStack itemResult  // null si c'est un trade de mob
    ) {}

    // ─── Cycle journalier ────────────────────────────────────────────────────

    public static void tickForPlayer(ServerPlayer player, ServerLevel level) {
        UUID playerId    = player.getUUID();
        long currentDay  = level.getGameTime() / 24000L;
        long lastDay     = lastTraderDay.getOrDefault(playerId, -1L);

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
            for (String mobKey : pending) {
                spawnMob(mobKey, level, data.blockPos);
            }
            savePendingMobs(playerId, level.getServer());
        }

        if (extLevel < 1) return;   // marchand pas encore disponible

        // Annonce d'arrivée
        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.literal(
            "§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendSystemMessage(Component.literal(
            "  §e✦ §6Le Marchand OneBlock est arrivé ! §e✦"));
        player.sendSystemMessage(Component.literal(
            "§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));

        // Lien cliquable pour ouvrir le catalogue
        MutableComponent link = Component.literal("  §a[► Voir les offres du jour]")
            .withStyle(s -> s
                .withClickEvent(new ClickEvent(
                    ClickEvent.Action.RUN_COMMAND, "/trader"))
                .withHoverEvent(new HoverEvent(
                    HoverEvent.Action.SHOW_TEXT,
                    Component.literal("§7Cliquez pour afficher le catalogue"))));

        player.sendSystemMessage(link);
        player.sendSystemMessage(Component.literal(
            "§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendSystemMessage(Component.literal(""));
    }

    // ─── Ouverture du GUI inventaire (/trader) ───────────────────────────────

    /**
     * Ouvre un inventaire grand coffre (6 lignes) affichant les offres du marchand.
     * Cliquer sur un item achète l'offre correspondante.
     */
    public static void openTraderGui(ServerPlayer player) {
        UUID playerId = player.getUUID();
        int extLevel  = IslandExtensionManager.getLevel(playerId);

        if (extLevel < 1) {
            player.sendSystemMessage(Component.literal(
                "§cLe marchand n'est pas encore disponible (niveau d'extension 1 requis)."));
            return;
        }

        List<TradeEntry> trades = buildTrades(extLevel);
        SimpleContainer inv    = new SimpleContainer(TraderMenu.TRADER_SIZE);

        // Remplissage : verre gris comme fond
        ItemStack glass = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        glass.setHoverName(Component.literal("§8─────────────────"));
        for (int i = 0; i < TraderMenu.TRADER_SIZE; i++) {
            inv.setItem(i, glass.copy());
        }

        // Placement des offres (une par slot, dans l'ordre)
        for (int i = 0; i < Math.min(trades.size(), TraderMenu.TRADER_SIZE); i++) {
            inv.setItem(i, buildDisplayItem(trades.get(i)));
        }

        player.openMenu(new SimpleMenuProvider(
            (id, playerInv, p) -> new TraderMenu(id, playerInv, inv, trades),
            Component.literal("§6✦ Marchand OneBlock §6✦")
        ));
    }

    /** Construit l'item d'affichage pour une offre dans le GUI. */
    private static ItemStack buildDisplayItem(TradeEntry entry) {
        ItemStack display = entry.mobKey() != null
            ? new ItemStack(Items.EGG)           // mob → œuf comme placeholder
            : entry.itemResult().copy();         // item → l'item lui-même

        String costDesc = entry.cost().getCount() + "x "
            + entry.cost().getItem().getDescription().getString();

        String suffix = entry.mobKey() != null
            ? "§8(livré demain)"
            : "";

        display.setHoverName(Component.literal(
            "§e" + entry.label()
            + " §8| §7Coût : §f" + costDesc
            + (suffix.isEmpty() ? "" : "  " + suffix)
        ));

        return display;
    }

    // ─── Affichage du catalogue chat (/trader list) ──────────────────────────

    public static void showCatalogue(ServerPlayer player) {
        UUID playerId = player.getUUID();
        int extLevel  = IslandExtensionManager.getLevel(playerId);

        if (extLevel < 1) {
            player.sendSystemMessage(Component.literal(
                "§cLe marchand n'est pas encore disponible (niveau d'extension 1 requis)."));
            return;
        }

        List<TradeEntry> trades = buildTrades(extLevel);

        player.sendSystemMessage(Component.literal(""));
        player.sendSystemMessage(Component.literal(
            "§6━━━━━━ §eCatalogue du Marchand §6━━━━━━"));

        for (TradeEntry entry : trades) {
            String costDesc = entry.cost().getCount() + "x "
                + entry.cost().getItem().getDescription().getString();

            MutableComponent line = Component.literal(
                "§7#" + entry.id() + " §f" + entry.label()
                + " §8(coût : §7" + costDesc + "§8)  ");

            // Bouton [Acheter]
            MutableComponent btn = Component.literal("§a[Acheter]")
                .withStyle(s -> s
                    .withClickEvent(new ClickEvent(
                        ClickEvent.Action.RUN_COMMAND,
                        "/trader buy " + entry.id()))
                    .withHoverEvent(new HoverEvent(
                        HoverEvent.Action.SHOW_TEXT,
                        Component.literal("§7Donne §f" + costDesc
                            + "\n§7Reçoit : §f" + entry.label()
                            + (entry.mobKey() != null
                                ? "\n§8(le mob spawn demain à l'arrivée du marchand)"
                                : "")))));

            player.sendSystemMessage(line.append(btn));
        }

        player.sendSystemMessage(Component.literal(
            "§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
        player.sendSystemMessage(Component.literal(""));
    }

    // ─── Achat (/trader buy <id>) ─────────────────────────────────────────────

    public static void processBuy(ServerPlayer player, int tradeId) {
        UUID playerId = player.getUUID();
        int extLevel  = IslandExtensionManager.getLevel(playerId);
        MinecraftServer server = player.getServer();
        if (server == null) return;

        if (extLevel < 1) {
            player.sendSystemMessage(Component.literal("§cMarchand non disponible."));
            return;
        }

        List<TradeEntry> trades = buildTrades(extLevel);
        Optional<TradeEntry> opt = trades.stream()
            .filter(t -> t.id() == tradeId)
            .findFirst();

        if (opt.isEmpty()) {
            player.sendSystemMessage(Component.literal("§cOffre #" + tradeId + " introuvable."));
            return;
        }

        TradeEntry entry = opt.get();

        // Vérifie que le joueur a les items nécessaires
        ItemStack cost = entry.cost();
        if (!player.getInventory().contains(cost)) {
            player.sendSystemMessage(Component.literal(
                "§cTu n'as pas assez de §f"
                + cost.getItem().getDescription().getString()
                + " §c(x" + cost.getCount() + " requis)."));
            return;
        }

        // Retire les items du coût
        player.getInventory().clearOrCountMatchingItems(
            s -> s.getItem() == cost.getItem(), cost.getCount(),
            player.inventoryMenu.getCraftSlots());

        // Traite le résultat
        if (entry.mobKey() != null) {
            // Mob → file d'attente
            pendingMobs.computeIfAbsent(playerId, k -> new ArrayList<>())
                .add(entry.mobKey());
            savePendingMobs(playerId, server);
            player.sendSystemMessage(Component.literal(
                "§a✓ §f" + entry.label()
                + " §7sera livré demain à l'arrivée du marchand."));
        } else if (entry.itemResult() != null) {
            // Item → donné immédiatement
            player.getInventory().add(entry.itemResult().copy());
            player.sendSystemMessage(Component.literal(
                "§a✓ §fTu as reçu : §e" + entry.itemResult().getCount()
                + "x " + entry.itemResult().getItem().getDescription().getString()));
        }
    }

    // ─── Construction des trades par niveau ───────────────────────────────────

    public static List<TradeEntry> buildTrades(int level) {
        List<TradeEntry> list = new ArrayList<>();
        int id = 1;

        // Niveau 1+
        if (level >= 1) {
            list.add(mob(id++, "Vache",    stack(Items.DIRT, 15),   "minecraft:cow"));
            list.add(mob(id++, "Mouton",   stack(Items.DIRT, 10),   "minecraft:sheep"));
            list.add(mob(id++, "Cochon",   stack(Items.DIRT, 20),   "minecraft:pig"));
            list.add(mob(id++, "Poulet",   stack(Items.DIRT, 25),   "minecraft:chicken"));
            list.add(mob(id++, "Lapin",    stack(Items.GRAVEL, 8),  "minecraft:rabbit"));
            list.add(item(id++, "Glace x4",        stack(Items.DIRT, 10),       stack(Items.ICE, 4)));
            list.add(item(id++, "Herbe x8",        stack(Items.DIRT, 15),       stack(Items.GRASS_BLOCK, 8)));
        }

        // Niveau 2+
        if (level >= 2) {
            list.add(mob(id++, "Cheval",   stack(Items.OAK_LOG, 5), "minecraft:horse"));
            list.add(mob(id++, "Âne",      stack(Items.OAK_LOG, 3), "minecraft:donkey"));
            list.add(mob(id++, "Renard",   stack(Items.OAK_LOG, 8), "minecraft:fox"));
        }

        // Niveau 3+
        if (level >= 3) {
            list.add(mob(id++, "Chat",     stack(Items.COAL_ORE, 3),  "minecraft:cat"));
            list.add(item(id++, "Pioche en fer",   stack(Items.IRON_ORE, 5),    stack(Items.IRON_PICKAXE, 1)));
        }

        // Niveau 4+
        if (level >= 4) {
            list.add(item(id++, "Graines de blé x16", stack(Items.OAK_LOG, 15), stack(Items.WHEAT_SEEDS, 16)));
        }

        // Niveau 5+
        if (level >= 5) {
            list.add(mob(id++, "Ours polaire", stack(Items.ICE, 10),        "minecraft:polar_bear"));
            list.add(mob(id++, "Loup",         stack(Items.SNOW_BLOCK, 8),  "minecraft:wolf"));
        }

        // Niveau 6+
        if (level >= 6) {
            list.add(mob(id++, "Tortue",   stack(Items.PRISMARINE, 5), "minecraft:turtle"));
            list.add(mob(id++, "Axolotl",  stack(Items.SPONGE, 3),     "minecraft:axolotl"));
            list.add(item(id++, "Pioche en fer",  stack(Items.SPRUCE_LOG, 15), stack(Items.IRON_PICKAXE, 1)));
        }

        // Niveau 7+
        if (level >= 7) {
            list.add(mob(id++, "Panda",     stack(Items.JUNGLE_LOG, 5), "minecraft:panda"));
            list.add(mob(id++, "Perroquet", stack(Items.BAMBOO, 8),     "minecraft:parrot"));
            list.add(mob(id++, "Ocelot",    stack(Items.MELON, 3),      "minecraft:ocelot"));
        }

        // Niveau 8+
        if (level >= 8) {
            list.add(mob(id++, "Grenouille", stack(Items.LILY_PAD, 6),    "minecraft:frog"));
            list.add(mob(id++, "Abeille",    stack(Items.MOSS_BLOCK, 10), "minecraft:bee"));
            list.add(item(id++, "Selle",     stack(Items.EMERALD_ORE, 2), stack(Items.SADDLE, 1)));
        }

        // Niveau 9+
        if (level >= 9) {
            list.add(mob(id++, "Lama",   stack(Items.IRON_BLOCK, 2),  "minecraft:llama"));
            list.add(mob(id++, "Chèvre", stack(Items.GOLD_BLOCK, 2),  "minecraft:goat"));
        }

        // Niveau 10+
        if (level >= 10) {
            list.add(item(id++, "Table d'enchantement", stack(Items.NETHER_QUARTZ_ORE, 5), stack(Items.ENCHANTING_TABLE, 1)));
            list.add(item(id++, "Plastron en or",        stack(Items.GOLD_BLOCK, 3),         stack(Items.GOLDEN_CHESTPLATE, 1)));
        }

        // Niveau 11
        if (level >= 11) {
            list.add(item(id++, "Épée en diamant", stack(Items.PURPUR_BLOCK, 3),  stack(Items.DIAMOND_SWORD, 1)));
            list.add(item(id,   "Élytre",          stack(Items.DIAMOND_BLOCK, 1), stack(Items.ELYTRA, 1)));
        }

        return list;
    }

    // ─── Helpers trades ──────────────────────────────────────────────────────

    private static TradeEntry mob(int id, String label, ItemStack cost, String mobKey) {
        return new TradeEntry(id, label, cost, mobKey, null);
    }

    private static TradeEntry item(int id, String label, ItemStack cost, ItemStack result) {
        return new TradeEntry(id, label, cost, null, result);
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        return new ItemStack(item, count);
    }

    // ─── Spawn de mobs ────────────────────────────────────────────────────────

    private static void spawnMob(String mobKey, ServerLevel level, BlockPos nearPos) {
        try {
            Optional<EntityType<?>> typeOpt = EntityType.byString(mobKey);
            if (typeOpt.isEmpty()) {
                OneBlockMod.LOGGER.warn("[OneBlock] Mob inconnu : {}", mobKey);
                return;
            }
            net.minecraft.world.entity.Entity mob = typeOpt.get().create(level);
            if (mob == null) return;

            double x = nearPos.getX() + (Math.random() * 4 - 2);
            double z = nearPos.getZ() + (Math.random() * 4 - 2);
            mob.setPos(x, nearPos.getY() + 1.0, z);
            level.addFreshEntity(mob);

            OneBlockMod.LOGGER.info("[OneBlock] Mob spawné : {}", mobKey);
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
            for (String mob : pendingMobs.getOrDefault(playerId, List.of())) {
                list.add(StringTag.valueOf(mob));
            }
            tag.put("PendingMobs", list);
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde pending mobs {}: {}", playerId, e.getMessage());
        }
    }

    private static void loadPendingMobs(UUID playerId, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(playerId + "_pending.dat");
            if (!path.toFile().exists()) return;
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ListTag list = tag.getList("PendingMobs", 8);
            List<String> mobs = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) mobs.add(list.getString(i));
            if (!mobs.isEmpty()) pendingMobs.put(playerId, mobs);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement pending mobs {}: {}", playerId, e.getMessage());
        }
    }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }
}
