package me.sialim.buboniccore;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.data.Levelled;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.material.Cauldron;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import javax.naming.Name;
import java.io.*;
import java.rmi.registry.LocateRegistry;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
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

    @EventHandler public void onBlockBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (block.getType() == Material.WATER_CAULDRON) {
            Location cauldronLocation = block.getLocation();
            Collection<Entity> nearbyEntities = cauldronLocation.getWorld().getNearbyEntities(cauldronLocation.add(0, 1, 0), 1, 1, 1);
            for (Entity entity : nearbyEntities) {
                if (entity instanceof TextDisplay) {
                    entity.remove();
                    getLogger().info("Hologram removed because cauldron was broken.");
                }
            }
        }
    }

    @EventHandler public void onItemDrop(PlayerDropItemEvent e) {
        Item droppedItem = e.getItemDrop();

        getLogger().info("Item " + droppedItem.getName() + " dropped by player: " + e.getPlayer().getName());
        if(!isIngredient(droppedItem.getItemStack().getType())) return;

        new BukkitRunnable() {
            private int ticks = 0;

            @Override
            public void run() {
                if (droppedItem.isValid()) {
                    Location itemLocation = droppedItem.getLocation();

                    int roundedItemX = (int) Math.floor(itemLocation.getX());
                    int roundedItemY = (int) Math.floor(itemLocation.getY());
                    int roundedItemZ = (int) Math.floor(itemLocation.getZ());

                    getLogger().info("Dropped item adjusted location: " + roundedItemX + ", " + roundedItemY + ", " + roundedItemZ);

                    Block block = itemLocation.getWorld().getBlockAt(roundedItemX, roundedItemY, roundedItemZ);

                    if (block.getType() == Material.WATER_CAULDRON) {
                        getLogger().info("Cauldron found at: " + block.getLocation());

                        if (isCauldronFilled(block) && hasLitCampfireBelow(block)) {
                            Material flowerType = droppedItem.getItemStack().getType();
                            if (isIngredient(flowerType)) {
                                getLogger().info("Valid ingredient dropped: " + flowerType.name());

                                if (!isHologramAboveCauldron(block.getLocation())) {
                                    summonHologram(block.getLocation(), flowerType);
                                    startBrewingProcess(block.getLocation(), flowerType);
                                    getLogger().info("Hologram summoned above cauldron.");
                                    droppedItem.remove();
                                    cancel();
                                } else {
                                    getLogger().info("There is already a hologram above this cauldron.");
                                }
                            }
                        } else {
                            getLogger().info("Cauldron not filled or no lit campfire below.");
                        }
                    }

                    if (++ticks >= 40) {
                        getLogger().info("Task cancelled after 40 ticks.");
                        cancel();
                    }
                } else {
                    getLogger().info("Dropped item is no longer valid.");
                    cancel();
                }
            }
        }.runTaskTimer(this, 0, 10);
    }

    private boolean isCauldronFilled(Block cauldronBlock) {
        if (cauldronBlock.getBlockData() instanceof Levelled) {
            Levelled levelled = (Levelled) cauldronBlock.getBlockData();
            return levelled.getLevel() == levelled.getMaximumLevel();
        }
        return false;
    }

    private boolean hasLitCampfireBelow(Block cauldronBlock) {
        Block belowBlock = cauldronBlock.getRelative(BlockFace.DOWN);
        return belowBlock.getType() == Material.CAMPFIRE && belowBlock.getBlockData().getAsString().contains("lit=true");
    }

    private boolean isIngredient(Material material) {
        return material == Material.POPPY || material == Material.DANDELION || material == Material.BLUE_ORCHID
                || material == Material.ALLIUM || material == Material.AZURE_BLUET || material == Material.RED_TULIP
                || material == Material.PINK_TULIP || material == Material.OXEYE_DAISY || material == Material.LILY_OF_THE_VALLEY
                || material == Material.CORNFLOWER || material == Material.WITHER_ROSE;
    }

    private boolean isHologramAboveCauldron(Location cauldronLoc) {
        return cauldronLoc.getWorld().getNearbyEntities(cauldronLoc.add(0, 1, 0), 1, 1, 1)
                .stream()
                .anyMatch(entity -> entity instanceof TextDisplay);
    }

    private TextDisplay getHologramAboveCauldron(Location cauldronLoc) {
        return (TextDisplay) cauldronLoc.getWorld().getNearbyEntities(cauldronLoc.add(0, 1, 0), 1, 1, 1)
                .stream()
                .filter(entity -> entity instanceof TextDisplay)
                .findFirst()
                .orElse(null); // Return null if no TextDisplay is found
    }

    private void summonHologram(Location location, Material flowerType) {
        TextDisplay hologram = (TextDisplay) location.getWorld().spawn(location.add(0.5, 1.5, 0.5), TextDisplay.class);
        hologram.setText("Previous Ingredient: " + flowerType.name() + "\nBrewing: 5:00");
        hologram.setPersistent(true);
        hologram.setViewRange(0.035f);
        hologram.setBillboard(Display.Billboard.VERTICAL);
        hologram.getPersistentDataContainer().set(new NamespacedKey(this, "ingredients"), PersistentDataType.STRING, "");
    }

    public void addIngredient(Block cauldronBlock, Item item) {
        TextDisplay hologram = getHologramAboveCauldron(cauldronBlock.getLocation());
        if (hologram == null) return;

        if (isCauldronBrewing(cauldronBlock)) {

        }
    }

    private boolean isCauldronBrewing(Block cauldronBlock) {
        return getHologramAboveCauldron(cauldronBlock.getLocation()).getPersistentDataContainer().has(new NamespacedKey(this, "isBrewing"), PersistentDataType.INTEGER);
    }

    private void startBrewingProcess(Location cauldronLoc, Material flowerType) {
        TextDisplay hologram = getHologramAboveCauldron(cauldronLoc);
        hologram.getPersistentDataContainer().set(new NamespacedKey(this, "isBrewing"), PersistentDataType.INTEGER, 1);

        int brewingTime = random.nextInt(11) + 25;

        hologram.setText("Previous Ingredient: " + flowerType.name() + "\nBrewing: " + formatTime(brewingTime));

        new BukkitRunnable() {
            private int timeLeft = brewingTime;
            @Override public void run() {
                if (timeLeft <= 0) {
                    completeBrewingProcess(cauldronLoc, hologram, flowerType);
                    cancel();
                    return;
                }

                hologram.setText("Previous Ingredient: " + flowerType.name() + "\nBrewing: " + formatTime(timeLeft));

                timeLeft--;
            }
        }.runTaskTimer(this, 0L, 20L);
    }

    private String getPreviousIngredients(TextDisplay hologram) {
        return hologram.getPersistentDataContainer().get(new NamespacedKey(this, "ingredients"), PersistentDataType.STRING);
    }

    private void completeBrewingProcess(Location cauldronLocation, TextDisplay hologram, Material flowerType) {
        hologram.getPersistentDataContainer().remove(new NamespacedKey(this, "isBrewing"));
        hologram.setText("Previous Ingredient: " + flowerType.name() + "\nBrewing Complete!");
    }

    private String formatTime(int seconds) {
        int minutes = seconds / 60;
        int remainingSeconds = seconds % 60;
        return String.format("%d:%02d", minutes, remainingSeconds);
    }

    private void spawnBubblingParticles(Location cauldronLoc) {
        World world = cauldronLoc.getWorld();
        if (world != null) {
            Location particleLocation = cauldronLoc.clone().add(0.5, 0.7, 0.5);

            world.spawnParticle(Particle.BUBBLE_COLUMN_UP, particleLocation, 10, 0.3, 0.2, 0.3, 0.05);
        }
    }
}