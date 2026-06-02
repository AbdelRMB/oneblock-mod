package com.oneblock.mod.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Données de progression OneBlock côté client, mises à jour via SyncProgressPacket.
 */
@OnlyIn(Dist.CLIENT)
public class ClientProgressData {

    public static int    blocksBroken   = 0;
    public static int    blocksUntilNext = -1;   // -1 = dernière phase
    public static String displayName    = "";
    public static float  progress       = 0f;    // 0.0 → 1.0 dans le sous-niveau

    public static void update(int blocksBroken, int blocksUntilNext, String displayName, float progress) {
        ClientProgressData.blocksBroken    = blocksBroken;
        ClientProgressData.blocksUntilNext = blocksUntilNext;
        ClientProgressData.displayName     = displayName;
        ClientProgressData.progress        = progress;
    }
}
