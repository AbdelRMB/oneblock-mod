package com.oneblock.mod.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Progression OneBlock — phases thématiques avec sous-niveaux cumulatifs.
 *
 * Chaque entrée définit UNIQUEMENT les nouveaux blocs qu'elle ajoute au pool.
 * getRandomBlock() agrège tous les blocs de PLAINS_1 jusqu'au sous-niveau actuel,
 * garantissant que les blocs des phases précédentes restent toujours disponibles.
 *
 * Résumé de la progression :
 *   Phase 1  Plains      :    0 →   700  (4 sous-niveaux)
 *   Phase 2  Underground :  700 →  1700  (5 sous-niveaux)
 *   Phase 3  Winter      : 1700 →  2700  (4 sous-niveaux)
 *   Phase 4  Ocean       : 2700 →  3700  (4 sous-niveaux)
 *   Phase 5  Jungle      : 3700 →  4000  (3 sous-niveaux)
 *   Phase 6  Swamp       : 4000 →  5000  (4 sous-niveaux)
 *   Phase 7  Dungeon     : 5000 →  6000  (4 sous-niveaux)
 *   Phase 8  Desert      : 6000 →  7000  (4 sous-niveaux)
 *   Phase 9  Nether      : 7000 →  7500  (3 sous-niveaux)
 *   Phase 10 Plenty      : 7500 →  8500  (3 sous-niveaux)
 *   Phase 11 The End     : 8500 → 10500  (4 sous-niveaux)
 */
public enum OneBlockPhase {

    // ═══════════════════════════════════════════
    // Phase 1 — Plains
    // ═══════════════════════════════════════════

