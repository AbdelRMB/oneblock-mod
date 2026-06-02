package com.oneblock.mod.client;

import com.oneblock.mod.OneBlockMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Affiche en haut de l'écran :
 *   [Plains 1.4 — 423 / 700]
 *   [████████░░░░░░░░░░░░░░░]   ← barre de progression style XP
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = OneBlockMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class OneBlockHud {

    private static final int BAR_WIDTH  = 182;
    private static final int BAR_HEIGHT = 5;

    @SubscribeEvent
    public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
        // On se greffe sur le hotbar pour ne rendre qu'une seule fois par frame
        if (event.getOverlay() != VanillaGuiOverlay.HOTBAR.type()) return;
        if (ClientProgressData.displayName.isEmpty()) return;

        Minecraft mc       = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        Font font          = mc.font;
        int screenWidth    = mc.getWindow().getGuiScaledWidth();

        // ── Texte ─────────────────────────────────────────────────
        String rawName = stripColorCodes(ClientProgressData.displayName);
        String label;
        if (ClientProgressData.blocksUntilNext > 0) {
            int nextCount = ClientProgressData.blocksBroken + ClientProgressData.blocksUntilNext;
            label = rawName + "  —  " + ClientProgressData.blocksBroken + " / " + nextCount;
        } else {
            label = rawName + "  —  " + ClientProgressData.blocksBroken + " (MAX)";
        }

        // ── Positions ─────────────────────────────────────────────
        int barX   = (screenWidth - BAR_WIDTH) / 2;
        int barY   = 8;
        int textY  = barY - font.lineHeight - 2;

        // ── Texte centré ──────────────────────────────────────────
        graphics.drawCenteredString(font, label, screenWidth / 2, textY, 0xFFFFFF);

        // ── Contour + fond de la barre ────────────────────────────
        graphics.fill(barX - 1, barY - 1, barX + BAR_WIDTH + 1, barY + BAR_HEIGHT + 1, 0xFF000000);
        graphics.fill(barX,     barY,     barX + BAR_WIDTH,     barY + BAR_HEIGHT,     0xFF1A1A1A);

        // ── Remplissage de la barre ───────────────────────────────
        int fillWidth = Math.max(0, (int)(BAR_WIDTH * ClientProgressData.progress));
        if (fillWidth > 0) {
            int color = getPhaseColor(ClientProgressData.displayName);
            // Léger dégradé : couleur principale puis version plus claire au centre
            graphics.fill(barX, barY,     barX + fillWidth, barY + BAR_HEIGHT,     color);
            graphics.fill(barX, barY + 1, barX + fillWidth, barY + BAR_HEIGHT - 1, brighten(color));
        }
    }

    /** Retire les codes couleur Minecraft (§x) d'une chaîne. */
    private static String stripColorCodes(String s) {
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    /** Couleur de la barre selon la phase (déduite du premier code couleur du displayName). */
    private static int getPhaseColor(String displayName) {
        if (displayName.length() < 2) return 0xFF80FF20;
        String code = displayName.substring(0, 2);
        return switch (code) {
            case "§a" -> 0xFF55FF55;   // Plains       — vert
            case "§7" -> 0xFF999999;   // Underground  — gris
            case "§b" -> 0xFF55FFFF;   // Winter       — cyan
            case "§9" -> 0xFF5577FF;   // Ocean        — bleu
            case "§2" -> 0xFF00AA00;   // Jungle/Swamp — vert foncé
            case "§8" -> 0xFF666666;   // Dungeon      — gris foncé
            case "§e" -> 0xFFFFAA00;   // Desert       — jaune
            case "§c" -> 0xFFFF4444;   // Nether       — rouge
            case "§6" -> 0xFFFFD700;   // Plenty       — or
            case "§5" -> 0xFFCC44CC;   // The End      — violet
            default   -> 0xFF80FF20;   // fallback XP
        };
    }

    /** Éclaircit légèrement une couleur ARGB pour le reflet de la barre. */
    private static int brighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 40);
        int g = Math.min(255, ((color >> 8)  & 0xFF) + 40);
        int b = Math.min(255, ( color        & 0xFF) + 40);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}
