package com.oneblock.mod.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.achievement.AchievementManager;
import com.oneblock.mod.boss.BossManager;
import com.oneblock.mod.challenge.ChallengeManager;
import com.oneblock.mod.cosmetic.CosmeticManager;
import com.oneblock.mod.data.CollectionTracker;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.economy.AuctionManager;
import com.oneblock.mod.economy.CoinManager;
import com.oneblock.mod.economy.ShopMenu;
import com.oneblock.mod.prestige.PrestigeManager;
import com.oneblock.mod.trader.TraderManager;
import com.oneblock.mod.world.OneBlockPhase;
import com.oneblock.mod.world.OneBlockWorldGen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = OneBlockMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class OneBlockCommands {

    /** Cooldown /spawn : dernier usage en ms (System.currentTimeMillis). */
    private static final Map<UUID, Long> spawnCooldowns = new ConcurrentHashMap<>();
    private static final long SPAWN_COOLDOWN_MS = 30_000L; // 30 secondes

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // ── /oneblock ─────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("oneblock")

                .then(Commands.literal("stats")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        PlayerDataManager.PlayerOneBlockData data =
                            PlayerDataManager.getOrCreate(player.getUUID(), ctx.getSource().getServer());
                        OneBlockWorldGen.sendPlayerStats(player, data);
                        return 1;
                    }))

                .then(Commands.literal("tp")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        PlayerDataManager.PlayerOneBlockData data =
                            PlayerDataManager.getOrCreate(player.getUUID(), ctx.getSource().getServer());
                        ServerLevel level = ctx.getSource().getServer().overworld();
                        player.teleportTo(level,
                            data.blockPos.getX() + 0.5,
                            data.blockPos.getY() + 1.1,
                            data.blockPos.getZ() + 0.5,
                            Set.of(), player.getYRot(), player.getXRot(), true);
                        player.sendSystemMessage(Component.literal("§aTéléporté à ton bloc !"));
                        return 1;
                    }))

                .then(Commands.literal("phase")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        PlayerDataManager.PlayerOneBlockData data =
                            PlayerDataManager.getOrCreate(player.getUUID(), ctx.getSource().getServer());
                        player.sendSystemMessage(Component.literal(
                            "§7Phase : " + data.getCurrentPhase().displayName
                            + " §7| Blocs : §f" + data.blocksBroken));
                        return 1;
                    }))

                // /oneblock challenges
                .then(Commands.literal("challenges")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ChallengeManager.showChallenges(player);
                        return 1;
                    }))

                // /oneblock collection
                .then(Commands.literal("collection")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        PlayerDataManager.PlayerOneBlockData data =
                            PlayerDataManager.getOrCreate(player.getUUID(), ctx.getSource().getServer());
                        CollectionTracker.showCollection(player, data);
                        return 1;
                    }))

                // /oneblock top
                .then(Commands.literal("top")
                    .executes(ctx -> {
                        MinecraftServer server = ctx.getSource().getServer();
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        showLeaderboard(player, server);
                        return 1;
                    }))

                // /oneblock moveisland <joueur>  — OP uniquement
                .then(Commands.literal("moveisland")
                    .requires(src -> {
                        // Console a tous les droits ; joueur → vérifie isOp via PlayerList
                        ServerPlayer p = src.getPlayer();
                        return p == null || src.getServer().getPlayerList().isOp(p.nameAndId());
                    })
                    .then(Commands.argument("joueur", StringArgumentType.word())
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            String targetName = StringArgumentType.getString(ctx, "joueur");

                            // Le joueur cible doit être connecté
                            ServerPlayer target = server.getPlayerList().getPlayerByName(targetName);
                            if (target == null) {
                                ctx.getSource().sendFailure(Component.literal(
                                    "§cLe joueur §f" + targetName + " §cdoit être connecté pour déplacer son île."));
                                return 0;
                            }
                            UUID targetId = target.getUUID();

                            PlayerDataManager.PlayerOneBlockData data =
                                PlayerDataManager.getOrCreate(targetId, server);
                            BlockPos oldPos = data.blockPos;

                            // Cherche une nouvelle position aléatoire loin de tout le monde
                            BlockPos newPos = PlayerDataManager.findRandomIslandPos(server);

                            ctx.getSource().sendSystemMessage(Component.literal(
                                "§7Déplacement de l'île de §f" + targetName + "§7…"));

                            // Copie les blocs (rayon 100)
                            ServerLevel level = server.overworld();
                            int moved = OneBlockWorldGen.moveIslandBlocks(level, oldPos, newPos, 100);

                            // Met à jour les données du joueur
                            PlayerDataManager.relocatePlayer(targetId, newPos, server);

                            // Téléporte le joueur à sa nouvelle île
                            target.teleportTo(level,
                                newPos.getX() + 0.5,
                                newPos.getY() + 1.1,
                                newPos.getZ() + 0.5,
                                Set.of(), target.getYRot(), target.getXRot(), true);
                            target.sendSystemMessage(Component.literal(
                                "§6Ton île a été déplacée par un administrateur."));

                            ctx.getSource().sendSystemMessage(Component.literal(
                                "§a✓ §f" + moved + " §7blocs déplacés. Nouvelle position : §f"
                                + newPos.getX() + ", " + newPos.getY() + ", " + newPos.getZ()));
                            return 1;
                        })))
        );

        // ── /spawn ────────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("spawn")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    MinecraftServer server = ctx.getSource().getServer();
                    UUID id = player.getUUID();
                    long now = System.currentTimeMillis();
                    long lastUse = spawnCooldowns.getOrDefault(id, 0L);
                    long remaining = (lastUse + SPAWN_COOLDOWN_MS - now) / 1000;

                    if (remaining > 0) {
                        player.sendSystemMessage(Component.literal(
                            "§cCooldown /spawn : §f" + remaining + "§c secondes restantes."));
                        return 0;
                    }

                    spawnCooldowns.put(id, now);
                    PlayerDataManager.PlayerOneBlockData data =
                        PlayerDataManager.getOrCreate(id, server);
                    player.teleportTo(server.overworld(),
                        data.blockPos.getX() + 0.5,
                        data.blockPos.getY() + 1.1,
                        data.blockPos.getZ() + 0.5,
                        Set.of(), player.getYRot(), player.getXRot(), true);
                    player.sendSystemMessage(Component.literal("§aTéléporté sur ton île."));
                    return 1;
                })
        );

        // ── /prestige ─────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("prestige")
                .executes(ctx -> {
                    PrestigeManager.doPrestige(ctx.getSource().getPlayerOrException());
                    return 1;
                })
        );

        // ── /shop ─────────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("shop")
                .executes(ctx -> {
                    ShopMenu.open(ctx.getSource().getPlayerOrException());
                    return 1;
                })
        );

        // ── /coins ────────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("coins")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.sendSystemMessage(Component.literal(
                        "§7Ton solde : §e" + CoinManager.getCoins(p.getUUID()) + " §6OneCoins ⬡"));
                    return 1;
                })
        );

        // ── /auction ──────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("auction")
                .then(Commands.literal("sell")
                    .then(Commands.argument("prix", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            AuctionManager.sell(p, IntegerArgumentType.getInteger(ctx, "prix"),
                                ctx.getSource().getServer());
                            return 1;
                        })))
                .then(Commands.literal("list")
                    .executes(ctx -> {
                        AuctionManager.list(ctx.getSource().getPlayerOrException());
                        return 1;
                    }))
                .then(Commands.literal("buy")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            AuctionManager.buy(p, IntegerArgumentType.getInteger(ctx, "id"),
                                ctx.getSource().getServer());
                            return 1;
                        })))
                .then(Commands.literal("cancel")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            AuctionManager.cancel(p, IntegerArgumentType.getInteger(ctx, "id"));
                            return 1;
                        })))
        );

        // ── /cosmetic ─────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("cosmetic")
                .then(Commands.literal("trail")
                    .executes(ctx -> {
                        CosmeticManager.toggleTrail(ctx.getSource().getPlayerOrException());
                        return 1;
                    }))
                .then(Commands.literal("title")
                    .executes(ctx -> {
                        CosmeticManager.toggleTitle(ctx.getSource().getPlayerOrException());
                        return 1;
                    }))
        );

        // ── /island ───────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("island")
                .then(Commands.literal("theme")
                    .then(Commands.argument("theme", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String theme = StringArgumentType.getString(ctx, "theme");
                            CosmeticManager.applyTheme(p, theme,
                                ctx.getSource().getServer().overworld());
                            return 1;
                        })))
        );

        // ── /succes ───────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("succes")
                .executes(ctx -> {
                    AchievementManager.showAchievements(ctx.getSource().getPlayerOrException());
                    return 1;
                })
        );

        // ── /boss ─────────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("boss")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    MinecraftServer srv = ctx.getSource().getServer();
                    BossManager.retryBoss(p, srv.overworld());
                    return 1;
                })
        );

        // ── /obhelp ───────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("obhelp")
                .executes(ctx -> {
                    openHelpBook(ctx.getSource().getPlayerOrException());
                    return 1;
                })
        );

        // ── /trader ───────────────────────────────────────────────────────────
        dispatcher.register(
            Commands.literal("trader")
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    TraderManager.openTraderGui(player);
                    return 1;
                })
                .then(Commands.literal("buy")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            TraderManager.processBuy(player, IntegerArgumentType.getInteger(ctx, "id"));
                            return 1;
                        })))
        );
    }

    // ─── Utilitaire : retrouver un UUID par nom dans les fichiers sauvegardés ────

    // ─── Leaderboard ─────────────────────────────────────────────────────────

    private static void showLeaderboard(ServerPlayer player, MinecraftServer server) {
        File saveDir = server.getServerDirectory().resolve("oneblock_data").toFile();
        if (!saveDir.exists()) {
            player.sendSystemMessage(Component.literal("§cAucune donnée disponible."));
            return;
        }

        // Lit tous les fichiers joueur (exclut global, _ext, _pending, _collection)
        Map<String, Integer> scores = new LinkedHashMap<>();
        File[] files = saveDir.listFiles((d, name) ->
            name.endsWith(".dat")
            && !name.equals("global.dat")
            && !name.contains("_ext")
            && !name.contains("_pending")
            && !name.contains("_collection")
        );

        if (files == null) { player.sendSystemMessage(Component.literal("§cErreur lecture.")); return; }

        for (File f : files) {
            try {
                CompoundTag tag = NbtIo.readCompressed(f.toPath(), NbtAccounter.unlimitedHeap());
                int broken = tag.getInt("BlocksBroken").orElse(0);
                // Essaie de récupérer le nom via UUID
                String uuidStr = f.getName().replace(".dat", "");
                String name;
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    net.minecraft.server.level.ServerPlayer online =
                        server.getPlayerList().getPlayer(uuid);
                    name = (online != null) ? online.getName().getString() : uuidStr.substring(0, 8) + "...";
                } catch (Exception e) {
                    name = uuidStr.substring(0, Math.min(8, uuidStr.length())) + "...";
                }
                scores.put(name, broken);
            } catch (Exception ignored) {}
        }

        // Trie par score décroissant, garde le top 10
        List<Map.Entry<String, Integer>> sorted = scores.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(10)
            .toList();

        player.sendSystemMessage(Component.literal("§6━━━━━ §eTop 10 OneBlock §6━━━━━"));
        String[] medals = {"§6#1", "§7#2", "§c#3"};
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Integer> e = sorted.get(i);
            String rank = i < medals.length ? medals[i] : "§8#" + (i + 1);
            player.sendSystemMessage(Component.literal(
                rank + " §f" + e.getKey() + " §8— §e" + e.getValue() + " §7blocs"));
        }
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    // ─── GUI d'aide ───────────────────────────────────────────────────────────

    private static void openHelpBook(ServerPlayer player) {
        // On ouvre un inventaire 6 lignes style encyclopédie
        // Chaque section = un item avec nom coloré, les infos dans le titre
        net.minecraft.world.SimpleContainer inv = new net.minecraft.world.SimpleContainer(54);

        // Fond verre gris
        ItemStack glass = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        glass.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal("§8 "));
        for (int i = 0; i < 54; i++) inv.setItem(i, glass.copy());

        // ── Section commandes ─────────────────────────────────────────────────
        setHelpItem(inv, 0,  Items.COMPASS,        "§e/spawn",              "§7Retour a ton ile (30s cooldown)");
        setHelpItem(inv, 1,  Items.EMERALD,         "§e/trader",             "§7Ouvre le marchand quotidien");
        setHelpItem(inv, 2,  Items.GOLD_INGOT,      "§e/shop",               "§7Boutique OneCoins");
        setHelpItem(inv, 3,  Items.GOLD_NUGGET,     "§e/coins",              "§7Voir ton solde de OneCoins");
        setHelpItem(inv, 4,  Items.NETHER_STAR,     "§e/prestige",           "§7Prestige (requis : phase End)");
        setHelpItem(inv, 5,  Items.DIAMOND,         "§e/succes",             "§7Voir tes succes deverouilles");
        setHelpItem(inv, 6,  Items.BOOK,            "§e/obhelp",             "§7Ce guide");

        setHelpItem(inv, 9,  Items.IRON_SWORD,      "§e/oneblock stats",     "§7Tes statistiques personnelles");
        setHelpItem(inv, 10, Items.GOLDEN_SWORD,    "§e/oneblock top",       "§7Classement des 10 meilleurs");
        setHelpItem(inv, 11, Items.PAPER,           "§e/oneblock challenges", "§7Defis du jour");
        setHelpItem(inv, 12, Items.CHEST,           "§e/oneblock collection","§7Blocs collectes dans ta phase");
        setHelpItem(inv, 13, Items.ITEM_FRAME,      "§e/auction sell <prix>","§7Mettre un item en vente");
        setHelpItem(inv, 14, Items.GOLD_BLOCK,      "§e/auction list",       "§7Lister les encheres actives");
        setHelpItem(inv, 15, Items.DIAMOND_BLOCK,   "§e/auction buy <id>",   "§7Acheter une enchere");
        setHelpItem(inv, 16, Items.BARRIER,         "§e/auction cancel <id>","§7Annuler ta vente");

        setHelpItem(inv, 18, Items.BLAZE_POWDER,    "§e/cosmetic trail",     "§7Particules autour de toi");
        setHelpItem(inv, 19, Items.NAME_TAG,        "§e/cosmetic title",     "§7Titre au-dessus de la tete");
        setHelpItem(inv, 20, Items.OAK_SAPLING,     "§e/island theme plains","§7Theme plaines");
        setHelpItem(inv, 21, Items.SNOW_BLOCK,      "§e/island theme winter","§7Theme hiver + neige sur l'ile");
        setHelpItem(inv, 22, Items.PRISMARINE,      "§e/island theme ocean", "§7Theme ocean");
        setHelpItem(inv, 23, Items.NETHERRACK,      "§e/island theme nether","§7Theme nether");
        setHelpItem(inv, 24, Items.END_STONE,       "§e/island theme end",   "§7Theme the end");
        setHelpItem(inv, 25, Items.JUNGLE_LOG,      "§e/island theme jungle","§7Theme jungle");

        // ── Section phases OneBlock ───────────────────────────────────────────
        String[] majorNames = {"Plains","Underground","Winter","Ocean","Jungle",
                               "Swamp","Dungeon","Desert","Nether","Plenty","End"};
        int[]    majorStart  = {0,700,1700,2700,3700,4000,5000,6000,7000,7500,8500};
        net.minecraft.world.item.Item[] majorIcons = {
            Items.GRASS_BLOCK, Items.STONE, Items.SNOW_BLOCK, Items.PRISMARINE,
            Items.JUNGLE_LOG,  Items.LILY_PAD, Items.MOSSY_COBBLESTONE, Items.SAND,
            Items.NETHERRACK,  Items.DIAMOND_BLOCK, Items.END_STONE
        };

        for (int i = 0; i < majorNames.length && (27 + i) < 54; i++) {
            StringBuilder blocks = new StringBuilder();
            for (OneBlockPhase phase : OneBlockPhase.values()) {
                if (phase.getMajorPhaseIndex() == i) {
                    for (Block b : phase.getNewBlocksList()) {
                        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(b).getPath().replace("_", " ");
                        if (blocks.length() > 0) blocks.append(", ");
                        blocks.append(name);
                    }
                }
            }
            setHelpItemMultiline(inv, 27 + i, majorIcons[i],
                "§6Phase " + (i+1) + " : " + majorNames[i] + " §8(" + majorStart[i] + " blocs)",
                blocks.toString()
            );
        }

        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
            (id, playerInv, p) -> new net.minecraft.world.inventory.AbstractContainerMenu(
                    net.minecraft.world.inventory.MenuType.GENERIC_9x6, id) {
                { /* slots */
                    for (int r = 0; r < 6; r++) for (int c = 0; c < 9; c++) {
                        final int idx = c + r * 9;
                        addSlot(new net.minecraft.world.inventory.Slot(inv, idx, 8+c*18, 18+r*18) {
                            @Override public boolean mayPickup(net.minecraft.world.entity.player.Player pl) { return false; }
                            @Override public boolean mayPlace(ItemStack s) { return false; }
                        });
                    }
                    for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++)
                        addSlot(new net.minecraft.world.inventory.Slot(playerInv, c+r*9+9, 8+c*18, 139+r*18));
                    for (int c = 0; c < 9; c++)
                        addSlot(new net.minecraft.world.inventory.Slot(playerInv, c, 8+c*18, 197));
                }
                @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player pl, int i) { return ItemStack.EMPTY; }
                @Override public boolean stillValid(net.minecraft.world.entity.player.Player pl) { return true; }
            },
            Component.literal("§6✦ Guide OneBlock §6✦")
        ));
    }

    private static void setHelpItem(net.minecraft.world.SimpleContainer inv, int slot,
                                     net.minecraft.world.item.Item icon,
                                     String name, String desc) {
        ItemStack item = new ItemStack(icon);
        item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name));
        item.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(
                List.of(Component.literal(desc)), List.of()));
        inv.setItem(slot, item);
    }

    private static void setHelpItemMultiline(net.minecraft.world.SimpleContainer inv, int slot,
                                              net.minecraft.world.item.Item icon, String name, String desc) {
        ItemStack item = new ItemStack(icon);
        item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name));
        // Découpe la description en lignes de ~40 chars
        List<Component> lore = new ArrayList<>();
        String[] words = desc.split(", ");
        StringBuilder line = new StringBuilder("§7");
        for (String w : words) {
            if (line.length() + w.length() > 42) {
                lore.add(Component.literal(line.toString()));
                line = new StringBuilder("§7");
            }
            if (!line.toString().equals("§7")) line.append(", ");
            line.append(w);
        }
        if (!line.toString().equals("§7")) lore.add(Component.literal(line.toString()));
        item.set(net.minecraft.core.component.DataComponents.LORE,
            new net.minecraft.world.item.component.ItemLore(lore, List.of()));
        inv.setItem(slot, item);
    }
}
