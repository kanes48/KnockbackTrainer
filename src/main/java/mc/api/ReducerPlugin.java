package mc.api;

import java.util.UUID;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
import org.bukkit.util.Vector;

public class ReducerPlugin extends JavaPlugin implements Listener {

    private NPC reducer;
    private Location home;

    // The player who most recently hit the reducer.
    private UUID targetId;

    private int attackInterval;
    private double horizontalKnockback;
    private double verticalKnockback;

    @Override
    public void onEnable() {
        getLogger().info("Plugin for Reducer loaded.");


        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(this, this);

        if (!spawnReducer()) {
            getLogger().severe(
                    "Reducer could not spawn. Check npc.world and coordinates."
            );
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Repeatedly knock back only the current target.
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                attackTarget();
            }
        }, attackInterval, attackInterval);

        // Keep the NPC stationary and at its configured location.
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                keepReducerStationary();
            }
        }, 1L, 1L);

        getLogger().info("KnockbackReducer enabled.");
    }

    private void loadSettings() {
        attackInterval = Math.max(
                1,
                getConfig().getInt("npc.attack-interval-ticks", 20)
        );

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
            getLogger().severe("World is not loaded: " + worldName);
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

        // Create a real player-model NPC through Citizens.
        reducer = CitizensAPI.getNPCRegistry().createNPC(
                EntityType.PLAYER,
                ChatColor.RED + "Knockback Trainer"
        );

        // It must be possible to hit the NPC to activate it.
        reducer.setProtected(false);

        if (!reducer.spawn(home)) {
            reducer.destroy();
            reducer = null;
            return false;
        }

        targetId = null;
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
     * Any player who hits the reducer becomes the active target.
     * The NPC itself never takes damage.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReducerHit(EntityDamageByEntityEvent event) {
        if (!isReducer(event.getEntity())) {
            return;
        }

        event.setCancelled(true);

        if (event.getDamager() instanceof Player) {
            Player attacker = (Player) event.getDamager();

            if (attacker.isOnline() && !attacker.isDead()) {
                targetId = attacker.getUniqueId();

                attacker.sendMessage(
                        ChatColor.GREEN
                                + "The reducer is now targeting you!"
                );
            }
        }
    }

    /*
     * Prevent every other type of damage to the reducer,
     * including fall, fire, and environmental damage.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onReducerDamage(EntityDamageEvent event) {
        if (isReducer(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /*
     * Disable fall damage for all players in every game mode.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player
                && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            event.setCancelled(true);
        }
    }

    /*
     * Rescue Survival players who go below Y = 0.
     * If the player is the active target, clear the target FIRST.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getTo().getY() >= 0) {
            return;
        }

        Player player = event.getPlayer();

        if (targetId != null
                && targetId.equals(player.getUniqueId())) {
            targetId = null;
        }

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

        player.teleport(destination);
        player.setFallDistance(0.0F);
    }

    /*
     * Knock back the latest attacker at each configured interval.
     */
    private void attackTarget() {
        if (targetId == null) {
            return;
        }

        Player target = Bukkit.getPlayer(targetId);

        if (target == null || !target.isOnline() || target.isDead()) {
            targetId = null;
            return;
        }

        if (target.getGameMode() == GameMode.CREATIVE
                || target.getGameMode() == GameMode.SPECTATOR) {
            targetId = null;
            return;
        }

        // Only attack players in the reducer's world.
        if (!target.getWorld().equals(home.getWorld())) {
            targetId = null;
            return;
        }

        // Safety check in case a movement event was missed.
        if (target.getLocation().getY() < 0) {
            targetId = null;
            return;
        }

        applyKnockback(target);
    }

    private void applyKnockback(Player player) {
        Vector direction = player.getLocation().toVector()
                .subtract(home.toVector());

        // Knockback direction should be horizontal.
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
     * Reset NPC velocity and position so that it cannot be
     * knocked away from its configured location.
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
                || entity.getLocation().distanceSquared(home) > 0.01) {
            entity.teleport(home);
        }

        entity.setVelocity(new Vector(0, 0, 0));
        entity.setFallDistance(0.0F);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (targetId != null
                && targetId.equals(event.getPlayer().getUniqueId())) {
            targetId = null;
        }
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
                    + "Interval: " + attackInterval + " ticks");
            sender.sendMessage(ChatColor.YELLOW
                    + "Target: "
                    + (targetId == null ? "none" : targetId.toString()));
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
        targetId = null;

        if (reducer != null) {
            if (reducer.isSpawned()) {
                reducer.despawn();
            }

            reducer.destroy();
            reducer = null;
        }
    }
}
