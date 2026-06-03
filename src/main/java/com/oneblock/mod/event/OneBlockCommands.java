package com.oneblock.mod.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.challenge.ChallengeManager;
import com.oneblock.mod.data.CollectionTracker;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.trader.TraderManager;
import com.oneblock.mod.world.OneBlockWorldGen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
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
}