    PLAINS_1(0, 50, "§aPlains §71.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.GRASS_BLOCK, 1),
            }),
    PLAINS_2(50, 100, "§aPlains §71.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.OAK_LOG, 1),
            }),
    PLAINS_3(150, 200, "§aPlains §71.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.FERN, 1),
            }),
    PLAINS_4(350, 350, "§aPlains §71.4",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.GRAVEL, 1),
                    new WeightedBlock(Blocks.POPPY, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 2 — Underground
    // ═══════════════════════════════════════════

    UNDERGROUND_1(700, 150, "§7Underground §72.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.STONE, 1),
                    new WeightedBlock(Blocks.COBBLESTONE, 1),
            }),
    UNDERGROUND_2(850, 200, "§7Underground §72.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.COAL_ORE, 1),
            }),
    UNDERGROUND_3(1050, 250, "§7Underground §72.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.IRON_ORE, 1),
            }),
    UNDERGROUND_4(1300, 250, "§7Underground §72.4",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.GOLD_ORE, 1),
            }),
    UNDERGROUND_5(1550, 150, "§7Underground §72.5",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.REDSTONE_ORE, 1),
                    new WeightedBlock(Blocks.LAPIS_ORE, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 3 — Winter
    // ═══════════════════════════════════════════

    WINTER_1(1700, 200, "§bWinter §73.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SNOW_BLOCK, 1),
            }),
    WINTER_2(1900, 250, "§bWinter §73.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.ICE, 1),
            }),
    WINTER_3(2150, 300, "§bWinter §73.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SPRUCE_LOG, 1),
            }),
    WINTER_4(2450, 250, "§bWinter §73.4",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.BLUE_ICE, 1),
                    new WeightedBlock(Blocks.SPRUCE_LEAVES, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 4 — Ocean
    // ═══════════════════════════════════════════

    OCEAN_1(2700, 200, "§9Ocean §74.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SAND, 1),
            }),
    OCEAN_2(2900, 250, "§9Ocean §74.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.CLAY, 1),
            }),
    OCEAN_3(3150, 300, "§9Ocean §74.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.PRISMARINE, 1),
            }),
    OCEAN_4(3450, 250, "§9Ocean §74.4",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SPONGE, 1),
                    new WeightedBlock(Blocks.SEA_LANTERN, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 5 — Jungle
    // ═══════════════════════════════════════════

    JUNGLE_1(3700, 100, "§2Jungle §75.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.JUNGLE_LOG, 1),
            }),
    JUNGLE_2(3800, 100, "§2Jungle §75.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.VINE, 1),
            }),
    JUNGLE_3(3900, 100, "§2Jungle §75.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.BAMBOO, 1),
                    new WeightedBlock(Blocks.MELON, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 6 — Swamp
    // ═══════════════════════════════════════════

    SWAMP_1(4000, 200, "§2Swamp §76.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.LILY_PAD, 1),
            }),
    SWAMP_2(4200, 300, "§2Swamp §76.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.RED_MUSHROOM, 1),
                    new WeightedBlock(Blocks.BROWN_MUSHROOM, 1),
            }),
    SWAMP_3(4500, 250, "§2Swamp §76.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SLIME_BLOCK, 1),
            }),
    SWAMP_4(4750, 250, "§2Swamp §76.4",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.MOSS_BLOCK, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 7 — Dungeon
    // ═══════════════════════════════════════════

    DUNGEON_1(5000, 250, "§8Dungeon §77.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.MOSSY_COBBLESTONE, 1),
                    new WeightedBlock(Blocks.STONE_BRICKS, 1),
            }),
    DUNGEON_2(5250, 300, "§8Dungeon §77.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.MOSSY_STONE_BRICKS, 1),
            }),
    DUNGEON_3(5550, 250, "§8Dungeon §77.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.CHISELED_STONE_BRICKS, 1),
            }),
    DUNGEON_4(5800, 200, "§8Dungeon §77.4",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.DIAMOND_ORE, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 8 — Desert
    // ═══════════════════════════════════════════

    DESERT_1(6000, 200, "§eDesert §78.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SANDSTONE, 1),
            }),
    DESERT_2(6200, 300, "§eDesert §78.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.RED_SAND, 1),
                    new WeightedBlock(Blocks.RED_SANDSTONE, 1),
            }),
    DESERT_3(6500, 250, "§eDesert §78.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.CACTUS, 1),
            }),
    DESERT_4(6750, 250, "§eDesert §78.4",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SMOOTH_SANDSTONE, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 9 — Nether
    // ═══════════════════════════════════════════

    NETHER_1(7000, 150, "§cNether §79.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.NETHERRACK, 1),
            }),
    NETHER_2(7150, 200, "§cNether §79.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SOUL_SAND, 1),
            }),
    NETHER_3(7350, 150, "§cNether §79.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.NETHER_QUARTZ_ORE, 1),
                    new WeightedBlock(Blocks.BASALT, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 10 — Plenty
    // ═══════════════════════════════════════════

    PLENTY_1(7500, 300, "§6Plenty §710.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.EMERALD_ORE, 1),
                    new WeightedBlock(Blocks.IRON_BLOCK, 1),
            }),
    PLENTY_2(7800, 350, "§6Plenty §710.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.GOLD_BLOCK, 1),
                    new WeightedBlock(Blocks.REDSTONE_BLOCK, 1),
            }),
    PLENTY_3(8150, 350, "§6Plenty §710.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.DIAMOND_BLOCK, 1),
                    new WeightedBlock(Blocks.ENDER_CHEST, 1),
            }),

    // ═══════════════════════════════════════════
    // Phase 11 — The End
    // ═══════════════════════════════════════════

    END_1(8500, 500, "§5The End §711.1",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.END_STONE, 1),
            }),
    END_2(9000, 600, "§5The End §711.2",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.PURPUR_BLOCK, 1),
            }),
    END_3(9600, 600, "§5The End §711.3",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.CHORUS_PLANT, 1),
            }),
    END_4(10200, Integer.MAX_VALUE, "§5The End §711.4",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.DRAGON_EGG, 1),
            });

    public final int startCount;
    public final int duration;
    public final String displayName;
    /** Blocs ajoutés UNIQUEMENT par ce sous-niveau (le pool réel est cumulatif). */
    private final WeightedBlock[] newBlocks;

    OneBlockPhase(int startCount, int duration, String displayName, WeightedBlock[] newBlocks) {
        this.startCount = startCount;
        this.duration = duration;
        this.displayName = displayName;
        this.newBlocks = newBlocks;
    }

    /**
     * Retourne la phase correspondant à un nombre de blocs cassés.
     */
    public static OneBlockPhase fromCount(int blocksBroken) {
        OneBlockPhase result = PLAINS_1;
        for (OneBlockPhase phase : values()) {
            if (blocksBroken >= phase.startCount) {
                result = phase;
            }
        }
        return result;
    }

    /**
     * Retourne un bloc aléatoire en piochant dans le pool CUMULATIF :
     * tous les blocs débloqués depuis PLAINS_1 jusqu'à ce sous-niveau inclus.
     */
    public Block getRandomBlock(Random random) {
        List<WeightedBlock> pool = new ArrayList<>();
        for (OneBlockPhase phase : values()) {
            pool.addAll(Arrays.asList(phase.newBlocks));
            if (phase == this) break;
        }

        int totalWeight = pool.stream().mapToInt(b -> b.weight).sum();
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (WeightedBlock wb : pool) {
            cumulative += wb.weight;
            if (roll < cumulative) return wb.block;
        }
        return Blocks.STONE;
    }

    /**
     * Retourne la progression dans le sous-niveau actuel (0.0 → 1.0).
     */
    public float getProgress(int blocksBroken) {
        if (duration == Integer.MAX_VALUE) return 1.0f;
        int inPhase = blocksBroken - startCount;
        return Math.min(1.0f, (float) inPhase / duration);
    }

    public boolean isLastPhase() {
        return this == END_4;
    }

    public static class WeightedBlock {
        public final Block block;
        public final int weight;

        public WeightedBlock(Block block, int weight) {
            this.block = block;
            this.weight = weight;
        }
    }
}
