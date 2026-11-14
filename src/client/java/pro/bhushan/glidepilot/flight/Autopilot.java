package pro.bhushan.glidepilot.flight;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import pro.bhushan.glidepilot.config.GlideConfig;

/**
 * The flight controller. Runs once per client tick, after the player has moved, and sets the
 * rotation used for the next tick's elytra physics. Rendering interpolates between the previous
 * and new rotation, so the camera moves smoothly at any frame rate.
 */
public final class Autopilot {
	public enum Mode { OFF, TAKEOFF, CRUISE, WAIT, LOITER, DESCENT, FLARE, FINAL }

	public enum Style {
		LOW("cruise_low", 40), MEDIUM("cruise_medium", 80), HIGH("cruise_high", 140);

		final String file;
		final int swing;

		Style(String file, int swing) {
			this.file = file;
			this.swing = swing;
		}
	}

	public record Stop(String label, double x, double z) {
	}

	private static final double ARRIVAL_RADIUS = 56;
	private static final double SPIRAL_RADIUS = 30;
	private static final float DESCENT_PITCH = 30f;
	private static final float SLOW_GLIDE_PITCH = -12f;
	private static final double FINAL_SPEED = 0.7;
	private static final int LEARN_LEAD = 3;

	private static final Autopilot INSTANCE = new Autopilot();

	public static Autopilot get() {
		return INSTANCE;
	}

	private GlideProfile[] cruiseProfiles;
	private GlideProfile climbProfile;
	private GlideProfile active;
	private int cycleTick;
	private boolean climbing;
	private final double[] recentY = new double[400];
	private int recentIndex;
	private int recentCount;

	private Mode mode = Mode.OFF;
	private final Deque<Stop> route = new ArrayDeque<>();
	private Stop target;
	private LandingSite.Site site;
	private int siteGround;
	private boolean chainLanding = true;

	private float lastYaw;
	private float lastPitch;
	private boolean rotationOwned;

	private int ticksInMode;
	private int rocketCooldown;
	private int takeoffTargetY;
	private int equipmentCheck;
	private boolean warnedDurability;

	private double avgSpeed;
	private double floorY;
	private double groundAhead;
	private String waitingReason;

	// Learning mode: the controller still runs, but only reports what it would do.
	private float wantYaw = Float.NaN;
	private float wantPitch = Float.NaN;
	private float adviceYaw = Float.NaN;
	private float advicePitch = Float.NaN;
	private float pitchError = Float.NaN;
	private float cruiseError = Float.NaN;
	private String hint;
	private boolean wasGliding;

	private Autopilot() {
	}

	private void loadProfiles() {
		if (cruiseProfiles != null) return;
		cruiseProfiles = new GlideProfile[Style.values().length];
		for (Style s : Style.values()) cruiseProfiles[s.ordinal()] = GlideProfile.load(s.file);
		climbProfile = GlideProfile.load("climb");
	}

	// ---- public controls ----

	public Mode mode() {
		return mode;
	}

	public boolean active() {
		return mode != Mode.OFF;
	}

	public Stop target() {
		return target;
	}

	public List<Stop> route() {
		return List.copyOf(route);
	}

	public LandingSite.Site site() {
		return site;
	}

	public double avgSpeed() {
		return avgSpeed;
	}

	public double floorY() {
		return floorY;
	}

	public double groundAhead() {
		return groundAhead;
	}

	public String waitingReason() {
		return waitingReason;
	}

	public String profileName() {
		return active == null ? "" : active.name();
	}

	public boolean learning() {
		return GlideConfig.get().learning;
	}

	/** Yaw the autopilot wants, or NaN when it has no opinion. */
	public float adviceYaw() {
		return adviceYaw;
	}

	/** Pitch the autopilot wants, a few ticks ahead so there is time to react. NaN when idle. */
	public float advicePitch() {
		return advicePitch;
	}

	/** How far the current pitch is from the plan, in degrees, with a short timing window. */
	public float pitchError() {
		return pitchError;
	}

	/** Lang key for an action the pilot should take, or null. */
	public String hint() {
		return hint;
	}

