package pro.bhushan.glidepilot.hud;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import pro.bhushan.glidepilot.config.GlideConfig;
import pro.bhushan.glidepilot.flight.Autopilot;
import pro.bhushan.glidepilot.flight.Equipment;
import pro.bhushan.glidepilot.flight.Terrain;

/** Small flight panel, shown only while gliding. */
public final class GlideHud {
	private GlideHud() {
	}

	private static String time(double seconds) {
		if (Double.isInfinite(seconds)) return "∞";
		int s = (int) Math.round(seconds);
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}

	public static List<Component> lines(Minecraft mc) {
		List<Component> out = new ArrayList<>();
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || !p.isFallFlying() && !Autopilot.get().active()) return out;
		Autopilot ap = Autopilot.get();
		GlideConfig cfg = GlideConfig.get();

		MutableComponent head = Component.translatable("glidepilot.hud.mode." + ap.mode().name().toLowerCase());
		out.add(head.withStyle(ap.active() ? ChatFormatting.AQUA : ChatFormatting.GRAY));
		if (coaching(ap)) {
			float err = error(p, ap);
			out.add(Component.translatable("glidepilot.hud.learn.pitch", angle(p.getXRot()), angle(ap.advicePitch())));
			out.add(Component.translatable("glidepilot.hud.learn.off", Math.round(err)).withColor(color(err)));
			float turn = Mth.wrapDegrees(ap.adviceYaw() - p.getYRot());
			if (Math.abs(turn) >= 8) {
				out.add(Component.translatable(turn > 0 ? "glidepilot.hud.learn.right" : "glidepilot.hud.learn.left", Math.round(Math.abs(turn))));
			}
			if (ap.hint() != null) out.add(Component.translatable(ap.hint()).withStyle(ChatFormatting.GOLD));
		}

		int ground = Terrain.groundAt(mc.level, p.getX(), p.getZ());
		out.add(Component.translatable("glidepilot.hud.altitude", p.getBlockY(), p.getBlockY() - ground));
		double speed = p.getDeltaMovement().horizontalDistance() * 20;
		out.add(Component.translatable("glidepilot.hud.speed", String.format("%.1f", speed), String.format("%.1f", ap.avgSpeed())));

		double eta = Double.NaN;
		Autopilot.Stop t = ap.target();
		if (t != null) {
			double dx = t.x() - p.getX();
			double dz = t.z() - p.getZ();
			double d = Math.sqrt(dx * dx + dz * dz);
			for (Autopilot.Stop s : ap.route()) {
				d += Math.hypot(s.x() - t.x(), s.z() - t.z());
				t = s;
			}
			eta = ap.avgSpeed() > 1 ? d / ap.avgSpeed() : Double.NaN;
			out.add(Component.translatable("glidepilot.hud.target", ap.target().label(), String.format("%,d", (int) d),
				Double.isNaN(eta) ? "--" : time(eta)));
		}
		if (ap.site() != null) {
			var pos = ap.site().pos();
			out.add(Component.translatable("glidepilot.hud.site", pos.getX(), pos.getY(), pos.getZ())
				.withStyle(ap.site().verified() ? ChatFormatting.GREEN : ChatFormatting.GOLD));
		}

