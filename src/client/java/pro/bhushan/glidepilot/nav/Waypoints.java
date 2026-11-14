package pro.bhushan.glidepilot.nav;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import pro.bhushan.glidepilot.GlidePilot;

/**
 * Named destinations, kept separately for each server or singleplayer world and each
 * dimension, so "home" on one server never points at "home" on another.
 * Stored in config/glidepilot-waypoints.json.
 */
public final class Waypoints {
	public record Point(int x, int z) {
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type TYPE = new TypeToken<TreeMap<String, TreeMap<String, Point>>>() {
	}.getType();
	private static TreeMap<String, TreeMap<String, Point>> data;

	private Waypoints() {
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("glidepilot-waypoints.json");
	}

	private static TreeMap<String, TreeMap<String, Point>> data() {
		if (data == null) {
			data = new TreeMap<>();
			if (Files.exists(path())) {
				try (Reader r = Files.newBufferedReader(path())) {
					TreeMap<String, TreeMap<String, Point>> read = GSON.fromJson(r, TYPE);
					if (read != null) data = read;
				} catch (Exception e) {
					GlidePilot.LOGGER.warn("Could not read waypoints", e);
				}
			}
		}
		return data;
	}

	private static void save() {
		try {
			Files.createDirectories(path().getParent());
			try (Writer w = Files.newBufferedWriter(path())) {
				GSON.toJson(data(), TYPE, w);
			}
		} catch (Exception e) {
			GlidePilot.LOGGER.warn("Could not write waypoints", e);
		}
	}

	/** "server-address|dimension" or "sp:world-folder|dimension". */
	public static String scope() {
		Minecraft mc = Minecraft.getInstance();
		String world;
		ServerData server = mc.getCurrentServer();
		if (server != null) {
			world = server.ip;
		} else if (mc.getSingleplayerServer() != null) {
			world = "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else {
			world = "unknown";
		}
		String dim = mc.level == null ? "?" : mc.level.dimension().identifier().toString();
		return world + "|" + dim;
	}

	public static Map<String, Point> current() {
		return data().getOrDefault(scope(), new TreeMap<>());
	}

	public static Point get(String name) {
		return current().get(name.toLowerCase());
	}

	public static void set(String name, int x, int z) {
		data().computeIfAbsent(scope(), k -> new TreeMap<>()).put(name.toLowerCase(), new Point(x, z));
		save();
	}

	public static boolean remove(String name) {
		TreeMap<String, Point> map = data().get(scope());
		if (map == null || map.remove(name.toLowerCase()) == null) return false;
		save();
		return true;
	}
}
