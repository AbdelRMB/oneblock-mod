package com.oneblock.mod.data;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.world.OneBlockPhase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suit quels blocs le joueur a déjà obtenus via son OneBlock.
 * Enregistre le registre key du bloc (ex : "minecraft:stone") à chaque break.
 * /oneblock collection affiche le % de completion de la phase actuelle.
 */
public class CollectionTracker {

    /** Blocks vus par joueur (clé = registry name). */
    private static final Map<UUID, Set<String>> seen = new ConcurrentHashMap<>();

    // ─── Enregistrement ──────────────────────────────────────────────────────

    public static void recordBlock(UUID playerId, Block block, MinecraftServer server) {
        String key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
        boolean isNew = seen
            .computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
            .add(key);

        if (isNew) saveToDisk(playerId, server);
    }

    // ─── Affichage ───────────────────────────────────────────────────────────

    public static void showCollection(ServerPlayer player, PlayerDataManager.PlayerOneBlockData data) {
        UUID id = player.getUUID();
        Set<String> playerSeen = seen.getOrDefault(id, Set.of());

        OneBlockPhase current = data.getCurrentPhase();

        // Recense tous les blocs disponibles jusqu'à la phase actuelle
        List<String> allAvailable = new ArrayList<>();
        for (OneBlockPhase phase : OneBlockPhase.values()) {
            for (Block b : phase.getNewBlocksList()) {
                String key = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString();
                if (!allAvailable.contains(key)) allAvailable.add(key);
            }
            if (phase == current) break;
        }

        int total  = allAvailable.size();
        int found  = (int) allAvailable.stream().filter(playerSeen::contains).count();
        float pct  = total == 0 ? 100f : (found * 100f / total);

        player.sendSystemMessage(Component.literal("§6━━━ §eCollection OneBlock §6━━━"));
        player.sendSystemMessage(Component.literal(
            "§7Blocs trouvés : §f" + found + " §7/ §f" + total
            + " §8(§e" + String.format("%.0f", pct) + "%§8)"));

        // Liste les blocs manquants (jusqu'à 10)
        List<String> missing = allAvailable.stream()
            .filter(k -> !playerSeen.contains(k))
            .limit(10)
            .toList();

        if (!missing.isEmpty()) {
            player.sendSystemMessage(Component.literal("§7Blocs non obtenus :"));
            for (String k : missing) {
                String name = k.contains(":") ? k.split(":")[1].replace("_", " ") : k;
                player.sendSystemMessage(Component.literal("  §c✗ §7" + name));
            }
            if (allAvailable.stream().filter(k -> !playerSeen.contains(k)).count() > 10) {
                player.sendSystemMessage(Component.literal("  §8... et plus"));
            }
        } else {
            player.sendSystemMessage(Component.literal("§a§lCollection complète ! ✦"));
        }
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    public static void saveToDisk(UUID id, MinecraftServer server) {
        try {
            File dir = getSaveDir(server);
            dir.mkdirs();
            Path path = dir.toPath().resolve(id + "_collection.dat");
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (String key : seen.getOrDefault(id, Set.of()))
                list.add(StringTag.valueOf(key));
            tag.put("Seen", list);
            NbtIo.writeCompressed(tag, path);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur sauvegarde collection {}: {}", id, e.getMessage());
        }
    }

    public static void loadFromDisk(UUID id, MinecraftServer server) {
        try {
            Path path = getSaveDir(server).toPath().resolve(id + "_collection.dat");
            if (!path.toFile().exists()) return;
            CompoundTag tag = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            ListTag list = tag.getList("Seen").orElse(new ListTag());
            Set<String> set = ConcurrentHashMap.newKeySet();
            for (int i = 0; i < list.size(); i++)
                list.getString(i).ifPresent(set::add);
            seen.put(id, set);
        } catch (IOException e) {
            OneBlockMod.LOGGER.error("[OneBlock] Erreur chargement collection {}: {}", id, e.getMessage());
        }
    }

    public static void clearCache(UUID id) { seen.remove(id); }

    private static File getSaveDir(MinecraftServer server) {
        return server.getServerDirectory().resolve("oneblock_data").toFile();
    }
}