		ItemStack glider = Equipment.worn(p);
		if (!glider.isEmpty()) {
			double left = Equipment.secondsLeft(p, glider, cfg.lowDurability);
			boolean short_ = !Double.isNaN(eta) && left < eta;
			out.add(Component.translatable("glidepilot.hud.glider", time(left))
				.withStyle(short_ ? ChatFormatting.RED : ChatFormatting.WHITE));
		}
		int rockets = Equipment.rocketCount(p);
		if (rockets > 0) out.add(Component.translatable("glidepilot.hud.rockets", rockets));
		return out;
	}

	static boolean coaching(Autopilot ap) {
		return ap.learning() && ap.active() && !Float.isNaN(ap.advicePitch());
	}

	private static Component angle(float pitch) {
		int a = Math.round(Math.abs(pitch));
		if (a == 0) return Component.literal("0°");
		return Component.translatable(pitch < 0 ? "glidepilot.hud.learn.up" : "glidepilot.hud.learn.down", a);
	}

	/** Combined error in degrees. Pitch matters most; heading counts a third as much. */
	public static float error(LocalPlayer p, Autopilot ap) {
		float pitch = Float.isNaN(ap.pitchError()) ? Math.abs(p.getXRot() - ap.advicePitch()) : ap.pitchError();
		float yaw = Float.isNaN(ap.adviceYaw()) ? 0 : Math.abs(Mth.wrapDegrees(ap.adviceYaw() - p.getYRot()));
		return Math.max(pitch, yaw / 3f);
	}

	public static final float GOOD = 4f;
	public static final float CLOSE = 10f;
	public static final float FAR = 20f;

	/** Green up to GOOD, yellow at CLOSE, red from FAR, blended in between. */
	public static int color(float err) {
		int green = 0x3CE65A;
		int yellow = 0xFFD83C;
		int red = 0xF03C32;
		if (err <= GOOD) return green;
		if (err <= CLOSE) return lerp(green, yellow, (err - GOOD) / (CLOSE - GOOD));
		if (err <= FAR) return lerp(yellow, red, (err - CLOSE) / (FAR - CLOSE));
		return red;
	}

	private static int lerp(int a, int b, float t) {
		int r = (int) Mth.lerp(t, a >> 16 & 255, b >> 16 & 255);
		int gr = (int) Mth.lerp(t, a >> 8 & 255, b >> 8 & 255);
		int bl = (int) Mth.lerp(t, a & 255, b & 255);
		return r << 16 | gr << 8 | bl;
	}

	private static double[] dir(double yaw, double pitch) {
		double y = Math.toRadians(yaw);
		double x = Math.toRadians(pitch);
		return new double[] {-Math.sin(y) * Math.cos(x), -Math.sin(x), Math.cos(y) * Math.cos(x)};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static void renderLearning(GuiGraphicsExtractor g, Minecraft mc, DeltaTracker delta) {
		Autopilot ap = Autopilot.get();
		LocalPlayer p = mc.player;
		if (p == null || !coaching(ap)) return;
		int w = g.guiWidth();
		int h = g.guiHeight();
		float err = error(p, ap);
		int rgb = color(err);
		int alpha = Math.round(Mth.clamp(GlideConfig.get().learningTint, 0, 100) * 2.55f);
		if (alpha > 0) {
			// Light wash over the whole view, stronger toward the edges so the color reads on any sky.
			g.fill(0, 0, w, h, (alpha / 2) << 24 | rgb);
			int steps = 16;
			int band = Math.max(1, Math.min(w, h) / 5 / steps);
			for (int i = 0; i < steps; i++) {
				int a = Math.min(255, Math.round(alpha * 1.6f * (1f - (float) i / steps)));
				int c = a << 24 | rgb;
				int o = i * band;
				g.fill(o, o, w - o, o + band, c);
				g.fill(o, h - o - band, w - o, h - o, c);
				g.fill(o, o + band, o + band, h - o - band, c);
				g.fill(w - o - band, o + band, w - o, h - o - band, c);
			}
		}

		// Aim marker: where the planned direction is on screen, clamped to the edges.
		float partial = delta.getGameTimeDeltaPartialTick(true);
		double camYaw = p.getViewYRot(partial);
		double camPitch = p.getViewXRot(partial);
		double[] f = dir(camYaw, camPitch);
		double[] up = dir(camYaw, camPitch - 90);
		double[] right = {-Math.cos(Math.toRadians(camYaw)), 0, -Math.sin(Math.toRadians(camYaw))};
		float yaw = Float.isNaN(ap.adviceYaw()) ? (float) camYaw : ap.adviceYaw();
		double[] d = dir(yaw, ap.advicePitch());
		double cz = dot(d, f);
		double cx = dot(d, right);
		double cy = dot(d, up);
		double fov = mc.gameRenderer.mainCamera().getFov();
		double scale = (h / 2.0) / Math.tan(Math.toRadians(fov / 2));
		double sx;
		double sy;
		if (cz > 0.05) {
			sx = cx / cz * scale;
			sy = -cy / cz * scale;
		} else {
			double len = Math.max(1e-6, Math.hypot(cx, cy));
			sx = cx / len * 10000;
			sy = -cy / len * 10000;
		}
		int margin = 12;
		int bottom = 44; // stay above the hotbar and hearts
		double limitY = sy > 0 ? h / 2.0 - bottom : h / 2.0 - margin;
		double k = Math.min(1, Math.min((w / 2.0 - margin) / Math.max(1e-6, Math.abs(sx)), limitY / Math.max(1e-6, Math.abs(sy))));
		int mx = (int) Math.round(w / 2.0 + sx * k);
		int my = (int) Math.round(h / 2.0 + sy * k);
		int color = 0xFF000000 | rgb;
		ring(g, mx, my, 7, 0xC0000000);
		ring(g, mx, my, 6, color);
		g.fill(mx - 1, my - 1, mx + 1, my + 1, color);
	}

	private static void ring(GuiGraphicsExtractor g, int x, int y, int r, int color) {
		g.fill(x - r, y - r, x + r, y - r + 1, color);
		g.fill(x - r, y + r - 1, x + r, y + r, color);
		g.fill(x - r, y - r, x - r + 1, y + r, color);
		g.fill(x + r - 1, y - r, x + r, y + r, color);
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() != null) return;
		renderLearning(g, mc, delta);
		if (!GlideConfig.get().showHud) return;
		List<Component> lines = lines(mc);
		if (lines.isEmpty()) return;
		int x = GlideConfig.get().hudX;
		int y = GlideConfig.get().hudY;
		int w = 0;
		for (Component c : lines) w = Math.max(w, mc.font.width(c));
		g.fill(x - 3, y - 3, x + w + 3, y + lines.size() * 10 + 1, 0x80000000);
		for (Component c : lines) {
			g.text(mc.font, c, x, y, 0xFFFFFFFF, true);
			y += 10;
		}
	}
}
