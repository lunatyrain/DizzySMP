package dev.dizzysmp;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.help.HelpTopic;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public final class DizzySMPPlugin extends JavaPlugin implements Listener {

    private NamespacedKey infinitePearlKey;
    private NamespacedKey infiniteWindKey;
    private NamespacedKey markerKey;
    private NamespacedKey recipeKey;

    /** Sword + mace enchants (plus the shared ones). Conflicts between them are ignored. */
    private static final Set<Enchantment> ALLOWED = Set.of(
            // sword
            Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS,
            Enchantment.FIRE_ASPECT, Enchantment.LOOTING, Enchantment.SWEEPING_EDGE,
            Enchantment.KNOCKBACK,
            // mace
            Enchantment.DENSITY, Enchantment.BREACH, Enchantment.WIND_BURST,
            // shared
            Enchantment.UNBREAKING, Enchantment.MENDING, Enchantment.VANISHING_CURSE
    );

    @Override
    public void onEnable() {
        // Keys keep their original namespaces so items made by the old plugins still work
        infinitePearlKey = new NamespacedKey("pearlwindchargecontrol", "infinite_pearl");
        infiniteWindKey = new NamespacedKey("pearlwindchargecontrol", "infinite_wind_charge");
        markerKey = new NamespacedKey("macesword", "mace_sword");
        recipeKey = new NamespacedKey("macesword", "mace_sword_recipe");
        saveDefaultConfig();
        // Makes "/help pw" work (the /pwhelp command stays as a backup)
        Bukkit.getHelpMap().addTopic(new PwHelpTopic(this));

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createMaceSword());
        recipe.shape(" M ", "SCS", " D ");
        recipe.setIngredient('M', Material.HEAVY_CORE);
        recipe.setIngredient('S', Material.ANCIENT_DEBRIS);
        recipe.setIngredient('C', Material.DRAGON_EGG);
        recipe.setIngredient('D', Material.DIAMOND_SWORD);
        Bukkit.addRecipe(recipe);

        // Block the regular mace craft (heavy core + breeze rod)
        Bukkit.removeRecipe(NamespacedKey.minecraft("mace"));

        // Unlock for anyone already online (e.g. plugin added while the server was running)
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.discoverRecipe(recipeKey);
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        Bukkit.removeRecipe(recipeKey);
    }

    private boolean hasMarker(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private ItemStack createInfiniteItem(Material material, String miniName, String lore, NamespacedKey key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(miniName));
        meta.lore(List.of(Component.text(lore)));
        meta.setEnchantmentGlintOverride(true);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createInfinitePearl() {
        ItemStack item = createInfiniteItem(Material.ENDER_PEARL,
                "<!italic><gradient:#9B5DE5:#00F5D4>Infinite Pearl</gradient>",
                "A pearl that never runs out.", infinitePearlKey);
        // Custom texture from the DizzySMP resource pack
        ItemMeta modelMeta = item.getItemMeta();
        modelMeta.setItemModel(new NamespacedKey("lunasitems", "infinite_pearl"));
        item.setItemMeta(modelMeta);
        applyCooldown(item);
        return item;
    }

    /** Own cooldown group, so it doesn't put normal pearls on cooldown (and vice versa). */
    private void applyCooldown(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        UseCooldownComponent cooldown = meta.getUseCooldown();
        cooldown.setCooldownSeconds((float) getConfig().getDouble("infinite-pearl-cooldown-seconds", 30.0));
        cooldown.setCooldownGroup(new NamespacedKey("pearlwindchargecontrol", "infinite_pearl_cooldown"));
        meta.setUseCooldown(cooldown);
        item.setItemMeta(meta);
    }

    /** Updates the cooldown on any Infinite Pearls in a player's inventory. */
    private void refreshPearls(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (hasMarker(item, infinitePearlKey)) {
                applyCooldown(item);
                player.getInventory().setItem(i, item);
            }
        }
    }

    public ItemStack createInfiniteWindCharge() {
        return createInfiniteItem(Material.WIND_CHARGE,
                "<!italic><gradient:#7DE8FF:#FFFFFF>Infinite Wind Charge</gradient>",
                "A wind charge that never runs out.", infiniteWindKey);
    }

    /** Infinite items are never used up; normal pearls / wind charges obey the /toggleitem setting. */
    @EventHandler
    public void onLaunch(PlayerLaunchProjectileEvent e) {
        ItemStack item = e.getItemStack();
        Material type = item.getType();

        if (type == Material.ENDER_PEARL) {
            if (hasMarker(item, infinitePearlKey)) {
                e.setShouldConsume(false);
            } else if (!getConfig().getBoolean("pearls-enabled", true)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(Component.text("Ender pearls are currently disabled.", NamedTextColor.RED));
            }
        } else if (type == Material.WIND_CHARGE) {
            if (hasMarker(item, infiniteWindKey)) {
                e.setShouldConsume(false);
            } else if (!getConfig().getBoolean("windcharges-enabled", true)) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(Component.text("Wind charges are currently disabled.", NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        refreshPearls(player);
        // Wait a tick so the player's recipe book is ready
        Bukkit.getScheduler().runTaskLater(this, () -> player.discoverRecipe(recipeKey), 20L);
    }

    public boolean isMaceSword(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    public ItemStack createMaceSword() {
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();

        // Custom texture from the DizzySMP resource pack
        meta.setItemModel(new NamespacedKey("lunasitems", "mace_sword"));

        meta.displayName(MiniMessage.miniMessage().deserialize(
                "<!italic><gradient:#FF69B4:#FFFFFF>Mace Sword</gradient>"));
        meta.lore(List.of(Component.text("Splicing data like this is illegal you know.")));
        meta.setUnbreakable(true);

        // Netherite sword stats: 8 damage total (1 base + 7), 1.6 speed (4 - 2.4)
        meta.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(
                new NamespacedKey("macesword", "mace_sword_damage"), 7.0,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        meta.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(
                new NamespacedKey("macesword", "mace_sword_speed"), -2.4,
                AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));

        meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /** Groups where only one enchant may be on the item at a time. */
    private static final List<Set<Enchantment>> EXCLUSIVE_GROUPS = List.of(
            Set.of(Enchantment.BREACH, Enchantment.DENSITY),
            Set.of(Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS)
    );

    /** Enchants the enchanting table can roll on the Mace Sword (sword + mace). */
    private static final List<Enchantment> TABLE_POOL = List.of(
            Enchantment.SHARPNESS, Enchantment.SMITE, Enchantment.BANE_OF_ARTHROPODS,
            Enchantment.FIRE_ASPECT, Enchantment.LOOTING, Enchantment.SWEEPING_EDGE,
            Enchantment.KNOCKBACK, Enchantment.UNBREAKING,
            Enchantment.DENSITY, Enchantment.BREACH
    );

    private final Map<UUID, Integer> enchantCount = new HashMap<>();

    private long seed(Player p, int button, int cost) {
        int n = enchantCount.getOrDefault(p.getUniqueId(), 0);
        return p.getUniqueId().getMostSignificantBits() ^ (n * 7919L) ^ (button * 131L) ^ (cost * 1000003L);
    }

    private static int levelFor(Enchantment ench, int cost) {
        // Table only: no Sharpness V (still possible with books in an anvil)
        int max = ench.equals(Enchantment.SHARPNESS) ? 4 : ench.getMaxLevel();
        int lvl = (int) Math.round(cost / 30.0 * max);
        return Math.max(1, Math.min(max, lvl));
    }

    private static boolean conflicts(Map<Enchantment, Integer> present, Enchantment ench) {
        for (Set<Enchantment> group : EXCLUSIVE_GROUPS) {
            if (!group.contains(ench)) continue;
            for (Enchantment other : group) {
                if (!other.equals(ench) && present.containsKey(other)) return true;
            }
        }
        return false;
    }

    /** Rolls sword + mace enchants for the table. The first entry is the one shown as the hint. */
    private static Map<Enchantment, Integer> rollEnchants(Random r, int cost) {
        List<Enchantment> pool = new ArrayList<>(TABLE_POOL);
        Collections.shuffle(pool, r);
        Map<Enchantment, Integer> out = new LinkedHashMap<>();
        out.put(pool.get(0), levelFor(pool.get(0), cost));
        double chance = (cost + 1) / 50.0;
        for (Enchantment c : pool.subList(1, pool.size())) {
            if (r.nextDouble() >= chance) break;
            if (conflicts(out, c)) continue;
            out.put(c, levelFor(c, cost));
            chance /= 2;
        }
        return out;
    }

    /** Enchanting table previews for the Mace Sword only. */
    @EventHandler
    public void onPrepareEnchant(PrepareItemEnchantEvent e) {
        if (!isMaceSword(e.getItem())) return;
        EnchantmentOffer[] offers = e.getOffers();
        for (int i = 0; i < offers.length; i++) {
            EnchantmentOffer offer = offers[i];
            if (offer == null || offer.getCost() <= 0) continue;
            Map<Enchantment, Integer> roll = rollEnchants(
                    new Random(seed(e.getEnchanter(), i, offer.getCost())), offer.getCost());
            Enchantment first = roll.keySet().iterator().next();
            offer.setEnchantment(first);
            offer.setEnchantmentLevel(roll.get(first));
        }
    }

    /** Applies sword + mace enchants when enchanting the Mace Sword at a table. */
    @EventHandler
    public void onEnchant(EnchantItemEvent e) {
        if (!isMaceSword(e.getItem())) return;
        Player p = e.getEnchanter();
        Map<Enchantment, Integer> add = e.getEnchantsToAdd();
        add.clear();
        add.putAll(rollEnchants(new Random(seed(p, e.whichButton(), e.getExpLevelCost())), e.getExpLevelCost()));
        enchantCount.merge(p.getUniqueId(), 1, Integer::sum);
    }

    private static boolean conflicts(ItemMeta meta, Enchantment ench) {
        for (Set<Enchantment> group : EXCLUSIVE_GROUPS) {
            if (!group.contains(ench)) continue;
            for (Enchantment other : group) {
                if (!other.equals(ench) && meta.hasEnchant(other)) return true;
            }
        }
        return false;
    }

    /** Dropped Mace Swords can't be destroyed by cactus, lava, fire, or explosions. */
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent e) {
        Item entity = e.getEntity();
        if (isMaceSword(entity.getItemStack())) {
            entity.setInvulnerable(true);
        }
    }

    /** Backup for items that were already in the world before the plugin loaded. */
    @EventHandler
    public void onItemDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Item item && isMaceSword(item.getItemStack())
                && e.getCause() != EntityDamageEvent.DamageCause.VOID) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onItemCombust(EntityCombustEvent e) {
        if (e.getEntity() instanceof Item drop && isMaceSword(drop.getItemStack())) {
            e.setCancelled(true);
        }
    }

    /** Backup: nothing in a crafting grid may produce a normal mace; only the Mace Sword recipe can. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        ItemStack result = e.getInventory().getResult();
        if (result != null && result.getType() == Material.MACE && !isMaceSword(result)) {
            e.getInventory().setResult(null);
        }
    }

    /** Lets ONLY the Mace Sword combine sword and mace enchants. Breach/Density and Sharpness/Smite/Bane stay mutually exclusive. */
    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        ItemStack left = inv.getFirstItem();
        ItemStack right = inv.getSecondItem();
        if (!isMaceSword(left)) {
            guardVanilla(e, left);
            return;
        }
        if (right == null || right.getType().isAir()) return;

        Map<Enchantment, Integer> incoming = new HashMap<>();
        if (right.getItemMeta() instanceof EnchantmentStorageMeta book) {
            incoming.putAll(book.getStoredEnchants());
        } else {
            incoming.putAll(right.getEnchantments());
        }
        if (incoming.isEmpty()) return;

        ItemStack result = left.clone();
        ItemMeta meta = result.getItemMeta();
        int cost = 0;
        boolean changed = false;

        for (Map.Entry<Enchantment, Integer> en : incoming.entrySet()) {
            Enchantment ench = en.getKey();
            if (!ALLOWED.contains(ench)) continue;
            if (conflicts(meta, ench)) continue;
            int current = meta.getEnchantLevel(ench);
            int in = en.getValue();
            int next = (current == in) ? Math.min(in + 1, ench.getMaxLevel()) : Math.max(current, in);
            if (next == current) continue;
            meta.addEnchant(ench, next, true);
            cost += next;
            changed = true;
        }

        if (!changed) {
            e.setResult(null);
            return;
        }
        result.setItemMeta(meta);
        e.setResult(result);
        e.getView().setRepairCost(Math.min(39, Math.max(1, cost)));
    }

    /** Normal maces and swords stay vanilla: block any anvil result with enchants they can't normally have. */
    private void guardVanilla(PrepareAnvilEvent e, ItemStack left) {
        if (left == null) return;
        Material type = left.getType();
        if (type != Material.MACE && !type.name().endsWith("_SWORD")) return;
        ItemStack result = e.getResult();
        if (result == null || result.getType() != type) return;

        ItemStack plain = new ItemStack(type);
        List<Enchantment> list = new ArrayList<>(result.getEnchantments().keySet());
        for (int i = 0; i < list.size(); i++) {
            Enchantment a = list.get(i);
            if (!a.canEnchantItem(plain)) {
                e.setResult(null);
                return;
            }
            for (int j = i + 1; j < list.size(); j++) {
                if (a.conflictsWith(list.get(j))) {
                    e.setResult(null);
                    return;
                }
            }
        }
    }

    private void give(CommandSender sender, String[] args, ItemStack item, String usage) {
        Player target = null;
        if (args.length > 0) target = Bukkit.getPlayer(args[0]);
        else if (sender instanceof Player p) target = p;
        if (target == null) {
            sender.sendMessage(usage);
            return;
        }
        target.getInventory().addItem(item);
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("--- DizzySMP ---", NamedTextColor.GOLD));
        boolean any = false;
        if (sender.hasPermission("pearlwindchargecontrol.toggle")) {
            any = true;
            sender.sendMessage(Component.text("/toggleitem <pearls|windcharges> [on|off]", NamedTextColor.YELLOW)
                    .append(Component.text(" - enable/disable normal pearls or wind charges for players", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("Currently: pearls " + (getConfig().getBoolean("pearls-enabled", true) ? "ON" : "OFF")
                    + ", wind charges " + (getConfig().getBoolean("windcharges-enabled", true) ? "ON" : "OFF"), NamedTextColor.AQUA));
        }
        if (sender.hasPermission("pearlwindchargecontrol.cooldown")) {
            any = true;
            sender.sendMessage(Component.text("/pearlcooldown <seconds>", NamedTextColor.YELLOW)
                    .append(Component.text(" - set the Infinite Pearl cooldown (now "
                            + getConfig().getDouble("infinite-pearl-cooldown-seconds", 30.0) + "s)", NamedTextColor.GRAY)));
        }
        if (sender.hasPermission("pearlwindchargecontrol.give")) {
            any = true;
            sender.sendMessage(Component.text("/infinitepearl [player]", NamedTextColor.YELLOW)
                    .append(Component.text(" - give an Infinite Pearl", NamedTextColor.GRAY)));
            sender.sendMessage(Component.text("/infinitewindcharge [player]", NamedTextColor.YELLOW)
                    .append(Component.text(" - give an Infinite Wind Charge", NamedTextColor.GRAY)));
        }
        if (sender.hasPermission("macesword.give")) {
            any = true;
            sender.sendMessage(Component.text("/macesword [player]", NamedTextColor.YELLOW)
                    .append(Component.text(" - give a Mace Sword", NamedTextColor.GRAY)));
        }
        if (!any) {
            sender.sendMessage(Component.text("You don't have access to any commands from this plugin.", NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.text("Infinite items always work, even when the normal ones are disabled.", NamedTextColor.GRAY));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase()) {
            case "pwhelp" -> sendHelp(sender);
            case "pearlcooldown" -> {
                double current = getConfig().getDouble("infinite-pearl-cooldown-seconds", 30.0);
                if (args.length < 1) {
                    sender.sendMessage("Infinite Pearl cooldown is " + current + " seconds. Usage: /pearlcooldown <seconds>");
                    return true;
                }
                double seconds;
                try {
                    seconds = Double.parseDouble(args[0]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage("Usage: /pearlcooldown <seconds>");
                    return true;
                }
                if (seconds < 1 || seconds > 3600) {
                    sender.sendMessage("Cooldown must be between 1 and 3600 seconds.");
                    return true;
                }
                getConfig().set("infinite-pearl-cooldown-seconds", seconds);
                saveConfig();
                for (Player online : Bukkit.getOnlinePlayers()) refreshPearls(online);
                sender.sendMessage("Infinite Pearl cooldown is now " + seconds + " seconds.");
            }
            case "macesword" -> give(sender, args, createMaceSword(), "Usage: /macesword [player]");
            case "infinitepearl" -> give(sender, args, createInfinitePearl(), "Usage: /infinitepearl [player]");
            case "infinitewindcharge" -> give(sender, args, createInfiniteWindCharge(), "Usage: /infinitewindcharge [player]");
            case "toggleitem" -> {
                if (args.length < 1 || !(args[0].equalsIgnoreCase("pearls") || args[0].equalsIgnoreCase("windcharges"))) {
                    sender.sendMessage("Usage: /toggleitem <pearls|windcharges> [on|off]");
                    return true;
                }
                String path = args[0].equalsIgnoreCase("pearls") ? "pearls-enabled" : "windcharges-enabled";
                String name = args[0].equalsIgnoreCase("pearls") ? "Ender pearls" : "Wind charges";
                boolean enabled;
                if (args.length >= 2) {
                    if (args[1].equalsIgnoreCase("on")) enabled = true;
                    else if (args[1].equalsIgnoreCase("off")) enabled = false;
                    else {
                        sender.sendMessage("Usage: /toggleitem <pearls|windcharges> [on|off]");
                        return true;
                    }
                } else {
                    enabled = !getConfig().getBoolean(path, true);
                }
                getConfig().set(path, enabled);
                saveConfig();
                sender.sendMessage(name + " are now " + (enabled ? "ENABLED" : "DISABLED") + " for players.");
            }
            default -> { return false; }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (cmd.getName().equalsIgnoreCase("toggleitem")) {
            if (args.length == 1) return List.of("pearls", "windcharges");
            if (args.length == 2) return List.of("on", "off");
        }
        if (cmd.getName().equalsIgnoreCase("pearlcooldown") && args.length == 1) {
            return List.of("30");
        }
        return List.of();
    }

    /** Plain-text version of the help, filtered by the viewer's permissions. */
    String helpText(CommandSender sender) {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        if (sender.hasPermission("pearlwindchargecontrol.toggle")) {
            any = true;
            sb.append("\u00a7e/toggleitem <pearls|windcharges> [on|off]\u00a77 - enable/disable normal pearls or wind charges for players\n");
            sb.append("\u00a7bCurrently: pearls ").append(getConfig().getBoolean("pearls-enabled", true) ? "ON" : "OFF")
                    .append(", wind charges ").append(getConfig().getBoolean("windcharges-enabled", true) ? "ON" : "OFF").append("\n");
        }
        if (sender.hasPermission("pearlwindchargecontrol.cooldown")) {
            any = true;
            sb.append("\u00a7e/pearlcooldown <seconds>\u00a77 - set the Infinite Pearl cooldown (now ")
                    .append(getConfig().getDouble("infinite-pearl-cooldown-seconds", 30.0)).append("s)\n");
        }
        if (sender.hasPermission("pearlwindchargecontrol.give")) {
            any = true;
            sb.append("\u00a7e/infinitepearl [player]\u00a77 - give an Infinite Pearl\n");
            sb.append("\u00a7e/infinitewindcharge [player]\u00a77 - give an Infinite Wind Charge\n");
        }
        if (sender.hasPermission("macesword.give")) {
            any = true;
            sb.append("\u00a7e/macesword [player]\u00a77 - give a Mace Sword\n");
        }
        if (!any) sb.append("\u00a77You don't have access to any commands from this plugin.\n");
        sb.append("\u00a77Infinite items always work, even when the normal ones are disabled.");
        return sb.toString();
    }

    /** Registers the "pw" topic so /help pw shows this plugin's commands. */
    private static final class PwHelpTopic extends HelpTopic {
        private final DizzySMPPlugin plugin;

        PwHelpTopic(DizzySMPPlugin plugin) {
            this.plugin = plugin;
            this.name = "pw";
            this.shortText = "DizzySMP commands";
            this.fullText = "DizzySMP commands";
        }

        @Override
        public boolean canSee(CommandSender sender) {
            return true;
        }

        @Override
        public String getFullText(CommandSender forWho) {
            return "\u00a76--- DizzySMP ---\n" + plugin.helpText(forWho);
        }
    }
}
