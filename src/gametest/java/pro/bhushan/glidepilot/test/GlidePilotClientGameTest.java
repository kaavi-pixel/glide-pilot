package pro.bhushan.glidepilot.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.entity.EquipmentSlot;
import pro.bhushan.glidepilot.config.GlideConfig;
import pro.bhushan.glidepilot.config.GlideConfigScreen;
import pro.bhushan.glidepilot.flight.Autopilot;
import pro.bhushan.glidepilot.flight.Autopilot.Stop;
import pro.bhushan.glidepilot.hud.GlideHud;

/**
 * Flies real routes in a real 26.3 client with an integrated server and records what happened.
 * Results go to the log and run/glidepilot-results.txt.
 */
@SuppressWarnings("UnstableApiUsage")
public class GlidePilotClientGameTest implements FabricClientGameTest {
	private static final List<String> results = new ArrayList<>();

	// flight recorder, written on the client thread
	private static volatile boolean recording;
	private static volatile boolean follower;
	private static volatile float holdPitch = Float.NaN;
	private static int coachTicks;
	private static int greenTicks;
	private static int redTicks;
	private static double errSum;
	private static float minHealth;
	private static float startHealth;
	private static float lastHealth;
	private static final StringBuilder dmg = new StringBuilder();
	private static double maxY;
	private static double minClearance;
	private static int ticks;
	private static int glideTicks;
	private static double startX;
	private static final List<String> modes = new ArrayList<>();
	private static Autopilot.Mode lastMode;
	private static final StringBuilder trace = new StringBuilder();

