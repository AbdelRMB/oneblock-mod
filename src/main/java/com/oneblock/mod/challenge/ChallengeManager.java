package com.oneblock.mod.challenge;

import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.data.IslandExtensionManager;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.world.OneBlockPhase;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Génère deux challenges quotidiens par joueur :
 *   1. Casser X blocs OneBlock
 *   2. Poser X blocs sur l'île
 *
 * Les objectifs et récompenses s'adaptent à la phase et au niveau d'extension.
 * La progression est réinitialisée chaque nouveau jour Minecraft.
 */
public class ChallengeManager {

    public enum ChallengeType { BREAK_BLOCKS, PLACE_BLOCKS }

    public record Challenge(
        ChallengeType type,
        int target,
        String description,
        ItemStack reward
    ) {}

    // ─── État par joueur ─────────────────────────────────────────────────────

    private static final Map<UUID, Challenge>  breakChallenge = new ConcurrentHashMap<>();
    private static final Map<UUID, Challenge>  placeChallenge = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer>    breakProgress  = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer>    placeProgress  = new ConcurrentHashMap<>();
    private static final Map<UUID, Long>       challengeDay   = new ConcurrentHashMap<>();

    // ─── Accès principal ─────────────────────────────────────────────────────

    /** Appelé chaque tick pour vérifier si les challenges doivent être regénérés. */
    public static void tick(ServerPlayer player, long currentDay) {
        UUID id = player.getUUID();
        if (!challengeDay.containsKey(id) || challengeDay.get(id) != currentDay) {
            generateChallenges(player, currentDay);
        }
    }

    public static Challenge getBreakChallenge(UUID id)  { return breakChallenge.get(id); }
    public static Challenge getPlaceChallenge(UUID id)  { return placeChallenge.get(id); }
    public static int getBreakProgress(UUID id)         { return breakProgress.getOrDefault(id, 0); }
    public static int getPlaceProgress(UUID id)         { return placeProgress.getOrDefault(id, 0); }

    // ─── Progression ─────────────────────────────────────────────────────────

    /** Appeler quand le joueur casse son OneBlock. */
    public static void onBlockBroken(ServerPlayer player) {
        UUID id = player.getUUID();
        Challenge ch = breakChallenge.get(id);
        if (ch == null) return;

        int prog = breakProgress.merge(id, 1, Integer::sum);
        if (prog == ch.target()) {
            complete(player, ch);
        } else if (prog < ch.target() && prog % 10 == 0) {
            player.sendSystemMessage(Component.literal(
                "§e[Challenge] §f" + ch.description()
                + " §8(" + prog + "/" + ch.target() + ")"));
        }
    }

    /** Appeler quand le joueur pose un bloc sur son île. */
    public static void onBlockPlaced(ServerPlayer player) {
        UUID id = player.getUUID();
        Challenge ch = placeChallenge.get(id);
        if (ch == null) return;

        int prog = placeProgress.merge(id, 1, Integer::sum);
        if (prog == ch.target()) {
            complete(player, ch);
        } else if (prog < ch.target() && prog % 10 == 0) {
            player.sendSystemMessage(Component.literal(
                "§e[Challenge] §f" + ch.description()
                + " §8(" + prog + "/" + ch.target() + ")"));
        }
    }

    // ─── Génération ──────────────────────────────────────────────────────────