	private Style style() {
		return GlideConfig.get().style;
	}

	/** Toggle key: engage free cruise while gliding, or turn everything off. */
	public void toggle(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) return;
		if (active()) {
			disengage(p, Component.translatable("glidepilot.msg.off"), false);
			return;
		}
		if (!p.isFallFlying()) {
			say(p, Component.translatable("glidepilot.msg.notGliding").withStyle(ChatFormatting.RED));
			return;
		}
		if (!checkDimension(p)) return;
		target = null;
		route.clear();
		engageCruise(p);
		say(p, Component.translatable("glidepilot.msg.on"));
	}

	public void flyTo(LocalPlayer p, List<Stop> stops, boolean takeoff) {
		if (!checkDimension(p)) return;
		route.clear();
		route.addAll(stops);
		target = route.poll();
		chainLanding = true;
		if (takeoff || !p.isFallFlying()) {
			startTakeoff(p);
		} else if (mode == Mode.OFF || mode == Mode.LOITER || mode == Mode.WAIT || mode == Mode.CRUISE) {
			engageCruise(p);
		} else {
			setMode(Mode.CRUISE);
		}
		say(p, Component.translatable("glidepilot.msg.flyingTo", target.label(), (int) target.x(), (int) target.z())
			.withStyle(ChatFormatting.GREEN));
	}

	public void addStop(LocalPlayer p, Stop stop) {
		if (target == null) {
			flyTo(p, List.of(stop), false);
		} else {
			route.add(stop);
			say(p, Component.translatable("glidepilot.msg.added", stop.label(), route.size()));
		}
	}

	public void land(LocalPlayer p) {
		if (!p.isFallFlying()) {
			say(p, Component.translatable("glidepilot.msg.notGliding").withStyle(ChatFormatting.RED));
			return;
		}
		Vec3 ahead = p.position().add(p.getDeltaMovement().multiply(1, 0, 1).scale(20));
		route.clear();
		target = new Stop("here", ahead.x, ahead.z);
		beginLanding(p);
	}

	public void startTakeoff(LocalPlayer p) {
		ItemStack glider = Equipment.worn(p);
		if (glider.isEmpty()) {
			say(p, Component.translatable("glidepilot.msg.noGlider").withStyle(ChatFormatting.RED));
			return;
		}
		if (!p.isFallFlying() && Equipment.rocketCount(p) == 0) {
			say(p, Component.translatable("glidepilot.msg.noRockets").withStyle(ChatFormatting.RED));
			return;
		}
		loadProfiles();
		ClientLevel level = (ClientLevel) p.level();
		int ground = Terrain.groundAt(level, p.getX(), p.getZ());
		takeoffTargetY = Math.max(ground + GlideConfig.get().takeoffHeight, GlideConfig.get().minCruiseY);
		takeoffTargetY = Math.min(takeoffTargetY, GlideConfig.get().maxCruiseY);
		rocketCooldown = 0;
		setMode(Mode.TAKEOFF);
		ownRotation(p);
	}

	public void stop(LocalPlayer p) {
		disengage(p, Component.translatable("glidepilot.msg.off"), false);
	}

	public void clearRoute() {
		route.clear();
	}

	// ---- internals ----

	private boolean checkDimension(LocalPlayer p) {
		if (p.level().dimensionType().hasCeiling() && p.getY() < p.level().dimensionType().logicalHeight()) {
			say(p, Component.translatable("glidepilot.msg.ceiling").withStyle(ChatFormatting.RED));
			return false;
		}
		return true;
	}

	private void engageCruise(LocalPlayer p) {
		loadProfiles();
		climbing = false;
		recentCount = 0;
		active = cruiseProfiles[style().ordinal()];
		Vec3 v = p.getDeltaMovement();
		cycleTick = active.closestTick(v.length(), v.y);
		setMode(Mode.CRUISE);
		ownRotation(p);
	}

	private void ownRotation(LocalPlayer p) {
		lastYaw = p.getYRot();
		lastPitch = p.getXRot();
		rotationOwned = true;
	}

	private void setMode(Mode m) {
		if (mode != m) ticksInMode = 0;
		mode = m;
		waitingReason = null;
	}

	private void disengage(LocalPlayer p, Component why, boolean warn) {
		boolean was = active();
		setMode(Mode.OFF);
		rotationOwned = false;
		target = null;
		site = null;
		route.clear();
		active = null;
		clearAdvice();
		if (was && p != null) say(p, warn ? why.copy().withStyle(ChatFormatting.RED) : why);
	}

	private void clearAdvice() {
		adviceYaw = Float.NaN;
		advicePitch = Float.NaN;
		pitchError = Float.NaN;
		hint = null;
	}

	private static void say(LocalPlayer p, Component c) {
		p.sendOverlayMessage(c);
	}

	private void beginLanding(LocalPlayer p) {
		ClientLevel level = (ClientLevel) p.level();
		site = LandingSite.find(level, Mth.floor(target.x()), Mth.floor(target.z()), GlideConfig.get().landingSearchRadius);
		siteGround = Math.max(site.pos().getY(), Terrain.disc(level, site.pos().getX() + 0.5, site.pos().getZ() + 0.5, SPIRAL_RADIUS + 8));
		setMode(Mode.DESCENT);
		if (!site.verified()) {
			say(p, Component.translatable("glidepilot.msg.noSafeSite").withStyle(ChatFormatting.GOLD));
		} else {
			say(p, Component.translatable(site.water() ? "glidepilot.msg.landingWater" : "glidepilot.msg.landing",
				site.pos().getX(), site.pos().getY(), site.pos().getZ()).withStyle(ChatFormatting.AQUA));
		}
	}

	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			if (mode != Mode.OFF) disengage(null, Component.empty(), false);
			return;
		}
		if (mc.isPaused()) return;
		boolean gliding = p.isFallFlying();
		if (gliding && !wasGliding && mode == Mode.OFF && learning() && !p.level().dimensionType().hasCeiling()) {
			target = null;
			route.clear();
			engageCruise(p);
			say(p, Component.translatable("glidepilot.msg.learning"));
		}
		wasGliding = gliding;
		if (mode == Mode.OFF) return;
		wantYaw = Float.NaN;
		wantPitch = Float.NaN;
		cruiseError = Float.NaN;
		hint = null;
		ticksInMode++;
		if (rocketCooldown > 0) rocketCooldown--;

		GlideConfig cfg = GlideConfig.get();
		if (rotationOwned && cfg.mouseOverride && !cfg.learning
			&& (Math.abs(Mth.wrapDegrees(p.getYRot() - lastYaw)) > 1.5f || Math.abs(p.getXRot() - lastPitch) > 1.5f)) {
			disengage(p, Component.translatable("glidepilot.msg.manual"), true);
			return;
		}

		Vec3 v = p.getDeltaMovement();
		double hSpeed = v.horizontalDistance();
		avgSpeed = avgSpeed * 0.985 + hSpeed * 20 * 0.015;

		if (mode == Mode.TAKEOFF) {
			tickTakeoff(mc, p, v);
			return;
		}

		if (!p.isFallFlying()) {
			boolean landing = mode == Mode.DESCENT || mode == Mode.FLARE || mode == Mode.FINAL;
			if (landing && (p.onGround() || p.isInWater())) {
				p.playSound(SoundEvents.NOTE_BLOCK_CHIME.value(), 1f, 1.2f);
				disengage(p, Component.translatable("glidepilot.msg.landed").withStyle(ChatFormatting.GREEN), false);
			} else {
				disengage(p, Component.translatable("glidepilot.msg.glideEnded"), true);
			}
			return;
		}

		if (--equipmentCheck <= 0) {
			equipmentCheck = 20;
			checkGlider(mc, p);
			if (mode == Mode.OFF) return;
		}

		ClientLevel level = mc.level;
		float yaw = p.getYRot();
		float pitch = p.getXRot();

		switch (mode) {
			case CRUISE, WAIT, LOITER -> {
				Terrain.Scan scan = Terrain.corridor(level, p.getX(), p.getZ(), headingYaw(p, v), Math.max(160, hSpeed * 20 * 8), 12);
				groundAhead = scan.highestGround();
				floorY = Math.min(cfg.maxCruiseY, Math.max(cfg.minCruiseY, scan.highestGround() + cfg.terrainClearance));

				if (mode != Mode.LOITER) {
					if (cfg.waitForChunks && !scan.allLoaded() && scan.loadedDistance() < 96) {
						if (mode != Mode.WAIT) setMode(Mode.WAIT);
						waitingReason = "chunks";
					} else if (mode == Mode.WAIT && (scan.allLoaded() || scan.loadedDistance() >= 150)) {
						setMode(Mode.CRUISE);
					}
				}

				if (mode == Mode.CRUISE && target != null) {
					double d = horizontalDistance(p, target.x(), target.z());
					if (d < ARRIVAL_RADIUS) {
						if (!route.isEmpty()) {
							target = route.poll();
							say(p, Component.translatable("glidepilot.msg.next", target.label()).withStyle(ChatFormatting.GREEN));
						} else if (cfg.autoLand && chainLanding) {
							p.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1f, 1f);
							beginLanding(p);
							break;
						} else {
							setMode(Mode.LOITER);
							say(p, Component.translatable("glidepilot.msg.loiter", target.label()).withStyle(ChatFormatting.GREEN));
						}
					}
				}

				if (mode == Mode.WAIT) {
					yaw = yaw + (float) cfg.turnRate;
					wantYaw = yaw + 30f;
				} else if (mode == Mode.LOITER && target != null) {
					yaw = steerTo(yaw, orbitYaw(p, target.x(), target.z(), 48, true), cfg.turnRate);
				} else if (target != null) {
					yaw = steerTo(yaw, bearing(p, target.x(), target.z()), cfg.turnRate);
				}
				pitch = cruisePitch(mc, p, v, level, yaw);
			}
			case DESCENT -> {
				double cx = site.pos().getX() + 0.5;
				double cz = site.pos().getZ() + 0.5;
				yaw = steerTo(yaw, orbitYaw(p, cx, cz, SPIRAL_RADIUS, false), cfg.turnRate);
				pitch = Mth.clamp(DESCENT_PITCH + 40f * (float) (v.y + 0.42), 5f, 42f);
				pitch = approachTo(p.getXRot(), pitch, 4f);
				if (ticksInMode % 40 == 0) {
					siteGround = Math.max(site.pos().getY(), Terrain.disc(level, cx, cz, SPIRAL_RADIUS + 8));
				}
				if (p.getY() < siteGround + 14) setMode(Mode.FLARE);
			}
			case FLARE -> {
				double cx = site.pos().getX() + 0.5;
				double cz = site.pos().getZ() + 0.5;
				yaw = steerTo(yaw, orbitYaw(p, cx, cz, 10, false), cfg.turnRate);
				pitch = approachTo(p.getXRot(), SLOW_GLIDE_PITCH, 4f);
				if (hSpeed < FINAL_SPEED) setMode(Mode.FINAL);
			}
			case FINAL -> {
				double cx = site.pos().getX() + 0.5;
				double cz = site.pos().getZ() + 0.5;
				double d = horizontalDistance(p, cx, cz);
				float want = d < 2.5 ? yaw + 8f : bearing(p, cx, cz);
				yaw = steerTo(yaw, want, 8.0);
				pitch = approachTo(p.getXRot(), SLOW_GLIDE_PITCH, 4f);
			}
			default -> pitch = p.getXRot();
		}
		apply(p, yaw, pitch);
	}

	private void tickTakeoff(Minecraft mc, LocalPlayer p, Vec3 v) {
		float yaw = p.getYRot();
		if (target != null) yaw = steerTo(yaw, bearing(p, target.x(), target.z()), GlideConfig.get().turnRate);
		if (!p.isFallFlying()) {
			if (learning()) {
				hint = p.onGround() ? "glidepilot.learn.jump" : "glidepilot.learn.open";
				wantPitch = -90f;
				apply(p, yaw, -90f);
				return;
			}
			if (ticksInMode > 60) {
				disengage(p, Component.translatable("glidepilot.msg.takeoffFailed"), true);
				return;
			}
			if (p.onGround()) {
				if (ticksInMode < 40) p.jumpFromGround();
			} else if (v.y < 0.2 && p.tryToStartFallFlying()) {
				p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
			}
			apply(p, yaw, approachTo(p.getXRot(), -90f, 15f));
			return;
		}
		float pitch = approachTo(p.getXRot(), -88f, 15f);
		if (p.getY() >= takeoffTargetY) {
			say(p, Component.translatable("glidepilot.msg.cruising"));
			apply(p, yaw, pitch);
			engageCruise(p);
			return;
		}
		if (learning()) {
			if (v.y < 0.9) hint = p.getXRot() < -70f ? "glidepilot.learn.rocket" : "glidepilot.learn.lookUp";
			apply(p, yaw, pitch);
			return;
		}
		if (rocketCooldown == 0 && v.y < 0.9 && p.getXRot() < -70f) {
			if (Equipment.fireRocket(mc, p)) {
				rocketCooldown = 15;
			} else if (v.y < 0) {
				say(p, Component.translatable("glidepilot.msg.outOfRockets").withStyle(ChatFormatting.GOLD));
				apply(p, yaw, pitch);
				engageCruise(p);
				return;
			}
		}
		apply(p, yaw, pitch);
	}

	private float cruisePitch(Minecraft mc, LocalPlayer p, Vec3 v, ClientLevel level, float yaw) {
		GlideConfig cfg = GlideConfig.get();
		GlideProfile cruise = cruiseProfiles[style().ordinal()];

		recentY[recentIndex] = p.getY();
		recentIndex = (recentIndex + 1) % recentY.length;
		recentCount = Math.min(recentCount + 1, recentY.length);
		int window = Math.min(recentCount, cruise.length());
		double peak = Double.NEGATIVE_INFINITY;
		for (int i = 1; i <= window; i++) peak = Math.max(peak, recentY[Math.floorMod(recentIndex - i, recentY.length)]);
		double needed = floorY + style().swing;

		boolean wantClimb = climbing ? peak < needed + 25 : peak < needed;
		if (p.getY() > cfg.maxCruiseY) wantClimb = false;
		GlideProfile want = wantClimb ? climbProfile : cruise;
		climbing = wantClimb;
		double speed = v.length();
		if (want != active) {
			active = want;
			cycleTick = active.closestTick(speed, v.y);
		} else if (Math.abs(speed - active.speedAt(cycleTick)) > 0.35) {
			cycleTick = active.closestTick(speed, v.y);
		}
		float pitch = active.pitchAt(cycleTick);
		float lead = active.pitchAt((cycleTick + LEARN_LEAD) % active.length());
		if (cfg.learning) {
			// Grade against the best match within a short window, so reaction time is not punished.
			float best = Float.MAX_VALUE;
			for (int k = -2; k <= LEARN_LEAD + 3; k++) {
				best = Math.min(best, Math.abs(p.getXRot() - active.pitchAt(Math.floorMod(cycleTick + k, active.length()))));
			}
			cruiseError = best;
		}
		cycleTick = (cycleTick + 1) % active.length();
		boolean guarded = false;

		// Terrain guard. The profile's dive is only safe if the ground in front stays well below.
		double look = Math.max(24, v.horizontalDistance() * 40);
		Terrain.Scan near = Terrain.corridor(level, p.getX(), p.getZ(), yaw, look, 4);
		double clearance = p.getY() - near.highestGround();
		if (pitch > 0 && clearance < cfg.terrainClearance * 0.5) {
			pitch = -35f;
			guarded = true;
			if (!climbing) {
				climbing = true;
				active = climbProfile;
				cycleTick = active.closestTick(speed, v.y);
			}
		}
		if (clearance < 10) {
			pitch = Math.min(pitch, -40f);
			guarded = true;
			if (cfg.learning) {
				if (speed < 1.2) hint = "glidepilot.learn.rocket";
				hint = hint == null ? "glidepilot.learn.pullUp" : hint;
			} else if (cfg.rocketAssist && rocketCooldown == 0 && speed < 1.2 && Equipment.fireRocket(mc, p)) {
				rocketCooldown = 30;
			}
		}
		if (guarded) {
			cruiseError = Float.NaN;
			wantPitch = pitch;
		} else {
			wantPitch = lead;
		}
		return Mth.clamp(pitch, -90f, 90f);
	}

	private void checkGlider(Minecraft mc, LocalPlayer p) {
		GlideConfig cfg = GlideConfig.get();
		ItemStack worn = Equipment.worn(p);
		if (worn.isEmpty()) return;
		int rem = Equipment.remaining(worn);
		if (rem > cfg.lowDurability) {
			warnedDurability = false;
			return;
		}
		if (cfg.swapGlider) {
			int spare = Equipment.spareGlider(p, cfg.lowDurability);
			if (spare >= 0 && Equipment.swapIn(mc, p, spare)) {
				say(p, Component.translatable("glidepilot.msg.swapped").withStyle(ChatFormatting.AQUA));
				return;
			}
		}
		boolean landing = mode == Mode.DESCENT || mode == Mode.FLARE || mode == Mode.FINAL;
		if (!landing) {
			target = new Stop("here", p.getX(), p.getZ());
			route.clear();
			beginLanding(p);
			say(p, Component.translatable("glidepilot.msg.gliderLow").withStyle(ChatFormatting.RED));
		} else if (!warnedDurability) {
			warnedDurability = true;
			say(p, Component.translatable("glidepilot.msg.gliderLow").withStyle(ChatFormatting.RED));
		}
	}

	private void apply(LocalPlayer p, float yaw, float pitch) {
		if (GlideConfig.get().learning) {
			adviceYaw = Float.isNaN(wantYaw) ? yaw : wantYaw;
			advicePitch = Mth.clamp(Float.isNaN(wantPitch) ? pitch : wantPitch, -90f, 90f);
			pitchError = Float.isNaN(cruiseError) ? Math.abs(p.getXRot() - advicePitch) : cruiseError;
			rotationOwned = false;
			return;
		}
		clearAdvice();
		p.setYRot(yaw);
		p.setXRot(Mth.clamp(pitch, -90f, 90f));
		lastYaw = p.getYRot();
		lastPitch = p.getXRot();
		rotationOwned = true;
	}

	// ---- geometry ----

	private static float headingYaw(LocalPlayer p, Vec3 v) {
		return v.horizontalDistanceSqr() > 0.01 ? (float) (Mth.atan2(v.z, v.x) * Mth.RAD_TO_DEG) - 90f : p.getYRot();
	}

	static double horizontalDistance(LocalPlayer p, double x, double z) {
		double dx = x - p.getX();
		double dz = z - p.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	static float bearing(LocalPlayer p, double x, double z) {
		return (float) (Mth.atan2(z - p.getZ(), x - p.getX()) * Mth.RAD_TO_DEG) - 90f;
	}

	/**
	 * Heading that converges onto a circle of the given radius around (cx, cz): tangent to the
	 * circle, tilted inward when outside it and outward when inside.
	 */
	private static float orbitYaw(LocalPlayer p, double cx, double cz, double radius, boolean clockwise) {
		double d = horizontalDistance(p, cx, cz);
		float toCenter = bearing(p, cx, cz);
		if (d > radius * 2.5) return toCenter;
		double err = Mth.clamp((d - radius) / radius, -1, 1);
		float offset = (float) (90 - 60 * err);
		return clockwise ? toCenter - offset : toCenter + offset;
	}

	private float steerTo(float current, float desired, double rate) {
		wantYaw = desired;
		return steer(current, desired, rate);
	}

	private float approachTo(float current, float desired, float rate) {
		wantPitch = desired;
		return approach(current, desired, rate);
	}

	private static float steer(float current, float desired, double rate) {
		float diff = Mth.wrapDegrees(desired - current);
		return current + Mth.clamp(diff, (float) -rate, (float) rate);
	}

	private static float approach(float current, float desired, float rate) {
		return current + Mth.clamp(desired - current, -rate, rate);
	}
}
