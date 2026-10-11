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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.bukkit.block.Block;


public class ReducerPlugin extends JavaPlugin implements Listener {

    private NPC reducer;
    private Location home;

    private double horizontalKnockback;
    private double verticalKnockback;

    // The currently active target.
    private UUID targetId = null;

    // Last tick when a target was knocked back.
    private long lastAttackTick = 0L;

    // Server tick counter for Spigot 1.8.8.
    private long serverTicks = 0L;

    private long attackDelayTicks;
    private static final double ATTACK_REACH = 3.0D;

    private int sandstoneCleanupRadius = 25;
    private int sandstoneCleanupHeight = 25;
    private int sandstoneCleanupDepth = 25;

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
                serverTicks++;

                keepReducerStationary();
                attackTarget();
            }
        }, 1L, 1L);
    }

    private void loadSettings() {
        horizontalKnockback = getConfig().getDouble(
                "npc.horizontal-knockback", 1.2
        );

        attackDelayTicks = getConfig().getLong(
                "npc.attack-interval-ticks", 10
        );

        verticalKnockback = getConfig().getDouble(
                "npc.vertical-knockback", 0.365
        );

        sandstoneCleanupRadius = Math.max(
                0, getConfig().getInt("cleanup.radius", 5)
        );

        sandstoneCleanupHeight = Math.max(
                0, getConfig().getInt("cleanup.height", 5)
        );

        sandstoneCleanupDepth = Math.max(
                0, getConfig().getInt("cleanup.depth", 5)
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

    private void attackTarget() {
        if (targetId == null) {
            return;
        }

        Player target = Bukkit.getPlayer(targetId);

        // Stop tracking the target if they are no longer a valid player.
        if (target == null || !target.isOnline() || target.isDead()
                || target.getGameMode() == GameMode.CREATIVE
                || target.getGameMode() == GameMode.SPECTATOR) {
            targetId = null;
            return;
        }

        // Only attack players in the same world as the reducer.
        if (!target.getWorld().equals(home.getWorld())) {
            return;
        }

        // Only attack when the player is within 3 blocks of the reducer.
        if (target.getLocation().distanceSquared(home) > ATTACK_REACH * ATTACK_REACH) {
            return;
        }

        // Attack repeatedly at the configured interval, but only within reach.
        if (serverTicks - lastAttackTick >= attackDelayTicks) {
            applyKnockback(target);
            lastAttackTick = serverTicks;
        }
    }

    /*
     * Activate the reducer when a player hits it.
     * Repeated hits do not reset the attack timer.
     * The repeating task handles subsequent attacks at the configured interval.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReducerHit(EntityDamageByEntityEvent event) {
        if (!isReducer(event.getEntity())) {
            return;
        }

        // The reducer never takes damage.
        event.setCancelled(true);

        if (!(event.getDamager() instanceof Player)) {
            return;
        }

        Player attacker = (Player) event.getDamager();

        if (!attacker.isOnline()
                || attacker.isDead()
                || attacker.getGameMode() == GameMode.CREATIVE
                || attacker.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        // Only activate when there is no current target.
        // Rapid clicking cannot reset the timer or trigger extra attacks.
        if (targetId == null) {
            targetId = attacker.getUniqueId();

            // Allow the first knockback immediately.
            lastAttackTick = serverTicks - attackDelayTicks;
        }
    }

    private void clearNearbySandstone(Location center) {
        World world = center.getWorld();

        if (world == null) {
            return;
        }

        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();

        for (int x = centerX - sandstoneCleanupRadius;
             x <= centerX + sandstoneCleanupRadius; x++) {

            for (int y = centerY - sandstoneCleanupDepth;
                 y <= centerY + sandstoneCleanupHeight; y++) {

                for (int z = centerZ - sandstoneCleanupRadius;
                     z <= centerZ + sandstoneCleanupRadius; z++) {

                    Block block = world.getBlockAt(x, y, z);

                    if (block.getType() == Material.SANDSTONE) {
                        block.setType(Material.AIR);
                    }
                }
            }
        }
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

        Location originalLocation = player.getLocation().clone();

        if (targetId != null
                && targetId.equals(player.getUniqueId())) {
            targetId = null;
        }

        if (player.teleport(destination)) {
            player.setFallDistance(0.0F);

            clearNearbySandstone(originalLocation);
            giveTrainingKit(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (targetId != null
                && targetId.equals(event.getPlayer().getUniqueId())) {
            targetId = null;
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

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {

        // Only players can use this command.
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }

        Player player = (Player) sender;

        // Check whether the player has permission to manage the reducer.
        if (!player.hasPermission("reducer.admin")) {
            player.sendMessage(ChatColor.RED
                    + "You don't have permission to use this command.");
            return true;
        }

        // Display command usage when no arguments are provided.
        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW
                    + "Reducer commands:");
            player.sendMessage(ChatColor.YELLOW
                    + "/reducer kb <horizontal> <vertical>");
            player.sendMessage(ChatColor.YELLOW
                    + "/reducer delay <seconds>");
            player.sendMessage(ChatColor.YELLOW
                    + "/reducer status");
            return true;
        }

        // Change the reducer's horizontal and vertical knockback strength.
        if (args[0].equalsIgnoreCase("kb")) {
            if (args.length != 3) {
                player.sendMessage(ChatColor.YELLOW
                        + "Usage: /reducer kb <horizontal> <vertical>");
                return true;
            }

            try {
                double horizontal = Double.parseDouble(args[1]);
                double vertical = Double.parseDouble(args[2]);

                // Reject invalid numeric values.
                if (Double.isNaN(horizontal) || Double.isInfinite(horizontal)
                        || Double.isNaN(vertical) || Double.isInfinite(vertical)
                        || horizontal < 0.0D || vertical < 0.0D) {
                    player.sendMessage(ChatColor.RED
                            + "Knockback values must be valid non-negative numbers.");
                    return true;
                }

                // Update the knockback values used by the reducer.
                horizontalKnockback = horizontal;
                verticalKnockback = vertical;

                player.sendMessage(ChatColor.GREEN
                        + "Reducer knockback updated: horizontal="
                        + horizontalKnockback
                        + ", vertical=" + verticalKnockback);

            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED
                        + "Please enter valid numbers for knockback.");
            }

            return true;
        }

        // Set the delay between repeated attacks, in seconds.
        // The default is 14 ticks, which equals 0.7 seconds.
        if (args[0].equalsIgnoreCase("delay")) {
            if (args.length != 2) {
                player.sendMessage(ChatColor.YELLOW
                        + "Usage: /reducer delay <seconds>");
                player.sendMessage(ChatColor.GRAY
                        + "Example: /reducer delay 0.7");
                return true;
            }

            try {
                double seconds = Double.parseDouble(args[1]);

                // Ensure the delay is a positive, finite number.
                if (seconds <= 0.0D || Double.isNaN(seconds)
                        || Double.isInfinite(seconds)) {
                    player.sendMessage(ChatColor.RED
                            + "Delay must be greater than 0.");
                    return true;
                }

                // Convert seconds to Minecraft ticks (20 ticks = 1 second).
                attackDelayTicks = Math.max(1L,
                        Math.round(seconds * 20.0D));

                player.sendMessage(ChatColor.GREEN
                        + "Reducer attack delay set to "
                        + String.format(java.util.Locale.US, "%.2f",
                        attackDelayTicks / 20.0D)
                        + " seconds (" + attackDelayTicks + " ticks).");

            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED
                        + "Please enter a valid number of seconds.");
            }

            return true;
        }

        // Display the current reducer settings.
        if (args[0].equalsIgnoreCase("status")) {
            player.sendMessage(ChatColor.GOLD
                    + "----- Reducer Status -----");
            player.sendMessage(ChatColor.YELLOW
                    + "Horizontal knockback: " + horizontalKnockback);
            player.sendMessage(ChatColor.YELLOW
                    + "Vertical knockback: " + verticalKnockback);
            player.sendMessage(ChatColor.YELLOW
                    + "Attack delay: "
                    + String.format(java.util.Locale.US, "%.2f",
                    attackDelayTicks / 20.0D)
                    + " seconds (" + attackDelayTicks + " ticks)");
            player.sendMessage(ChatColor.YELLOW
                    + "Attack reach: " + ATTACK_REACH + " blocks");
            return true;
        }

        // Inform the player when they enter an unknown subcommand.
        player.sendMessage(ChatColor.RED
                + "Unknown command. Use /reducer for help.");
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