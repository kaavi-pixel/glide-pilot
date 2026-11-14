package pro.bhushan.glidepilot.config;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import pro.bhushan.glidepilot.flight.Autopilot;

public class GlideConfigScreen extends Screen {
	private final Screen parent;
	private final GlideConfig c = GlideConfig.get();

	public GlideConfigScreen(Screen parent) {
		super(Component.translatable("glidepilot.config.title"));
		this.parent = parent;
	}

	private int col(int i) {
		return this.width / 2 + (i % 2 == 0 ? -155 : 5);
	}

	private int row(int i) {
		return 32 + (i / 2) * 22;
	}

	@Override
	protected void init() {
		int i = 0;
		addRenderableWidget(CycleButton.<Autopilot.Style>builder(s -> Component.translatable("glidepilot.style." + s.name().toLowerCase()), c.style)
			.withValues(Autopilot.Style.values())
			.create(col(i), row(i++), 150, 20, Component.translatable("glidepilot.config.style"), (b, v) -> c.style = v));
		addRenderableWidget(new IntSlider(col(i), row(i++), "glidepilot.config.clearance", 16, 200, c.terrainClearance, v -> c.terrainClearance = v));
		addRenderableWidget(new IntSlider(col(i), row(i++), "glidepilot.config.minY", -64, 400, c.minCruiseY, v -> c.minCruiseY = v));
		addRenderableWidget(new IntSlider(col(i), row(i++), "glidepilot.config.takeoff", 20, 300, c.takeoffHeight, v -> c.takeoffHeight = v));
		addRenderableWidget(new IntSlider(col(i), row(i++), "glidepilot.config.turn", 1, 10, (int) Math.round(c.turnRate), v -> c.turnRate = v));
		addRenderableWidget(new IntSlider(col(i), row(i++), "glidepilot.config.searchRadius", 0, 64, c.landingSearchRadius, v -> c.landingSearchRadius = v));
		addRenderableWidget(toggle(col(i), row(i++), "glidepilot.config.autoLand", c.autoLand, v -> c.autoLand = v));
		addRenderableWidget(toggle(col(i), row(i++), "glidepilot.config.mouse", c.mouseOverride, v -> c.mouseOverride = v));
		addRenderableWidget(toggle(col(i), row(i++), "glidepilot.config.chunks", c.waitForChunks, v -> c.waitForChunks = v));
		addRenderableWidget(toggle(col(i), row(i++), "glidepilot.config.swap", c.swapGlider, v -> c.swapGlider = v));
		addRenderableWidget(toggle(col(i), row(i++), "glidepilot.config.rocketAssist", c.rocketAssist, v -> c.rocketAssist = v));
		addRenderableWidget(toggle(col(i), row(i++), "glidepilot.config.explosive", c.allowExplosiveRockets, v -> c.allowExplosiveRockets = v));
		addRenderableWidget(toggle(col(i), row(i++), "glidepilot.config.hud", c.showHud, v -> c.showHud = v));
		addRenderableWidget(toggle(col(i), row(i++), "glidepilot.config.learning", c.learning, v -> c.learning = v));
		addRenderableWidget(new IntSlider(col(i), row(i++), "glidepilot.config.tint", 0, 60, c.learningTint, v -> c.learningTint = v));
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
			.bounds(this.width / 2 - 100, row(i + 1) + 2, 200, 20).build());
	}

	private interface BoolSink {
		void set(boolean v);
	}

	private static CycleButton<Boolean> toggle(int x, int y, String key, boolean value, BoolSink sink) {
		return CycleButton.onOffBuilder(value).create(x, y, 150, 20, Component.translatable(key), (b, v) -> sink.set(v));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		c.save();
		this.minecraft.gui.setScreen(parent);
	}

	private static final class IntSlider extends AbstractSliderButton {
		private final String key;
		private final int min;
		private final int max;
		private final IntConsumer sink;

		IntSlider(int x, int y, String key, int min, int max, int value, IntConsumer sink) {
			super(x, y, 150, 20, Component.empty(), (double) (Math.clamp(value, min, max) - min) / (max - min));
			this.key = key;
			this.min = min;
			this.max = max;
			this.sink = sink;
			updateMessage();
		}

		private int current() {
			return min + (int) Math.round(value * (max - min));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(key, current()));
		}

		@Override
		protected void applyValue() {
			sink.accept(current());
		}
	}
}
