package com.oneblock.mod.network;

import com.oneblock.mod.OneBlockMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class OneBlockNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(OneBlockMod.MOD_ID, "main"),
        () -> PROTOCOL,
        PROTOCOL::equals,
        PROTOCOL::equals
    );

    public static void register() {
        CHANNEL.messageBuilder(SyncProgressPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
            .encoder(SyncProgressPacket::encode)
            .decoder(SyncProgressPacket::decode)
            .consumerMainThread(SyncProgressPacket::handle)
            .add();
    }
}
