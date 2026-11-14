package pro.bhushan.glidepilot.flight;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A periodic pitch waveform found by trajectory optimization against the 26.3 elytra physics,
 * together with the speed the glider is expected to have at each tick of the cycle.
 *
 * <p>The expected speed lets the autopilot phase-lock: when the real speed drifts from the
 * plan (server lag, a turn, a rocket, a profile switch), it jumps to the tick of the cycle
 * whose expected state matches the real one instead of replaying the cycle blindly.
 */
public final class GlideProfile {
	private final String name;
	private final float[] pitch;
	private final float[] speed;
	private final float[] vy;

	private GlideProfile(String name, float[] pitch, float[] speed, float[] vy) {
		this.name = name;
		this.pitch = pitch;
		this.speed = speed;
		this.vy = vy;
	}

	public String name() {
		return name;
	}

	public int length() {
		return pitch.length;
	}

	public float pitchAt(int tick) {
		return pitch[Math.floorMod(tick, pitch.length)];
	}

	public float speedAt(int tick) {
		return speed[Math.floorMod(tick, speed.length)];
	}

	/**
	 * Tick of the cycle whose expected speed and vertical speed are closest to the given state.
	 * The vertical speed term separates the accelerating dive from the decelerating climb,
	 * which can share the same total speed.
	 */
	public int closestTick(double currentSpeed, double currentVy) {
		int best = 0;
		double bestErr = Double.MAX_VALUE;
		for (int i = 0; i < pitch.length; i++) {
			double ds = speed[i] - currentSpeed;
			double dv = vy[i] - currentVy;
			double err = ds * ds + 0.5 * dv * dv;
			if (err < bestErr) {
				bestErr = err;
				best = i;
			}
		}
		return best;
	}

	public static GlideProfile load(String name) {
		String path = "/assets/glidepilot/profiles/" + name + ".csv";
		try (InputStream in = GlideProfile.class.getResourceAsStream(path)) {
			if (in == null) throw new IllegalStateException("Missing " + path);
			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			List<float[]> rows = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.strip();
				if (line.isEmpty() || line.startsWith("#") || line.startsWith("pitch")) continue;
				String[] parts = line.split(",");
				rows.add(new float[] {Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2])});
			}
			float[] p = new float[rows.size()];
			float[] s = new float[rows.size()];
			float[] v = new float[rows.size()];
			for (int i = 0; i < rows.size(); i++) {
				p[i] = rows.get(i)[0];
				s[i] = rows.get(i)[1];
				v[i] = rows.get(i)[2];
			}
			return new GlideProfile(name, p, s, v);
		} catch (Exception e) {
			throw new IllegalStateException("Could not load glide profile " + name, e);
		}
	}
}
