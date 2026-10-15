package com.craftraria;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CraftrariaMod implements ModInitializer {
	public static final String MOD_ID = "craftraria";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Craftraria mod initialized");
	}
}
