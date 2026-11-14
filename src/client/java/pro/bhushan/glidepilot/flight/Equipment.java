package pro.bhushan.glidepilot.flight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;
import pro.bhushan.glidepilot.config.GlideConfig;

/** Glider durability, spare gliders and rockets. Any item with the glider component counts. */
public final class Equipment {
	private static final int CHEST_MENU_SLOT = 6;
	private static final int OFFHAND_MENU_SLOT = 45;

	private Equipment() {
	}

	public static boolean isGlider(ItemStack stack) {
		return !stack.isEmpty() && stack.has(DataComponents.GLIDER);
	}

	/** The glider being worn, or empty. */
	public static ItemStack worn(LocalPlayer player) {
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack stack = player.getItemBySlot(slot);
			if (isGlider(stack) && LivingEntity.canGlideUsing(stack, slot)) return stack;
		}
		ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
		return isGlider(chest) ? chest : ItemStack.EMPTY;
	}

	public static int remaining(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		if (!stack.isDamageableItem()) return Integer.MAX_VALUE;
		return stack.getMaxDamage() - stack.getDamageValue();
	}

	private static int unbreaking(LocalPlayer player, ItemStack stack) {
		Holder<Enchantment> holder = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
			.get(Enchantments.UNBREAKING).map(h -> (Holder<Enchantment>) h).orElse(null);
		return holder == null ? 0 : EnchantmentHelper.getItemEnchantmentLevel(holder, stack);
	}

	/**
	 * Expected seconds of gliding left before the glider reaches the low-durability mark.
	 * Vanilla removes one point per second of flight; Unbreaking N skips each loss with chance
	 * N/(N+1), so the expected time scales by N+1.
	 */
	public static double secondsLeft(LocalPlayer player, ItemStack stack, int lowMark) {
		int rem = remaining(stack);
		if (rem == Integer.MAX_VALUE) return Double.POSITIVE_INFINITY;
		return Math.max(0, rem - lowMark) * (unbreaking(player, stack) + 1.0);
	}

	/** Inventory index (0-35) of the best spare glider for the chest slot, or -1. */
	public static int spareGlider(LocalPlayer player, int lowMark) {
		int best = -1;
		double bestTime = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!isGlider(stack)) continue;
			Equippable eq = stack.get(DataComponents.EQUIPPABLE);
			if (eq == null || eq.slot() != EquipmentSlot.CHEST) continue;
			double t = secondsLeft(player, stack, lowMark);
			if (t > bestTime) {
				bestTime = t;
				best = i;
			}
		}
		return best;
	}

	private static int menuSlot(int inventoryIndex) {
		return inventoryIndex < 9 ? 36 + inventoryIndex : inventoryIndex;
	}

	/** Puts the spare in the chest slot. The worn glider goes where the spare was. */
	public static boolean swapIn(Minecraft mc, LocalPlayer player, int inventoryIndex) {
		if (mc.gameMode == null || inventoryIndex < 0) return false;
		int container = player.inventoryMenu.containerId;
		if (inventoryIndex < 9) {
			mc.gameMode.handleContainerInput(container, CHEST_MENU_SLOT, inventoryIndex, ContainerInput.SWAP, player);
		} else {
			int slot = menuSlot(inventoryIndex);
			mc.gameMode.handleContainerInput(container, slot, 0, ContainerInput.PICKUP, player);
			mc.gameMode.handleContainerInput(container, CHEST_MENU_SLOT, 0, ContainerInput.PICKUP, player);
			mc.gameMode.handleContainerInput(container, slot, 0, ContainerInput.PICKUP, player);
		}
		return isGlider(player.getItemBySlot(EquipmentSlot.CHEST));
	}

	public static boolean safeRocket(ItemStack stack) {
		if (!stack.is(Items.FIREWORK_ROCKET)) return false;
		if (GlideConfig.get().allowExplosiveRockets) return true;
		Fireworks fw = stack.get(DataComponents.FIREWORKS);
		return fw == null || fw.explosions().isEmpty();
	}

	public static int rocketCount(LocalPlayer player) {
		int n = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack s = player.getInventory().getItem(i);
			if (safeRocket(s)) n += s.getCount();
		}
		return n;
	}

	/**
	 * Fires one safe rocket and leaves the inventory as it was. Uses a hand that already holds
	 * one; otherwise briefly selects a hotbar slot; otherwise swaps a stack from the main
	 * inventory into the selected slot and back in the same tick.
	 */
	public static boolean fireRocket(Minecraft mc, LocalPlayer player) {
		if (mc.gameMode == null || player.isUsingItem()) return false;
		for (InteractionHand hand : InteractionHand.values()) {
			if (safeRocket(player.getItemInHand(hand))) {
				mc.gameMode.useItem(player, hand);
				return true;
			}
		}
		var inv = player.getInventory();
		int selected = inv.getSelectedSlot();
		for (int i = 0; i < 9; i++) {
			if (safeRocket(inv.getItem(i))) {
				inv.setSelectedSlot(i);
				mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
				inv.setSelectedSlot(selected);
				return true;
			}
		}
		for (int i = 9; i < 36; i++) {
			if (safeRocket(inv.getItem(i))) {
				int container = player.inventoryMenu.containerId;
				mc.gameMode.handleContainerInput(container, i, selected, ContainerInput.SWAP, player);
				mc.gameMode.useItem(player, InteractionHand.MAIN_HAND);
				mc.gameMode.handleContainerInput(container, i, selected, ContainerInput.SWAP, player);
				return true;
			}
		}
		return false;
	}
}
