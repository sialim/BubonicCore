package me.sialim.buboniccore;

import org.bukkit.*;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.naming.Name;
import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class BubonicCore extends JavaPlugin implements Listener, CommandExecutor {
    private File file;
    private Random random;
    private Map<UUID, Integer> infectedTime = new ConcurrentHashMap<>();
    private Map<UUID, Integer> lastNotifiedStage = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        random = new Random();
        file = new File(getDataFolder(), "infectionData.txt");

        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("cure").setExecutor(this);
        getCommand("infect").setExecutor(this);
        loadAllInfectionData();
        startDiseaseProgressionTask();
        startParticleEffectTask();
    }

    @Override
    public void onDisable() {
        saveAllInfectionData();
    }

    @EventHandler
    public void onEntitySpawn(EntitySpawnEvent e) {
        if (e.getEntity() instanceof Zombie z) {
            double chance = Math.random();
            if (chance < 0.1) {
                z.getPersistentDataContainer().set(new NamespacedKey(this, "infectious"), PersistentDataType.INTEGER, 1);
            }
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player p && e.getDamager() instanceof Zombie z) {
            if (z.getPersistentDataContainer().has(new NamespacedKey(this, "infectious"), PersistentDataType.INTEGER)) {
                double infectionChance = Math.random();
                if (infectionChance < 0.5) {
                    infectPlayer(p);
                }
            }
        }
    }

    public void infectPlayer(Player p) {
        UUID uuid = p.getUniqueId();
        infectedTime.putIfAbsent(uuid, 0);
        p.sendMessage("You have been infected!");
    }

    private void startDiseaseProgressionTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (infectedTime.isEmpty()) return;

                for (UUID uuid : infectedTime.keySet()) {
                    Player p = Bukkit.getPlayer(uuid);

                    int seconds = infectedTime.get(uuid);
                    infectedTime.put(uuid, seconds + 1);

                    int stage = calculateDiseaseStage(seconds);
                    if (p != null) {
                        if (shouldNotify(p, stage)) {
                            applyStageEffect(p, stage);
                        }

                        if (isPlayerCured(p)) {
                            curePlayer(p);
                        }
                    } else {
                        if (shouldNotify(uuid, stage)) {
                            applyStageEffect(uuid, stage);
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void startParticleEffectTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Location pLoc = p.getLocation();
                    World w = p.getWorld();

                    Collection<Entity> nearbyEntities = w.getNearbyEntities(pLoc, 20, 20, 20);
                    for (Entity e : nearbyEntities) {
                        if (e instanceof Zombie z) {
                            if (z.getPersistentDataContainer().has(new NamespacedKey(BubonicCore.this, "infectious"), PersistentDataType.INTEGER)) {
                                spawnBlackPotionParticles(z.getLocation());
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private void spawnBlackPotionParticles(Location location) {
        Particle.DustOptions dustOptions = new Particle.DustOptions(Color.BLACK, 1.0f);
        location.getWorld().spawnParticle(Particle.DUST, location, 10, dustOptions);
    }

    private boolean shouldNotify(Player p, int stage) {
        UUID uuid = p.getUniqueId();
        int lastStage = lastNotifiedStage.getOrDefault(uuid, -1);
        if (stage > lastStage) {
            lastNotifiedStage.put(uuid, stage);
            return true;
        }
        return false;
    }

    private boolean shouldNotify(UUID uuid, int stage) {
        int lastStage = lastNotifiedStage.getOrDefault(uuid, -1);
        if (stage > lastStage) {
            lastNotifiedStage.put(uuid, stage);
            return true;
        }
        return false;
    }

    private void saveAllInfectionData() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (UUID uuid : infectedTime.keySet()) {
                int seconds = infectedTime.get(uuid);
                writer.write(uuid.toString() + "," + seconds + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadAllInfectionData() {
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] data = line.split(",");
                    UUID uuid = UUID.fromString(data[0]);
                    int seconds = Integer.parseInt(data[1]);
                    infectedTime.put(uuid, seconds);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private int calculateDiseaseStage(int seconds) {
        return seconds / 60;
    }

    private void applyStageEffect(Player p, int stage) { // Called every second
        p.sendMessage("You have reached stage: " + stage);
        getLogger().info(p.getName() + " has reached stage: " + stage);
    }

    private void applyStageEffect(UUID uuid, int stage) { // Called every second
        getLogger().info(Bukkit.getOfflinePlayer(uuid).getName() + " has reached stage: " + stage);
    }

    public void curePlayer(Player p) {
        UUID uuid = p.getUniqueId();
        infectedTime.remove(uuid);
        p.sendMessage("You have been cured.");
    }

    private boolean isPlayerCured(Player p) {
        UUID uuid = p.getUniqueId();
        return false;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (sender instanceof Player p) {
            switch (label) {
                case "cure": {
                    if (!p.hasPermission("bubonic.cure")) {
                        p.sendMessage(ChatColor.WHITE + "[BubonicCore] " + ChatColor.RED + "Missing permission ('bubonic.cure')");
                        return false;
                    }
                    if (args.length < 1) {
                        curePlayer(p);
                    } else {
                        curePlayer(Bukkit.getPlayer(args[0]));
                    }
                    return true;
                }
                case "infect": {
                    if (!p.hasPermission("bubonic.infect")) {
                        p.sendMessage(ChatColor.WHITE + "[BubonicCore] " + ChatColor.RED + "Missing permission ('bubonic.infect')");
                        return false;
                    }
                    if (args.length < 1) {
                        infectPlayer(p);
                    } else {
                        infectPlayer(Bukkit.getPlayer(args[0]));
                    }
                    return true;
                }
            }
        }
        return false;
    }
}
