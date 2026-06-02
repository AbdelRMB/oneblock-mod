package com.oneblock.mod;

import com.oneblock.mod.config.OneBlockConfig;
import com.oneblock.mod.network.OneBlockNetwork;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.config.IConfigSpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(OneBlockMod.MOD_ID)
public class OneBlockMod {

    public static final String MOD_ID = "oneblock";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public OneBlockMod() {
        OneBlockNetwork.register();
        LOGGER.info("[OneBlock] Mod chargé !");
    }
}
