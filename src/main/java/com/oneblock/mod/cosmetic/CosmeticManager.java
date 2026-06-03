package com.oneblock.mod.cosmetic;

import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.prestige.PrestigeManager;
import com.oneblock.mod.world.OneBlockPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CosmeticManager {

    private static final Set<UUID> trailEnabled = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> titleEnabled = ConcurrentHashMap.newKeySet();

    /** Thème d'île actif par joueur ("plains", "winter", etc.) */
    private static final Map<UUID, String> islandThemes = new ConcurrentHashMap<>();

    // ─── Trails ──────────────────────────────────────────────────────────────

    public static void toggleTrail(ServerPlayer player) {
        UUID id = player.getUUID();
        if (trailEnabled.remove(id)) {
            player.sendSystemMessage(Component.literal("§7Trail §cdésactivé§7."));
        } else {
            trailEnabled.add(id);
            player.sendSystemMessage(Component.literal("§7Trail §aactivé§7."));
        }
    }

    public static void tickTrails(MinecraftServer server) {
        ServerLevel level = server.overworld();

        for (UUID id : trailEnabled) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) continue;
            OneBlockPhase phase = PlayerDataManager.getOrCreate(id, server).getCurrentPhase();
            level.sendParticles(getTrailParticle(phase),
                player.getX(), player.getY() + 0.5, player.getZ(),
                3, 0.3, 0.3, 0.3, 0.01);
        }

        // Neige pour les îles en thème winter (toutes les 10 ticks)
        if (level.getGameTime() % 10 == 0) {
            for (Map.Entry<UUID, String> e : islandThemes.entrySet()) {
                if (!"winter".equals(e.getValue())) continue;
                ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
                if (player == null) continue;

                PlayerDataManager.PlayerOneBlockData data =
                    PlayerDataManager.getOrCreate(e.getKey(), server);
                BlockPos island = data.blockPos;

                // Particules de flocons de neige qui tombent au-dessus de l'île
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        double x = island.getX() + dx + Math.random();
                        double y = island.getY() + 8 + Math.random() * 4;
                        double z = island.getZ() + dz + Math.random();
                        level.sendParticles(ParticleTypes.SNOWFLAKE,
                            x, y, z, 1, 0, -0.1, 0, 0.02);
                    }
                }
            }
        }
    }

    private static ParticleOptions getTrailParticle(OneBlockPhase phase) {
        return switch (phase.getMajorPhaseIndex()) {
            case 0  -> ParticleTypes.HAPPY_VILLAGER;
            case 1  -> ParticleTypes.SMOKE;
            case 2  -> ParticleTypes.SNOWFLAKE;
            case 3  -> ParticleTypes.BUBBLE_POP;
            case 4  -> ParticleTypes.SPORE_BLOSSOM_AIR;
            case 5  -> ParticleTypes.WITCH;
            case 6  -> ParticleTypes.ASH;
            case 7  -> ParticleTypes.POOF;
            case 8  -> ParticleTypes.FLAME;
            case 9  -> ParticleTypes.ENCHANT;
            case 10 -> ParticleTypes.PORTAL;
            default -> ParticleTypes.HAPPY_VILLAGER;
        };
    }

    // ─── Titres (au-dessus de la tête via scoreboard team) ───────────────────

    public static void toggleTitle(ServerPlayer player) {
        UUID id = player.getUUID();
        if (titleEnabled.remove(id)) {
            removeTeamTitle(player);
            player.sendSystemMessage(Component.literal("§7Titre §cdésactivé§7."));
        } else {
            titleEnabled.add(id);
            applyTeamTitle(player,
                PlayerDataManager.getOrCreate(id,
                    (MinecraftServer) player.level().getServer()));
            player.sendSystemMessage(Component.literal("§7Titre §aactivé§7."));
        }
    }

    /**
     * Applique le titre via une équipe scoreboard — apparaît au-dessus de la tête
     * ET dans la tab-list comme prefix.
     */
    public static void applyTeamTitle(ServerPlayer player, PlayerDataManager.PlayerOneBlockData data) {
        if (!titleEnabled.contains(player.getUUID())) return;

        String prestige = PrestigeManager.getPrestigePrefix(player.getUUID());
        String phase    = getPhaseTitle(data.getCurrentPhase());
        String full     = prestige + phase;
        if (full.isEmpty()) return;

        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;
        Scoreboard scoreboard = server.getScoreboard();
        String teamName = "ob_" + player.getUUID().toString().replace("-", "").substring(0, 14);

        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team == null) team = scoreboard.addPlayerTeam(teamName);

        team.setPlayerPrefix(Component.literal(full + " "));
        team.setNameTagVisibility(Team.Visibility.ALWAYS);

        scoreboard.addPlayerToTeam(player.getName().getString(), team);
    }

    private static void removeTeamTitle(ServerPlayer player) {
        MinecraftServer server = (MinecraftServer) player.level().getServer();
        if (server == null) return;
        Scoreboard scoreboard = server.getScoreboard();
        String teamName = "ob_" + player.getUUID().toString().replace("-", "").substring(0, 14);
        PlayerTeam team = scoreboard.getPlayerTeam(teamName);
        if (team != null) {
            scoreboard.removePlayerFromTeam(player.getName().getString(), team);
            scoreboard.removePlayerTeam(team);
        }
    }

    private static String getPhaseTitle(OneBlockPhase phase) {
        return switch (phase.getMajorPhaseIndex()) {
            case 0  -> "§7[Survivant]";
            case 1  -> "§7[Mineur]";
            case 2  -> "§b[Arctique]";
            case 3  -> "§9[Marin]";
            case 4  -> "§2[Explorateur]";
            case 5  -> "§2[Rôdeur]";
            case 6  -> "§8[Pilleur]";
            case 7  -> "§e[Nomade]";
            case 8  -> "§c[Démon]";
            case 9  -> "§6[Marchand]";
            case 10 -> "§5[Légende]";
            default -> "";
        };
    }

    /** Appelé quand la phase change pour mettre à jour le titre au-dessus de la tête. */
    public static void onPhaseChange(ServerPlayer player, PlayerDataManager.PlayerOneBlockData data) {
        if (titleEnabled.contains(player.getUUID())) {
            applyTeamTitle(player, data);
        }
    }

    public static String getFullTitle(UUID id, PlayerDataManager.PlayerOneBlockData data) {
        if (!titleEnabled.contains(id)) return "";
        return PrestigeManager.getPrestigePrefix(id) + getPhaseTitle(data.getCurrentPhase());
    }

    public static boolean isTitleEnabled(UUID id) { return titleEnabled.contains(id); }

    // ─── Thèmes d'île ────────────────────────────────────────────────────────

    public static void applyTheme(ServerPlayer player, String themeName, ServerLevel level) {
        PlayerDataManager.PlayerOneBlockData data =
            PlayerDataManager.getOrCreate(player.getUUID(),
                (MinecraftServer) player.level().getServer());
        BlockPos center = data.blockPos.below();

        net.minecraft.world.level.block.Block[] blocks = switch (themeName.toLowerCase()) {
            case "winter" -> new net.minecraft.world.level.block.Block[]{
                Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.SNOW_BLOCK,
                Blocks.ICE, Blocks.PACKED_ICE, Blocks.ICE,
                Blocks.SNOW_BLOCK, Blocks.ICE, Blocks.SNOW_BLOCK
            };
            case "ocean" -> new net.minecraft.world.level.block.Block[]{
                Blocks.SAND, Blocks.PRISMARINE, Blocks.SAND,
                Blocks.PRISMARINE, Blocks.SEA_LANTERN, Blocks.PRISMARINE,
                Blocks.SAND, Blocks.PRISMARINE, Blocks.SAND
            };
            case "nether" -> new net.minecraft.world.level.block.Block[]{
                Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.NETHERRACK,
                Blocks.SOUL_SAND, Blocks.MAGMA_BLOCK, Blocks.SOUL_SAND,
                Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.NETHERRACK
            };
            case "end" -> new net.minecraft.world.level.block.Block[]{
                Blocks.END_STONE, Blocks.PURPUR_BLOCK, Blocks.END_STONE,
                Blocks.PURPUR_BLOCK, Blocks.END_STONE_BRICKS, Blocks.PURPUR_BLOCK,
                Blocks.END_STONE, Blocks.PURPUR_BLOCK, Blocks.END_STONE
            };
            case "jungle" -> new net.minecraft.world.level.block.Block[]{
                Blocks.JUNGLE_PLANKS, Blocks.MOSS_BLOCK, Blocks.JUNGLE_PLANKS,
                Blocks.MOSS_BLOCK, Blocks.JUNGLE_LOG, Blocks.MOSS_BLOCK,
                Blocks.JUNGLE_PLANKS, Blocks.MOSS_BLOCK, Blocks.JUNGLE_PLANKS
            };
            default -> new net.minecraft.world.level.block.Block[]{  // plains
                Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.GRASS_BLOCK,
                Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT,
                Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.GRASS_BLOCK
            };
        };

        int i = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(center.offset(dx, 0, dz), blocks[i].defaultBlockState(), 3);
                i++;
            }
        }

        // Enregistre le thème pour la neige (winter)
        if ("winter".equalsIgnoreCase(themeName)) {
            islandThemes.put(player.getUUID(), "winter");
        } else {
            islandThemes.remove(player.getUUID());
        }

        player.sendSystemMessage(Component.literal("§aThème §e" + themeName + " §aappliqué à ton île."));
        if ("winter".equalsIgnoreCase(themeName)) {
            player.sendSystemMessage(Component.literal("§b❄ La neige tombera sur ton île !"));
        }
    }

    public static void cleanupOnLogout(UUID id, ServerPlayer player) {
        if (player != null && titleEnabled.contains(id)) {
            removeTeamTitle(player);
        }
        trailEnabled.remove(id);
        titleEnabled.remove(id);
    }
}
