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
        long gameTime = level.getGameTime();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            PlayerDataManager.PlayerOneBlockData data =
                PlayerDataManager.getOrCreate(id, server);
            OneBlockPhase phase = data.getCurrentPhase();

            // Trail de particules impressionnant (si activé)
            if (trailEnabled.contains(id)) {
                spawnImpressiveTrail(level, player, phase, gameTime);
            }

            // Titre en action bar toutes les 3 secondes (60 ticks)
            // → le joueur voit son propre rang au-dessus de sa barre de vie
            if (gameTime % 60 == 0) {
                String prestige = PrestigeManager.getPrestigePrefix(id);
                String phaseTitle = getPhaseTitle(phase);
                if (!phaseTitle.isEmpty()) {
                    player.sendSystemMessage(
                        Component.literal(prestige + phaseTitle), true);
                }
            }
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

    /**
     * Trail impressionnant : 3 anneaux en spirale autour du joueur + particules montantes.
     * Chaque phase a ses propres particules et couleurs.
     */
    private static void spawnImpressiveTrail(ServerLevel level, ServerPlayer player,
                                              OneBlockPhase phase, long gameTime) {
        double px = player.getX();
        double py = player.getY() + 0.5;
        double pz = player.getZ();

        ParticleOptions main   = getTrailParticle(phase);
        ParticleOptions accent = getAccentParticle(phase);

        // ── Anneau principal (rayon 1.2, tourne dans le temps) ────────────────
        double angle1 = (gameTime * 0.15) % (2 * Math.PI);
        for (int i = 0; i < 8; i++) {
            double a = angle1 + i * (Math.PI / 4);
            double rx = Math.cos(a) * 1.2;
            double rz = Math.sin(a) * 1.2;
            level.sendParticles(main, px + rx, py, pz + rz, 1, 0, 0.02, 0, 0);
        }

        // ── Anneau accent (rayon 0.7, tourne en sens inverse) ─────────────────
        double angle2 = -(gameTime * 0.12) % (2 * Math.PI);
        for (int i = 0; i < 6; i++) {
            double a = angle2 + i * (Math.PI / 3);
            double rx = Math.cos(a) * 0.7;
            double rz = Math.sin(a) * 0.7;
            level.sendParticles(accent, px + rx, py + 0.3, pz + rz, 1, 0, 0.03, 0, 0);
        }

        // ── Spirale montante sous le joueur ───────────────────────────────────
        double angle3 = (gameTime * 0.20) % (2 * Math.PI);
        for (int i = 0; i < 4; i++) {
            double a  = angle3 + i * (Math.PI / 2);
            double t  = (gameTime % 20) / 20.0;
            double ry = -0.5 + t;
            double rx = Math.cos(a) * (0.3 + t * 0.5);
            double rz = Math.sin(a) * (0.3 + t * 0.5);
            level.sendParticles(main, px + rx, py + ry, pz + rz, 1, 0, 0.05, 0, 0.02);
        }

        // ── Éclats ponctuels aléatoires (toutes les 5 ticks) ─────────────────
        if (gameTime % 5 == 0) {
            level.sendParticles(accent,
                px + (Math.random() - 0.5) * 2,
                py + Math.random() * 2,
                pz + (Math.random() - 0.5) * 2,
                1, 0, 0.1, 0, 0.05);
        }
    }

    private static ParticleOptions getAccentParticle(OneBlockPhase phase) {
        return switch (phase.getMajorPhaseIndex()) {
            case 0  -> ParticleTypes.COMPOSTER;      // Plains
            case 1  -> ParticleTypes.CRIT;           // Underground
            case 2  -> ParticleTypes.ITEM_SNOWBALL;  // Winter
            case 3  -> ParticleTypes.SPLASH;         // Ocean
            case 4  -> ParticleTypes.FALLING_NECTAR; // Jungle
            case 5  -> ParticleTypes.MYCELIUM;       // Swamp
            case 6  -> ParticleTypes.CRIT;           // Dungeon
            case 7  -> ParticleTypes.POOF;            // Desert
            case 8  -> ParticleTypes.LAVA;           // Nether
            case 9  -> ParticleTypes.NAUTILUS;       // Plenty
            case 10 -> ParticleTypes.REVERSE_PORTAL; // End
            default -> ParticleTypes.CRIT;
        };
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
        player.sendSystemMessage(Component.literal(
            "§7Les titres sont §atoujours actifs §7— visibles par tous les joueurs."));
    }

    /**
     * Applique le titre via une équipe scoreboard — apparaît au-dessus de la tête
     * ET dans la tab-list comme prefix.
     */
    public static void applyTeamTitle(ServerPlayer player, PlayerDataManager.PlayerOneBlockData data) {
        // Titres toujours actifs pour tous
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

    public static String getPhaseTitle(OneBlockPhase phase) {
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
        applyTeamTitle(player, data); // toujours appliquer
    }

    public static String getFullTitle(UUID id, PlayerDataManager.PlayerOneBlockData data) {
        return PrestigeManager.getPrestigePrefix(id) + getPhaseTitle(data.getCurrentPhase());
    }

    public static boolean isTitleEnabled(UUID id) { return true; } // toujours actif

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
                BlockPos pos = center.offset(dx, 0, dz);
                // Le bloc directement sous le OneBlock (dx=0, dz=0) est TOUJOURS bedrock
                if (dx == 0 && dz == 0) {
                    // Vérifie et force la bedrock si nécessaire
                    if (!level.getBlockState(pos).is(Blocks.BEDROCK)) {
                        level.setBlock(pos, Blocks.BEDROCK.defaultBlockState(), 3);
                    }
                } else {
                    level.setBlock(pos, blocks[i].defaultBlockState(), 3);
                }
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