	private static void log(String s) {
		System.out.println("[glidepilot-test] " + s);
		results.add(s);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) return;
			Autopilot ap0 = Autopilot.get();
			if (!Float.isNaN(holdPitch)) mc.player.setXRot(holdPitch);
			if (follower && ap0.learning() && !Float.isNaN(ap0.advicePitch())) {
				// Simulated pilot: turns toward the marker at a human-ish rate.
				LocalPlayer fp = mc.player;
				float dp = ap0.advicePitch() - fp.getXRot();
				fp.setXRot(fp.getXRot() + net.minecraft.util.Mth.clamp(dp, -12f, 12f));
				if (!Float.isNaN(ap0.adviceYaw())) {
					float dy = net.minecraft.util.Mth.wrapDegrees(ap0.adviceYaw() - fp.getYRot());
					fp.setYRot(fp.getYRot() + net.minecraft.util.Mth.clamp(dy, -6f, 6f));
				}
			}
			if (recording && ap0.learning() && !Float.isNaN(ap0.advicePitch())) {
				float e = GlideHud.error(mc.player, ap0);
				coachTicks++;
				errSum += e;
				if (e <= GlideHud.GOOD) greenTicks++;
				if (e >= GlideHud.FAR) redTicks++;
			}
			if (!recording) return;
			LocalPlayer p = mc.player;
			ticks++;
			if (p.isFallFlying()) glideTicks++;
			minHealth = Math.min(minHealth, p.getHealth());
			if (p.getHealth() < lastHealth) dmg.append(String.format(" t%d:%.1f %s y%.0f v%.2f,%.2f", ticks, lastHealth - p.getHealth(), Autopilot.get().mode(), p.getY(), p.getDeltaMovement().horizontalDistance(), p.getDeltaMovement().y));
			lastHealth = p.getHealth();
			maxY = Math.max(maxY, p.getY());
			int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, p.getBlockX(), p.getBlockZ());
			if (p.isFallFlying()) minClearance = Math.min(minClearance, p.getY() - ground);
			Autopilot.Mode m = Autopilot.get().mode();
			if (m != lastMode) {
				modes.add(m + "@" + ticks + "(" + (int) p.getX() + "," + (int) p.getY() + "," + (int) p.getZ() + ")");
				lastMode = m;
			}
			if (ticks % 100 == 0) {
				trace.append(String.format(" t%d:x%d,y%d,%.1fm/s,%s", ticks, (int) p.getX(), (int) p.getY(),
					p.getDeltaMovement().horizontalDistance() * 20, Autopilot.get().profileName()));
			}
		});

		ctx.runOnClient(mc -> mc.gui.setScreen(new GlideConfigScreen(null)));
		ctx.waitTicks(5);
		ctx.takeScreenshot("glidepilot-config");
		ctx.runOnClient(mc -> mc.gui.screen().onClose());
		ctx.waitTicks(2);
		log("config file written: " + Files.exists(FabricLoader.getInstance().getConfigDir().resolve("glidepilot.json")));

		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			TestServerContext server = sp.getServer();
			server.runCommand("gamerule spawn_mobs false");
			server.runCommand("gamerule advance_time false");
			server.runCommand("time set noon");
			server.runCommand("difficulty peaceful");
			server.runCommand("gamemode survival @a");
			int ground = ctx.computeOnClient(mc -> mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0));
			log("flat ground top at y=" + ground);

			learningTest(ctx, server);
			if (System.getenv("GP_ONLY_LEARNING") != null) return;

			for (Autopilot.Style style : Autopilot.Style.values()) {
				GlideConfig.get().style = style;
				cruiseAndLand(ctx, server, "cruise " + style, 0, 2000, 1500);
			}
			GlideConfig.get().style = Autopilot.Style.MEDIUM;

			// A 260-block-tall wall across the path at x = 4600..4620.
			for (int y = -60; y < 200; y += 18) {
				server.runCommand("fill 4600 " + y + " 1960 4620 " + Math.min(199, y + 17) + " 2040 stone");
			}
			cruiseAndLand(ctx, server, "wall in path", 4000, 2000, 1200);

			takeoffTest(ctx, server);
			swapTest(ctx, server);
			ctx.takeScreenshot("glidepilot-end");
		}

		try {
			Files.writeString(Path.of("glidepilot-results.txt"), String.join("\n", results) + "\n");
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private static void resetRecorder(Minecraft mc) {
		minHealth = mc.player.getHealth();
		startHealth = minHealth;
		lastHealth = minHealth;
		dmg.setLength(0);
		maxY = mc.player.getY();
		minClearance = Double.MAX_VALUE;
		ticks = 0;
		glideTicks = 0;
		coachTicks = 0;
		greenTicks = 0;
		redTicks = 0;
		errSum = 0;
		startX = mc.player.getX();
		modes.clear();
		trace.setLength(0);
		lastMode = null;
	}

	private static void startGliding(ClientGameTestContext ctx) {
		ctx.waitTicks(3);
		ctx.runOnClient(mc -> {
			LocalPlayer p = mc.player;
			if (p.tryToStartFallFlying()) {
				p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
			}
		});
		ctx.waitTicks(2);
	}

	private static void prepare(ClientGameTestContext ctx, TestServerContext server, int x, int y, int z) {
		server.runCommand("clear @a");
		server.runCommand("effect clear @a");
		server.runCommand("item replace entity @a armor.chest with elytra[unbreakable={}]");
		server.runCommand("forceload add " + (x - 32) + " " + (z - 32) + " " + (x + 32) + " " + (z + 32));
		server.runCommand("tp @a " + x + " " + y + " " + z + " -90 0");
		server.runCommand("effect give @a instant_health 1 10");
		ctx.waitTicks(30);
	}

	private static void cruiseAndLand(ClientGameTestContext ctx, TestServerContext server, String name, int x0, int z0, int distance) {
		prepare(ctx, server, x0, 200, z0);
		startGliding(ctx);
		int tx = x0 + distance;
		ctx.runOnClient(mc -> {
			resetRecorder(mc);
			recording = true;
			Autopilot.get().flyTo(mc.player, List.of(new Stop("test", tx + 0.5, z0 + 0.5)), false);
		});
		ctx.waitTicks(200);
		ctx.takeScreenshot("glidepilot-" + name.replace(' ', '-'));
		ctx.waitFor(mc -> !Autopilot.get().active(), 20 * 300);
		ctx.waitTicks(20);
		recording = false;
		String r = ctx.computeOnClient(mc -> {
			LocalPlayer p = mc.player;
			double flown = p.getX() - startX;
			return String.format("%s: landed=%s onGround=%s pos=%s missBy=%.1f flown=%.0f glideTicks=%d avg=%.1fm/s maxY=%.0f minClearance=%.0f hpLost=%.1f start=%.1f dmg=[%s] modes=%s%n   trace:%s",
				name, p.onGround() || p.isInWater(), p.onGround(), p.blockPosition().toShortString(),
				Math.hypot(p.getX() - (tx + 0.5), p.getZ() - (z0 + 0.5)), flown, glideTicks, distance / (glideTicks / 20.0),
				maxY, minClearance, startHealth - minHealth, startHealth, dmg, modes, trace);
		});
		log(r);
	}

	private static void learningTest(ClientGameTestContext ctx, TestServerContext server) {
		GlideConfig.get().learning = true;
		ctx.runOnClient(mc -> mc.gui.setScreen(new GlideConfigScreen(null)));
		ctx.waitTicks(5);
		ctx.takeScreenshot("glidepilot-config-learning");
		ctx.runOnClient(mc -> mc.gui.setScreen(null));

		// 1. Starting a glide engages coaching, and the mod never moves the camera.
		prepare(ctx, server, 16000, 200, 2000);
		holdPitch = -60f;
		startGliding(ctx);
		ctx.waitTicks(40);
		ctx.takeScreenshot("glidepilot-learning-far");
		log(ctx.computeOnClient(mc -> String.format("learning engage: mode=%s pitch=%.1f advice=%.1f err=%.1f color=%06X hud=%s",
			Autopilot.get().mode(), mc.player.getXRot(), Autopilot.get().advicePitch(), GlideHud.error(mc.player, Autopilot.get()),
			GlideHud.color(GlideHud.error(mc.player, Autopilot.get())),
			GlideHud.lines(mc).stream().map(c -> c.getString()).toList())));
		holdPitch = Float.NaN;
		ctx.runOnClient(mc -> mc.player.setXRot(10f));
		ctx.waitTicks(5);
		log(ctx.computeOnClient(mc -> String.format("learning hands-off: pitch=%.1f (expect 10.0)", mc.player.getXRot())));

		// 2. A simulated pilot that follows the marker should fly and land like the autopilot.
		ctx.runOnClient(mc -> {
			resetRecorder(mc);
			recording = true;
			follower = true;
			Autopilot.get().flyTo(mc.player, List.of(new Stop("coach", 17500.5, 2000.5)), false);
		});
		ctx.waitTicks(300);
		ctx.takeScreenshot("glidepilot-learning-close");
		ctx.waitFor(mc -> !Autopilot.get().active(), 20 * 300);
		ctx.waitTicks(20);
		recording = false;
		follower = false;
		log(ctx.computeOnClient(mc -> String.format("learning follow: onGround=%s pos=%s missBy=%.1f hpLost=%.1f coachTicks=%d green=%.0f%% red=%.0f%% avgErr=%.1f modes=%s%n   trace:%s",
			mc.player.onGround(), mc.player.blockPosition().toShortString(), Math.hypot(mc.player.getX() - 17500.5, mc.player.getZ() - 2000.5),
			startHealth - minHealth, coachTicks, 100.0 * greenTicks / Math.max(1, coachTicks), 100.0 * redTicks / Math.max(1, coachTicks),
			errSum / Math.max(1, coachTicks), modes, trace)));
		GlideConfig.get().learning = false;
	}

	private static void takeoffTest(ClientGameTestContext ctx, TestServerContext server) {
		prepare(ctx, server, 8000, -50, 2000);
		server.runCommand("tp @a 8000 -60 2000 -90 0");
		server.runCommand("give @a firework_rocket[fireworks={flight_duration:1,explosions:[{shape:\"star\"}]}] 16");
		server.runCommand("give @a firework_rocket[fireworks={flight_duration:2}] 32");
		ctx.waitTicks(30);
		ctx.runOnClient(mc -> {
			resetRecorder(mc);
			recording = true;
			Autopilot.get().flyTo(mc.player, List.of(new Stop("takeoff target", 8800.5, 2000.5)), true);
		});
		ctx.waitTicks(60);
		ctx.takeScreenshot("glidepilot-takeoff");
		ctx.waitFor(mc -> !Autopilot.get().active(), 20 * 200);
		ctx.waitTicks(20);
		recording = false;
		log(ctx.computeOnClient(mc -> {
			int plain = 0;
			int star = 0;
			for (int i = 0; i < 41; i++) {
				var s = mc.player.getInventory().getItem(i);
				if (!s.is(net.minecraft.world.item.Items.FIREWORK_ROCKET)) continue;
				var fw = s.get(net.minecraft.core.component.DataComponents.FIREWORKS);
				if (fw != null && !fw.explosions().isEmpty()) star += s.getCount();
				else plain += s.getCount();
			}
			return String.format("takeoff: plainRocketsLeft=%d/32 starRocketsLeft=%d/16 pos=%s maxY=%.0f hpLost=%.1f start=%.1f dmg=[%s] modes=%s%n   trace:%s",
				plain, star, mc.player.blockPosition().toShortString(), maxY, startHealth - minHealth, startHealth, dmg, modes, trace);
		}));
	}

	private static void swapTest(ClientGameTestContext ctx, TestServerContext server) {
		prepare(ctx, server, 12000, 200, 2000);
		server.runCommand("item replace entity @a armor.chest with elytra[damage=420]");
		server.runCommand("item replace entity @a inventory.5 with elytra[damage=10]");
		ctx.waitTicks(10);
		startGliding(ctx);
		ctx.runOnClient(mc -> {
			resetRecorder(mc);
			recording = true;
			Autopilot.get().flyTo(mc.player, List.of(new Stop("far", 14000.5, 2000.5)), false);
		});
		ctx.waitTicks(20 * 20);
		String hud = ctx.computeOnClient(mc -> GlideHud.lines(mc).stream().map(c -> c.getString()).toList().toString());
		ctx.takeScreenshot("glidepilot-hud");
		String r = ctx.computeOnClient(mc -> {
			var chest = mc.player.getItemBySlot(EquipmentSlot.CHEST);
			var old = mc.player.getInventory().getItem(14);
			return "swap: chestDamage=" + chest.getDamageValue() + " oldElytraDamage=" + old.getDamageValue()
				+ " stillGliding=" + mc.player.isFallFlying() + " mode=" + Autopilot.get().mode() + " hud=" + hud;
		});
		log(r);
		ctx.runOnClient(mc -> Autopilot.get().land(mc.player));
		ctx.waitFor(mc -> !Autopilot.get().active(), 20 * 200);
		ctx.waitTicks(20);
		recording = false;
		log(ctx.computeOnClient(mc -> String.format("land command: onGround=%s pos=%s hpLost=%.1f start=%.1f dmg=[%s] modes=%s",
			mc.player.onGround(), mc.player.blockPosition().toShortString(), startHealth - minHealth, startHealth, dmg, modes)));
	}
}
