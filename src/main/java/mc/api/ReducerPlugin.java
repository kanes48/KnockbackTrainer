package mc.api;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

public class ReducerPlugin extends JavaPlugin implements Listener {

    private static final String NPC_NAME = "§cKnockback Trainer";

    private Zombie npc;
    private Location home;
    private boolean activated = false;

    private int attackInterval;
    private double horizontalKnockback;
    private double verticalKnockback;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);

        attackInterval = Math.max(
                1, getConfig().getInt("npc.attack-interval-ticks", 20));
        horizontalKnockback =
                getConfig().getDouble("npc.horizontal-knockback", 1.15);
        verticalKnockback =
                getConfig().getDouble("npc.vertical-knockback", 0.42);

        spawnTrainer();

        // Attack repeatedly, but only after the NPC has been hit.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!activated || npc == null || !npc.isValid()) {
                return;
            }

            Player target = findNearestPlayer();
            if (target != null) {
                knockBack(target);
            }
        }, attackInterval, attackInterval);

        // Keep the NPC at its configured location.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (npc == null || !npc.isValid() || home == null) {
                return;
            }

            if (npc.getLocation().distanceSquared(home) > 0.01) {
                npc.teleport(home);
            }
            npc.setVelocity(new Vector(0, 0, 0));
        }, 1L, 1L);

        getLogger().info("VoidRescue and Knockback Trainer enabled.");
    }

    private void spawnTrainer() {
        String worldName = getConfig().getString("npc.world", "world");
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            getLogger().severe("NPC world '" + worldName + "' is not loaded!");
            return;
        }

        home = new Location(
                world,
                getConfig().getDouble("npc.x", 0.5),
                getConfig().getDouble("npc.y", 64.0),
                getConfig().getDouble("npc.z", 0.5),
                (float) getConfig().getDouble("npc.yaw", 0.0),
                (float) getConfig().getDouble("npc.pitch", 0.0)
        );

        // Remove any old trainer copies.
        for (Entity entity : world.getEntities()) {
            if (isTrainer(entity)) {
                entity.remove();
            }
        }

        npc = (Zombie) world.spawnEntity(home, EntityType.ZOMBIE);
        npc.setCustomName(NPC_NAME);
        npc.setCustomNameVisible(true);
        npc.setRemoveWhenFarAway(false);
        npc.setCanPickupItems(false);
        npc.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOW, Integer.MAX_VALUE, 10));

        ItemStack stick = new ItemStack(Material.STICK);
        stick.addUnsafeEnchantment(Enchantment.KNOCKBACK, 2);
        npc.getEquipment().setItemInHand(stick);
        npc.getEquipment().setItemInHandDropChance(0.0F);
    }

    private boolean isTrainer(Entity entity) {
        return entity instanceof Zombie
                && NPC_NAME.equals(entity.getCustomName());
    }

    // Rescue Survival players who fall below Y = 0.
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getTo().getY() >= 0) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return;
        }

        String worldName =
                getConfig().getString("destination.world", "world");
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
    }

    // Hitting the NPC activates it, but never damages or kills it.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTrainerHit(EntityDamageByEntityEvent event) {
        if (!isTrainer(event.getEntity())) {
            return;
        }

        event.setCancelled(true);

        if (event.getDamager() instanceof Player) {
            activated = true;
        }
    }

    @EventHandler
    public void onTrainerDamage(EntityDamageEvent event) {
        if (isTrainer(event.getEntity())
                && !(event instanceof EntityDamageByEntityEvent)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTrainerTarget(EntityTargetEvent event) {
        if (isTrainer(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    private Player findNearestPlayer() {
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Player player : home.getWorld().getPlayers()) {
            if (player.isDead()
                    || player.getGameMode() == GameMode.CREATIVE
                    || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }

            double distance =
                    player.getLocation().distanceSquared(home);

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }

        return nearest;
    }

    private void knockBack(Player player) {
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

    @Override
    public void onDisable() {
        if (npc != null && npc.isValid()) {
            npc.remove();
        }
    }
}