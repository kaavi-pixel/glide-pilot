package pro.bhushan.glidepilot.config;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import pro.bhushan.glidepilot.GlidePilot;
import pro.bhushan.glidepilot.flight.Autopilot;

/** Stored in config/glidepilot.json. */
public final class GlideConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static GlideConfig instance;

	/** Cruise profile. Low and medium keep the altitude swing small enough to follow terrain; high is fastest. */
	public Autopilot.Style style = Autopilot.Style.MEDIUM;
	/** Blocks kept between you and the highest terrain on the path ahead. */
	public int terrainClearance = 40;
	/** Lowest absolute altitude used for cruising, regardless of terrain. */
	public int minCruiseY = 100;
	/** Highest altitude the autopilot will climb to. */
	public int maxCruiseY = 600;
	/** Steering rate limit in degrees per tick. */
	public double turnRate = 4.0;
	/** Land automatically at the final destination. Off means circle above it. */
	public boolean autoLand = true;
	/** How far from the destination to look for a safe landing block. */
	public int landingSearchRadius = 24;
	/** Moving the mouse while the autopilot is on hands control back to you. */
	public boolean mouseOverride = true;
	/** Circle in place while the chunks ahead are not loaded yet. */
	public boolean waitForChunks = true;
	/** Swap in a spare glider from the inventory when the worn one gets low. */
	public boolean swapGlider = true;
	/** Durability left at which a glider counts as worn out. */
	public int lowDurability = 10;
	/** Fire a rocket when terrain gets too close and gliding cannot climb in time. */
	public boolean rocketAssist = true;
	/** Allow rockets with firework stars. They damage you, so this is off by default. */
	public boolean allowExplosiveRockets = false;
	/** Altitude above terrain reached by /glide takeoff before cruising. */
	public int takeoffHeight = 60;
	public boolean showHud = true;
	public int hudX = 6;
	public int hudY = 6;
	/** You fly, the autopilot shows the angle it would use and tints the screen by how close you are. */
	public boolean learning = false;
	/** Strength of the learning tint, in percent. */
	public int learningTint = 25;

	public static GlideConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("glidepilot.json");
	}

	private static GlideConfig load() {
		Path path = path();
		GlideConfig config = null;
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				config = GSON.fromJson(reader, GlideConfig.class);
			} catch (Exception e) {
				GlidePilot.LOGGER.warn("Could not read {}, using defaults", path, e);
			}
		}
		if (config == null) config = new GlideConfig();
		config.clamp();
		return config;
	}

	public void clamp() {
		if (style == null) style = Autopilot.Style.MEDIUM;
		terrainClearance = Math.clamp(terrainClearance, 16, 200);
		minCruiseY = Math.clamp(minCruiseY, -64, 1000);
		maxCruiseY = Math.clamp(maxCruiseY, minCruiseY + 40, 2000);
		turnRate = Math.clamp(turnRate, 1.0, 10.0);
		landingSearchRadius = Math.clamp(landingSearchRadius, 0, 64);
		lowDurability = Math.clamp(lowDurability, 2, 100);
		takeoffHeight = Math.clamp(takeoffHeight, 20, 300);
	}

	public void save() {
		clamp();
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (Exception e) {
			GlidePilot.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
