package pro.bhushan.glidepilot.nav;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import pro.bhushan.glidepilot.config.GlideConfig;
import pro.bhushan.glidepilot.flight.Autopilot;
import pro.bhushan.glidepilot.flight.Autopilot.Stop;

/** Client-side /glide command tree. */
public final class GlideCommands {
	private GlideCommands() {
	}

	private static final SuggestionProvider<FabricClientCommandSource> NAMES =
		(ctx, builder) -> SharedSuggestionProvider.suggest(Waypoints.current().keySet(), builder);

	private interface StopAction {
		void run(LocalPlayer p, Stop stop);
	}

	/** Adds "<x> <z>" and "<name>" branches that resolve to a Stop. */
	private static <T extends ArgumentBuilder<FabricClientCommandSource, T>> T destination(T node, StopAction action) {
		node.then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer()).executes(ctx -> {
			int x = IntegerArgumentType.getInteger(ctx, "x");
			int z = IntegerArgumentType.getInteger(ctx, "z");
			action.run(ctx.getSource().getPlayer(), new Stop(x + ", " + z, x + 0.5, z + 0.5));
			return 1;
		})));
		node.then(argument("name", StringArgumentType.word()).suggests(NAMES).executes(ctx -> {
			String name = StringArgumentType.getString(ctx, "name");
			Waypoints.Point pt = Waypoints.get(name);
			if (pt == null) {
				ctx.getSource().sendError(Component.translatable("glidepilot.cmd.unknown", name));
				return 0;
			}
			action.run(ctx.getSource().getPlayer(), new Stop(name, pt.x() + 0.5, pt.z() + 0.5));
			return 1;
		}));
		return node;
	}

	private static int run(CommandContext<FabricClientCommandSource> ctx, Consumer<LocalPlayer> body) {
		body.accept(ctx.getSource().getPlayer());
		return 1;
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		Autopilot ap = Autopilot.get();
		var root = literal("glide").executes(ctx -> {
			ctx.getSource().sendFeedback(Component.translatable("glidepilot.cmd.help"));
			return 1;
		});

		root.then(destination(literal("to"), (p, s) -> ap.flyTo(p, List.of(s), false)));
		root.then(destination(literal("via"), ap::addStop));
		root.then(destination(literal("takeoff"), (p, s) -> ap.flyTo(p, List.of(s), true))
			.executes(ctx -> run(ctx, ap::startTakeoff)));
		root.then(literal("land").executes(ctx -> run(ctx, ap::land)));
		root.then(literal("config").executes(ctx -> {
			pro.bhushan.glidepilot.GlidePilot.requestSettings();
			return 1;
		}));
		root.then(literal("stop").executes(ctx -> run(ctx, ap::stop)));

		root.then(literal("route").executes(ctx -> {
			Stop t = ap.target();
			if (t == null) {
				ctx.getSource().sendFeedback(Component.translatable("glidepilot.cmd.noRoute"));
				return 1;
			}
			ctx.getSource().sendFeedback(Component.literal("1. " + t.label()).withStyle(ChatFormatting.GREEN));
			int i = 2;
			for (Stop s : ap.route()) ctx.getSource().sendFeedback(Component.literal(i++ + ". " + s.label()));
			return 1;
		}).then(literal("clear").executes(ctx -> {
			ap.clearRoute();
			ctx.getSource().sendFeedback(Component.translatable("glidepilot.cmd.routeCleared"));
			return 1;
		})));

		var wp = literal("wp");
		wp.then(literal("set").then(argument("name", StringArgumentType.word())
			.executes(ctx -> {
				LocalPlayer p = ctx.getSource().getPlayer();
				return setWaypoint(ctx, StringArgumentType.getString(ctx, "name"), p.getBlockX(), p.getBlockZ());
			})
			.then(argument("x", IntegerArgumentType.integer()).then(argument("z", IntegerArgumentType.integer())
				.executes(ctx -> setWaypoint(ctx, StringArgumentType.getString(ctx, "name"),
					IntegerArgumentType.getInteger(ctx, "x"), IntegerArgumentType.getInteger(ctx, "z")))))));
		wp.then(literal("remove").then(argument("name", StringArgumentType.word()).suggests(NAMES).executes(ctx -> {
			String name = StringArgumentType.getString(ctx, "name");
			if (Waypoints.remove(name)) {
				ctx.getSource().sendFeedback(Component.translatable("glidepilot.cmd.removed", name));
				return 1;
			}
			ctx.getSource().sendError(Component.translatable("glidepilot.cmd.unknown", name));
			return 0;
		})));
		wp.then(literal("list").executes(ctx -> {
			Map<String, Waypoints.Point> all = Waypoints.current();
			if (all.isEmpty()) ctx.getSource().sendFeedback(Component.translatable("glidepilot.cmd.noWaypoints"));
			all.forEach((name, pt) -> ctx.getSource().sendFeedback(Component.literal(name + "  " + pt.x() + ", " + pt.z())));
			return 1;
		}));
		root.then(wp);

		var style = literal("style");
		for (Autopilot.Style s : Autopilot.Style.values()) {
			style.then(literal(s.name().toLowerCase()).executes(ctx -> {
				GlideConfig.get().style = s;
				GlideConfig.get().save();
				ctx.getSource().sendFeedback(Component.translatable("glidepilot.cmd.style", Component.translatable("glidepilot.style." + s.name().toLowerCase())));
				return 1;
			}));
		}
		root.then(style);

		d.register(root);
	}

	private static int setWaypoint(CommandContext<FabricClientCommandSource> ctx, String name, int x, int z) {
		Waypoints.set(name, x, z);
		ctx.getSource().sendFeedback(Component.translatable("glidepilot.cmd.saved", name, x, z));
		return 1;
	}

	static Minecraft mc() {
		return Minecraft.getInstance();
	}
}
