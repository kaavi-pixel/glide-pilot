package pro.bhushan.glidepilot;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.bhushan.glidepilot.config.GlideConfig;
import pro.bhushan.glidepilot.config.GlideConfigScreen;
import pro.bhushan.glidepilot.flight.Autopilot;
import pro.bhushan.glidepilot.hud.GlideHud;
import pro.bhushan.glidepilot.nav.GlideCommands;

public final class GlidePilot implements ClientModInitializer {
	public static final String MOD_ID = "glidepilot";
	public static final Logger LOGGER = LoggerFactory.getLogger("glidepilot");

	public static KeyMapping toggleKey;
	public static KeyMapping landKey;
	public static KeyMapping takeoffKey;
	public static KeyMapping settingsKey;
	private static boolean openSettings;

	/** Opens the settings screen on the next tick, after chat has closed. */
	public static void requestSettings() {
		openSettings = true;
	}

	@Override
	public void onInitializeClient() {
		GlideConfig.get();
		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "flight"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.glidepilot.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_R, cat));
		landKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.glidepilot.land", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), cat));
		takeoffKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.glidepilot.takeoff", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), cat));
		settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.glidepilot.settings", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), cat));

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> GlideCommands.register(dispatcher));
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), GlideHud::render);

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (toggleKey.consumeClick()) Autopilot.get().toggle(mc);
			while (landKey.consumeClick()) if (mc.player != null) Autopilot.get().land(mc.player);
			while (takeoffKey.consumeClick()) if (mc.player != null) Autopilot.get().startTakeoff(mc.player);
			while (settingsKey.consumeClick()) openSettings = true;
			if (openSettings && mc.gui.screen() == null) {
				openSettings = false;
				mc.gui.setScreen(new GlideConfigScreen(null));
			}
			Autopilot.get().tick(mc);
		});
	}

	static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
