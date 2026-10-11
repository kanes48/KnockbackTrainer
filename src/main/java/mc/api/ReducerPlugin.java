package mc.api;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class ReducerPlugin extends JavaPlugin implements Listener {

    private NPC reducer;
    private Location home;

    private double horizontalKnockback;
    private double verticalKnockback;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);

        if (!spawnReducer()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Keep the NPC at its configured location.
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                keepReducerStationary();
            }
        }, 1L, 1L);
    }

    private void loadSettings() {
        horizontalKnockback = getConfig().getDouble(
                "npc.horizontal-knockback", 1.15
        );

        verticalKnockback = getConfig().getDouble(
                "npc.vertical-knockback", 0.42
        );
    }

    private boolean spawnReducer() {
        String worldName = getConfig().getString("npc.world", "world");
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            getLogger().severe("Reducer world is not loaded: " + worldName);
            return false;
        }

        home = new Location(
                world,
                getConfig().getDouble("npc.x", 0.5),
                getConfig().getDouble("npc.y", 64.0),
                getConfig().getDouble("npc.z", 0.5),
                (float) getConfig().getDouble("npc.yaw", 0.0),
                (float) getConfig().getDouble("npc.pitch", 0.0)
        );

        reducer = CitizensAPI.getNPCRegistry().createNPC(
                EntityType.PLAYER,
                ChatColor.RED + "Knockback Trainer"
        );

        // Allow hits to reach the damage event.
        reducer.setProtected(false);

        if (!reducer.spawn(home)) {
            reducer.destroy();
            reducer = null;
            return false;
        }

        return true;
    }

    private boolean isReducer(Entity entity) {
        return reducer != null
                && reducer.isSpawned()
                && reducer.getEntity() != null
                && reducer.getEntity().getUniqueId()
                .equals(entity.getUniqueId());
    }

    /*
     * Each player hit causes one knockback.
     * No repeating attack timer is used.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReducerHit(final EntityDamageByEntityEvent event) {
        if (!isReducer(event.getEntity())) {
            return;
        }

        // NPC cannot lose health, but remains attackable.
        event.setCancelled(true);

        if (!(event.getDamager() instanceof Player)) {
            return;
        }

        final Player attacker = (Player) event.getDamager();

        if (!attacker.isOnline()
                || attacker.isDead()
                || attacker.getGameMode() == GameMode.CREATIVE
                || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        // Apply after vanilla attack processing.
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                if (!attacker.isOnline()
                        || attacker.isDead()
                        || attacker.getGameMode() == GameMode.CREATIVE
                        || attacker.getGameMode() == GameMode.SPECTATOR) {
                    return;
                }

                if (home == null
                        || !attacker.getWorld().equals(home.getWorld())
                        || attacker.getLocation().getY() < 0) {
                    return;
                }

                applyKnockback(attacker);
            }
        });
    }

    /*
     * Cancel all damage to the reducer, including environmental damage.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReducerDamage(EntityDamageEvent event) {
        if (isReducer(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /*
     * Disable fall damage for all players.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player
                && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    /*
     * Rescue Survival players below Y = 0.
     * Clear their inventory and give the training kit after teleport.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getTo().getY() >= 0) {
            return;
        }

        Player player = event.getPlayer();

        if (player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }

        String worldName = getConfig().getString(
                "destination.world", "world"
        );

        World destinationWorld = Bukkit.getWorld(worldName);

        if (destinationWorld == null) {
            return;
        }

        Location destination = new Location(
                destinationWorld,
                getConfig().getDouble("destination.x", 0.5),
                getConfig().getDouble("destination.y", 64.0),
                getConfig().getDouble("destination.z", 0.5),
                player.getLocation().getYaw(),
                player.getLocation().getPitch()
        );

        if (player.teleport(destination)) {
            player.setFallDistance(0.0F);
            giveTrainingKit(player);
        }
    }

    private void giveTrainingKit(Player player) {
        // Spigot 1.8.8 has no separate off-hand slot.
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);

        // Diamond sword with Knockback II.
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        sword.addUnsafeEnchantment(Enchantment.KNOCKBACK, 2);
        player.getInventory().addItem(sword);

        // Four full stacks of sandstone.
        for (int i = 0; i < 4; i++) {
            player.getInventory().addItem(
                    new ItemStack(Material.SANDSTONE, 64)
            );
        }

        player.updateInventory();
    }

    /*
     * Push the player away from the reducer's configured position.
     */
    private void applyKnockback(Player player) {
        Vector direction = player.getLocation().toVector()
                .subtract(home.toVector());

        direction.setY(0);

        if (direction.lengthSquared() < 0.0001) {
            direction = new Vector(1, 0, 0);
        } else {
            direction.normalize();
        }

        player.setVelocity(new Vector(
                direction.getX() * horizontalKnockback,
                verticalKnockback,
                direction.getZ() * horizontalKnockback
        ));
    }

    /*
     * Keep the NPC stationary and reset its velocity every tick.
     */
    private void keepReducerStationary() {
        if (reducer == null || !reducer.isSpawned() || home == null) {
            return;
        }

        Entity entity = reducer.getEntity();

        if (entity == null || !entity.isValid()) {
            return;
        }

        if (!entity.getWorld().equals(home.getWorld())
                || entity.getLocation().distanceSquared(home) > 0.0001) {
            entity.teleport(home);
        }

        entity.setVelocity(new Vector(0, 0, 0));
        entity.setFallDistance(0.0F);
    }

    /*
     * Commands:
     * /reducer kb <horizontal> <vertical>
     * /reducer status
     */
    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!command.getName().equalsIgnoreCase("reducer")) {
            return false;
        }

        if (!sender.hasPermission("reducer.admin")) {
            sender.sendMessage(
                    ChatColor.RED + "You do not have permission."
            );
            return true;
        }

        if (args.length == 1
                && args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GOLD + "Reducer status");
            sender.sendMessage(ChatColor.YELLOW
                    + "Horizontal: " + horizontalKnockback);
            sender.sendMessage(ChatColor.YELLOW
                    + "Vertical: " + verticalKnockback);
            sender.sendMessage(ChatColor.YELLOW
                    + "Mode: one knockback per hit");
            return true;
        }

        if (args.length == 3
                && (args[0].equalsIgnoreCase("kb")
                || args[0].equalsIgnoreCase("knockback"))) {

            final double horizontal;
            final double vertical;

            try {
                horizontal = Double.parseDouble(args[1]);
                vertical = Double.parseDouble(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED
                        + "Both knockback values must be numbers.");
                return true;
            }

            if (Double.isNaN(horizontal)
                    || Double.isInfinite(horizontal)
                    || Double.isNaN(vertical)
                    || Double.isInfinite(vertical)
                    || horizontal < 0
                    || vertical < 0
                    || horizontal > 10
                    || vertical > 10) {
                sender.sendMessage(ChatColor.RED
                        + "Values must be between 0 and 10.");
                return true;
            }

            horizontalKnockback = horizontal;
            verticalKnockback = vertical;

            getConfig().set(
                    "npc.horizontal-knockback", horizontalKnockback
            );
            getConfig().set(
                    "npc.vertical-knockback", verticalKnockback
            );

            saveConfig();

            sender.sendMessage(ChatColor.GREEN
                    + "Knockback updated: horizontal=" + horizontalKnockback
                    + ", vertical=" + verticalKnockback);
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW
                + "/reducer kb <horizontal> <vertical>");
        sender.sendMessage(ChatColor.YELLOW + "/reducer status");
        return true;
    }

    @Override
    public void onDisable() {
        if (reducer != null) {
            if (reducer.isSpawned()) {
                reducer.despawn();
            }

            reducer.destroy();
            reducer = null;
        }
    }
}