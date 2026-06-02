package com.oneblock.mod.event;

import com.mojang.brigadier.CommandDispatcher;
import com.oneblock.mod.OneBlockMod;
import com.oneblock.mod.data.PlayerDataManager;
import com.oneblock.mod.world.OneBlockWorldGen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.listener.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Set;

@Mod.EventBusSubscriber(modid = OneBlockMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class OneBlockCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("oneblock")

                .then(Commands.literal("stats")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        MinecraftServer server = ctx.getSource().getServer();
                        PlayerDataManager.PlayerOneBlockData data =
                            PlayerDataManager.getOrCreate(player.getUUID(), server);
                        OneBlockWorldGen.sendPlayerStats(player, data);
                        return 1;
                    })
                )

                .then(Commands.literal("tp")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        MinecraftServer server = ctx.getSource().getServer();
                        PlayerDataManager.PlayerOneBlockData data =
                            PlayerDataManager.getOrCreate(player.getUUID(), server);
                        ServerLevel level = server.overworld();

                        player.teleportTo(level,
                            data.blockPos.getX() + 0.5,
                            data.blockPos.getY() + 1.1,
                            data.blockPos.getZ() + 0.5,
                            Set.of(),
                            player.getYRot(),
                            player.getXRot(),
                            true
                        );
                        player.sendSystemMessage(Component.literal("§aTéléporté à ton bloc !"));
                        return 1;
                    })
                )

                .then(Commands.literal("phase")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        MinecraftServer server = ctx.getSource().getServer();
                        PlayerDataManager.PlayerOneBlockData data =
                            PlayerDataManager.getOrCreate(player.getUUID(), server);

                        player.sendSystemMessage(
                            Component.literal("§7Phase : " + data.getCurrentPhase().displayName
                                + " §7| Blocs : §f" + data.blocksBroken)
                        );
                        return 1;
                    })
                )
        );
    }
}