    private static void generateChallenges(ServerPlayer player, long day) {
        UUID id = player.getUUID();
        challengeDay.put(id, day);
        breakProgress.put(id, 0);
        placeProgress.put(id, 0);

        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(id, (net.minecraft.server.MinecraftServer) player.level().getServer());
        OneBlockPhase phase = data.getCurrentPhase();
        int extLevel = IslandExtensionManager.getLevel(id);

        // Seed reproductible : même challenge pour tout le monde le même jour
        long seed = day * 1000003L + id.hashCode();

        // Cible break : 20-100 selon phase
        int breakTarget = 20 + (phase.ordinal() * 8) + (int)(Math.abs(seed % 20));
        // Cible place : 10-60 selon niveau extension
        int placeTarget = 10 + (extLevel * 5) + (int)(Math.abs((seed >> 8) % 15));

        Challenge bc = new Challenge(
            ChallengeType.BREAK_BLOCKS,
            breakTarget,
            "Casse " + breakTarget + " blocs OneBlock",
            breakReward(phase, seed)
        );
        Challenge pc = new Challenge(
            ChallengeType.PLACE_BLOCKS,
            placeTarget,
            "Pose " + placeTarget + " blocs sur ton ile",
            placeReward(extLevel, seed)
        );

        breakChallenge.put(id, bc);
        placeChallenge.put(id, pc);

        player.sendSystemMessage(Component.literal("§6━━━━━━ §eNouveaux Challenges §6━━━━━━"));
        player.sendSystemMessage(Component.literal(
            "§a⚔ §f" + bc.description() + "  §8→ §e" + rewardDesc(bc.reward())));
        player.sendSystemMessage(Component.literal(
            "§a🔨 §f" + pc.description() + "  §8→ §e" + rewardDesc(pc.reward())));
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    private static void complete(ServerPlayer player, Challenge ch) {
        player.getInventory().add(ch.reward().copy());
        player.sendSystemMessage(Component.literal(
            "§a§l✦ CHALLENGE COMPLÉTÉ : §r§e" + ch.description()));
        player.sendSystemMessage(Component.literal(
            "§7Récompense : §f" + rewardDesc(ch.reward())));
    }

    // ─── Récompenses ─────────────────────────────────────────────────────────

    private static ItemStack breakReward(OneBlockPhase phase, long seed) {
        int ordinal = phase.ordinal();
        if (ordinal <= 1)  return rnd(seed, stack(Items.WHEAT_SEEDS, 16), stack(Items.BONE_MEAL, 8));
        if (ordinal <= 3)  return rnd(seed, stack(Items.IRON_INGOT, 4),   stack(Items.COAL, 8));
        if (ordinal <= 5)  return rnd(seed, stack(Items.GOLD_INGOT, 3),   stack(Items.IRON_INGOT, 6));
        if (ordinal <= 8)  return rnd(seed, stack(Items.DIAMOND, 1),       stack(Items.EMERALD, 2));
        if (ordinal <= 10) return rnd(seed, stack(Items.DIAMOND, 2),       stack(Items.NETHERITE_SCRAP, 1));
        return rnd(seed, stack(Items.NETHERITE_SCRAP, 1), stack(Items.DIAMOND, 3));
    }

    private static ItemStack placeReward(int extLevel, long seed) {
        if (extLevel <= 1) return rnd(seed, stack(Items.OAK_SAPLING, 4), stack(Items.BONE_MEAL, 6));
        if (extLevel <= 3) return rnd(seed, stack(Items.APPLE, 8),        stack(Items.BREAD, 4));
        if (extLevel <= 5) return rnd(seed, stack(Items.IRON_INGOT, 3),   stack(Items.GOLD_INGOT, 2));
        if (extLevel <= 8) return rnd(seed, stack(Items.DIAMOND, 1),       stack(Items.EMERALD, 3));
        return rnd(seed, stack(Items.DIAMOND, 2), stack(Items.NETHERITE_SCRAP, 1));
    }

    private static ItemStack rnd(long seed, ItemStack a, ItemStack b) {
        return (Math.abs(seed) % 2 == 0) ? a : b;
    }

    private static ItemStack stack(net.minecraft.world.item.Item item, int count) {
        return new ItemStack(item, count);
    }

    private static String rewardDesc(ItemStack s) {
        return s.getCount() + "x "
            + Component.translatable(s.getItem().getDescriptionId()).getString();
    }

    // ─── Affichage ───────────────────────────────────────────────────────────

    public static void showChallenges(ServerPlayer player) {
        UUID id = player.getUUID();
        Challenge bc = breakChallenge.get(id);
        Challenge pc = placeChallenge.get(id);

        if (bc == null) {
            player.sendSystemMessage(Component.literal("§cAucun challenge actif (reconnecte-toi)."));
            return;
        }

        int bp = getBreakProgress(id);
        int pp = getPlaceProgress(id);

        player.sendSystemMessage(Component.literal("§6━━━━━━ §eChallenges du jour §6━━━━━━"));
        player.sendSystemMessage(Component.literal(
            (bp >= bc.target() ? "§a✔" : "§c✗") + " §f" + bc.description()
            + " §8(" + Math.min(bp, bc.target()) + "/" + bc.target() + ")"
            + " §8→ §e" + rewardDesc(bc.reward())));
        player.sendSystemMessage(Component.literal(
            (pp >= pc.target() ? "§a✔" : "§c✗") + " §f" + pc.description()
            + " §8(" + Math.min(pp, pc.target()) + "/" + pc.target() + ")"
            + " §8→ §e" + rewardDesc(pc.reward())));
        player.sendSystemMessage(Component.literal("§6━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"));
    }

    public static void clearPlayer(UUID id) {
        breakChallenge.remove(id);
        placeChallenge.remove(id);
        breakProgress.remove(id);
        placeProgress.remove(id);
        challengeDay.remove(id);
    }
}
