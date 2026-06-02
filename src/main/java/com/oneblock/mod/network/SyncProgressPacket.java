package com.oneblock.mod.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Paquet envoyé du serveur vers le client pour mettre à jour la barre de progression HUD.
 */
public class SyncProgressPacket {

    public final int blocksBroken;
    public final int blocksUntilNext;  // -1 si dernière phase
    public final String displayName;   // ex: "§aPlains §71.4"
    public final float progress;       // 0.0 → 1.0 dans le sous-niveau actuel

    public SyncProgressPacket(int blocksBroken, int blocksUntilNext, String displayName, float progress) {
        this.blocksBroken = blocksBroken;
        this.blocksUntilNext = blocksUntilNext;
        this.displayName = displayName;
        this.progress = progress;
    }

    public static void encode(SyncProgressPacket packet, FriendlyByteBuf buf) {
        buf.writeInt(packet.blocksBroken);
        buf.writeInt(packet.blocksUntilNext);
        buf.writeUtf(packet.displayName);
        buf.writeFloat(packet.progress);
    }

    public static SyncProgressPacket decode(FriendlyByteBuf buf) {
        return new SyncProgressPacket(buf.readInt(), buf.readInt(), buf.readUtf(), buf.readFloat());
    }

    public static void handle(SyncProgressPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            com.oneblock.mod.client.ClientProgressData.update(
                packet.blocksBroken,
                packet.blocksUntilNext,
                packet.displayName,
                packet.progress
            );
        });
        ctx.get().setPacketHandled(true);
    }
}
