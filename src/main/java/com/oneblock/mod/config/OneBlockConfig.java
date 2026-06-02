package com.oneblock.mod.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class OneBlockConfig {

    public static final ForgeConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        SERVER = new Server(builder);
        SERVER_SPEC = builder.build();
    }

    public static class Server {

        public final ForgeConfigSpec.IntValue islandSpacing;
        public final ForgeConfigSpec.BooleanValue starterPlatform;
        public final ForgeConfigSpec.IntValue blockY;
        public final ForgeConfigSpec.BooleanValue voidTeleport;
        public final ForgeConfigSpec.BooleanValue progressMessages;

        public Server(ForgeConfigSpec.Builder builder) {
            builder.comment("Configuration du serveur OneBlock");
            builder.push("general");

            islandSpacing = builder
                    .comment("Distance entre chaque île. Default: 200")
                    .defineInRange("islandSpacing", 200, 50, 10000);

            blockY = builder
                    .comment("Hauteur Y du bloc OneBlock. Default: 64")
                    .defineInRange("blockY", 64, 0, 320);

            starterPlatform = builder
                    .comment("Plateforme 3x3 de verre au départ. Default: true")
                    .define("starterPlatform", true);

            voidTeleport = builder
                    .comment("Téléporter si chute dans le vide. Default: true")
                    .define("voidTeleport", true);

            progressMessages = builder
                    .comment("Messages de progression. Default: true")
                    .define("progressMessages", true);

            builder.pop();
        }
    }
}
