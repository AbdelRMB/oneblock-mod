package com.oneblock.mod.world;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Définit les phases de progression du OneBlock.
 * Chaque phase a un nom, un nombre de blocs à casser pour avancer,
 * et une liste de blocs/items qui peuvent apparaître.
 */
public enum OneBlockPhase {

    // Phase 0 : Plaines (0 - 99 blocs cassés)
    PLAINS(0, 100, "§aPlaines",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.GRASS_BLOCK, 30),
                    new WeightedBlock(Blocks.DIRT, 20),
                    new WeightedBlock(Blocks.OAK_LOG, 15),
                    new WeightedBlock(Blocks.OAK_LEAVES, 10),
                    new WeightedBlock(Blocks.GRAVEL, 10),
                    new WeightedBlock(Blocks.SAND, 8),
                    new WeightedBlock(Blocks.STONE, 7),
            }
    ),

    // Phase 1 : Underground (100 - 299)
    UNDERGROUND(100, 200, "§7Underground",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.STONE, 30),
                    new WeightedBlock(Blocks.COBBLESTONE, 20),
                    new WeightedBlock(Blocks.IRON_ORE, 15),
                    new WeightedBlock(Blocks.COAL_ORE, 15),
                    new WeightedBlock(Blocks.GRAVEL, 10),
                    new WeightedBlock(Blocks.GOLD_ORE, 5),
                    new WeightedBlock(Blocks.DIAMOND_ORE, 3),
                    new WeightedBlock(Blocks.LAPIS_ORE, 2),
            }
    ),

    // Phase 2 : Océan (300 - 499)
    OCEAN(300, 200, "§9Océan",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SAND, 25),
                    new WeightedBlock(Blocks.GRAVEL, 20),
                    new WeightedBlock(Blocks.CLAY, 20),
                    new WeightedBlock(Blocks.KELP, 10),
                    new WeightedBlock(Blocks.SEA_LANTERN, 8),
                    new WeightedBlock(Blocks.PRISMARINE, 8),
                    new WeightedBlock(Blocks.DARK_PRISMARINE, 5),
                    new WeightedBlock(Blocks.SPONGE, 4),
            }
    ),

    // Phase 3 : Jungle (500 - 699)
    JUNGLE(500, 200, "§2Jungle",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.JUNGLE_LOG, 25),
                    new WeightedBlock(Blocks.JUNGLE_LEAVES, 20),
                    new WeightedBlock(Blocks.JUNGLE_WOOD, 15),
                    new WeightedBlock(Blocks.VINE, 15),
                    new WeightedBlock(Blocks.MELON, 10),
                    new WeightedBlock(Blocks.BAMBOO, 10),
                    new WeightedBlock(Blocks.COCOA, 5),
            }
    ),

    // Phase 4 : Désert (700 - 899)
    DESERT(700, 200, "§esDésert",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.SAND, 30),
                    new WeightedBlock(Blocks.SANDSTONE, 20),
                    new WeightedBlock(Blocks.RED_SAND, 15),
                    new WeightedBlock(Blocks.CACTUS, 10),
                    new WeightedBlock(Blocks.SMOOTH_SANDSTONE, 10),
                    new WeightedBlock(Blocks.CHISELED_SANDSTONE, 8),
                    new WeightedBlock(Blocks.GOLD_BLOCK, 4),
                    new WeightedBlock(Blocks.GOLD_ORE, 3),
            }
    ),

    // Phase 5 : Nether (900 - 1099)
    NETHER(900, 200, "§cNether",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.NETHERRACK, 30),
                    new WeightedBlock(Blocks.NETHER_BRICKS, 20),
                    new WeightedBlock(Blocks.SOUL_SAND, 15),
                    new WeightedBlock(Blocks.NETHER_QUARTZ_ORE, 10),
                    new WeightedBlock(Blocks.MAGMA_BLOCK, 10),
                    new WeightedBlock(Blocks.CRIMSON_STEM, 7),
                    new WeightedBlock(Blocks.WARPED_STEM, 5),
                    new WeightedBlock(Blocks.NETHER_GOLD_ORE, 3),
            }
    ),

    // Phase 6 : End (1100+)
    END(1100, Integer.MAX_VALUE, "§5End",
            new WeightedBlock[]{
                    new WeightedBlock(Blocks.END_STONE, 30),
                    new WeightedBlock(Blocks.PURPUR_BLOCK, 20),
                    new WeightedBlock(Blocks.END_STONE_BRICKS, 15),
                    new WeightedBlock(Blocks.OBSIDIAN, 10),
                    new WeightedBlock(Blocks.CHORUS_PLANT, 10),
                    new WeightedBlock(Blocks.DRAGON_EGG, 1),
                    new WeightedBlock(Blocks.ENDER_CHEST, 5),
                    new WeightedBlock(Blocks.DIAMOND_BLOCK, 4),
                    new WeightedBlock(Blocks.EMERALD_BLOCK, 5),
            }
    );

    public final int startCount;   // Nombre de blocs cassés pour entrer dans cette phase
    public final int duration;     // Nombre de blocs à casser dans cette phase
    public final String displayName;
    private final WeightedBlock[] blocks;

    OneBlockPhase(int startCount, int duration, String displayName, WeightedBlock[] blocks) {
        this.startCount = startCount;
        this.duration = duration;
        this.displayName = displayName;
        this.blocks = blocks;
    }

    /**
     * Retourne la phase correspondant à un nombre de blocs cassés.
     */
    public static OneBlockPhase fromCount(int blocksBroken) {
        OneBlockPhase result = PLAINS;
        for (OneBlockPhase phase : values()) {
            if (blocksBroken >= phase.startCount) {
                result = phase;
            }
        }
        return result;
    }

    /**
     * Retourne un bloc aléatoire pondéré pour cette phase.
     */
    public Block getRandomBlock(Random random) {
        int totalWeight = Arrays.stream(blocks).mapToInt(b -> b.weight).sum();
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (WeightedBlock wb : blocks) {
            cumulative += wb.weight;
            if (roll < cumulative) {
                return wb.block;
            }
        }
        return Blocks.STONE;
    }

    /**
     * Retourne la progression dans la phase (0.0 à 1.0).
     */
    public float getProgress(int blocksBroken) {
        if (duration == Integer.MAX_VALUE) return 1.0f;
        int inPhase = blocksBroken - startCount;
        return Math.min(1.0f, (float) inPhase / duration);
    }

    public boolean isLastPhase() {
        return this == END;
    }

    /**
     * Classe interne pour les blocs pondérés.
     */
    public static class WeightedBlock {
        public final Block block;
        public final int weight;

        public WeightedBlock(Block block, int weight) {
            this.block = block;
            this.weight = weight;
        }
    }
}
