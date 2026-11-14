package pro.bhushan.glidepilot.flight;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Picks a block near the destination that is safe to touch down on. */
public final class LandingSite {
	private LandingSite() {
	}

	/** {@code pos} is the air block the player will stand in. */
	public record Site(BlockPos pos, boolean water, boolean verified) {
	}

	private static final Block[] HAZARDS = {
		Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.FIRE, Blocks.SOUL_FIRE,
		Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW, Blocks.POINTED_DRIPSTONE, Blocks.WITHER_ROSE, Blocks.COBWEB,
		Blocks.LAVA, Blocks.LAVA_CAULDRON
	};

	private static boolean hazard(BlockState state) {
		if (state.getFluidState().is(FluidTags.LAVA)) return true;
		for (Block b : HAZARDS) if (state.is(b)) return true;
		return false;
	}

	/**
	 * Checks one column. A site is safe when the surface is not a hazard, is not a tree crown,
	 * the 3x3 around it is within one block of its height (so the last metres of a slow glide do
	 * not end in a wall), and two blocks of air sit above it. Water deep enough to stop a fall is
	 * also accepted.
	 */
	static Site check(ClientLevel level, int x, int z) {
		if (!Terrain.loaded(level, x, z)) return null;
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		int noLeaves = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
		if (top != noLeaves) return null;
		BlockPos stand = new BlockPos(x, top, z);
		BlockState surface = level.getBlockState(stand.below());
		if (hazard(surface) || hazard(level.getBlockState(stand))) return null;
		if (!level.getBlockState(stand).getCollisionShape(level, stand).isEmpty()) return null;
		if (!level.getBlockState(stand.above()).getCollisionShape(level, stand.above()).isEmpty()) return null;
		boolean water = surface.getFluidState().is(FluidTags.WATER);
		if (water) {
			BlockState under = level.getBlockState(stand.below(2));
			return under.getFluidState().is(FluidTags.WATER) ? new Site(stand, true, true) : null;
		}
		if (surface.getCollisionShape(level, stand.below()).isEmpty()) return null;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz);
				if (Math.abs(h - top) > 1) return null;
				if (hazard(level.getBlockState(new BlockPos(x + dx, h - 1, z + dz)))) return null;
			}
		}
		return new Site(stand, false, true);
	}

	/**
	 * Searches outward in rings from the destination. Returns the closest safe site, or the
	 * destination column itself marked unverified when nothing within the radius qualifies.
	 */
	public static Site find(ClientLevel level, int cx, int cz, int radius) {
		Site exact = check(level, cx, cz);
		if (exact != null) return exact;
		for (int r = 1; r <= radius; r++) {
			Site best = null;
			double bestDist = Double.MAX_VALUE;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					Site site = check(level, cx + dx, cz + dz);
					if (site == null) continue;
					double d = dx * dx + dz * dz;
					if (d < bestDist) {
						bestDist = d;
						best = site;
					}
				}
			}
			if (best != null) return best;
		}
		int top = Terrain.groundAt(level, cx, cz);
		return new Site(new BlockPos(cx, top, cz), false, false);
	}
}
