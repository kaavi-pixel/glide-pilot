package pro.bhushan.glidepilot.flight;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;

/** Reads the client's loaded chunks to find the highest ground along the planned path. */
public final class Terrain {
	private Terrain() {
	}

	/** Result of scanning a corridor ahead. */
	public record Scan(int highestGround, boolean allLoaded, double loadedDistance) {
	}

	public static boolean loaded(ClientLevel level, double x, double z) {
		return level.hasChunk(SectionPos.blockToSectionCoord(Mth.floor(x)), SectionPos.blockToSectionCoord(Mth.floor(z)));
	}

	/** Top of the highest solid or leaf block in the column, or minY when unloaded. */
	public static int groundAt(ClientLevel level, double x, double z) {
		if (!loaded(level, x, z)) return level.getMinY();
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z));
	}

	/**
	 * Scans a corridor from (x, z) along yaw for {@code distance} blocks, {@code halfWidth} blocks
	 * to each side. Samples every 4 blocks, which is finer than any terrain feature tall enough to
	 * matter at cruise height.
	 */
	public static Scan corridor(ClientLevel level, double x, double z, float yawDeg, double distance, double halfWidth) {
		double rad = Math.toRadians(yawDeg);
		double fx = -Math.sin(rad);
		double fz = Math.cos(rad);
		double sx = -fz;
		double sz = fx;
		int highest = level.getMinY();
		boolean allLoaded = true;
		double loadedDistance = distance;
		for (double d = 0; d <= distance; d += 4) {
			for (double w = -halfWidth; w <= halfWidth; w += Math.max(4, halfWidth)) {
				double px = x + fx * d + sx * w;
				double pz = z + fz * d + sz * w;
				if (!loaded(level, px, pz)) {
					if (allLoaded) loadedDistance = d;
					allLoaded = false;
					continue;
				}
				highest = Math.max(highest, groundAt(level, px, pz));
			}
		}
		return new Scan(highest, allLoaded, loadedDistance);
	}

	/** Highest ground in a disc, used to keep a landing spiral clear of hills. */
	public static int disc(ClientLevel level, double cx, double cz, double radius) {
		int highest = level.getMinY();
		for (double dx = -radius; dx <= radius; dx += 4) {
			for (double dz = -radius; dz <= radius; dz += 4) {
				if (dx * dx + dz * dz > radius * radius) continue;
				highest = Math.max(highest, groundAt(level, cx + dx, cz + dz));
			}
		}
		return highest;
	}
}
