package me.sialim.buboniccore;

import org.bukkit.Bukkit;
import org.bukkit.block.CreatureSpawner;
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
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.util.*;

public final class BubonicCore extends JavaPlugin implements Listener {
    private File dataFile;
    private List<String> infectedPlayers;
    private Random random;

    @Override
    public void onEnable()
    {
        dataFile = new File(getDataFolder(), "infected_players.txt");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        loadInfectedPlayers();

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable()
    {
        saveInfectedPlayers();
    }

    @EventHandler public void onEntityAttack(EntityDamageByEntityEvent e)
    {
        Entity damager = e.getDamager();
        Entity damaged = e.getEntity();

        if (damager instanceof Zombie && damaged instanceof Player)
        {
            Zombie zombie = (Zombie) damager;
            if (isInfectingZombie(zombie))
            {
                Player p = (Player) damaged;
                if (Math.random() < 0.3)
                {
                    p.sendMessage("You have been infected with a deadly virus!");
                }
            }
        }
    }

    @EventHandler public void onCreatureSPawn(CreatureSpawnEvent e)
    {
        Entity entity = e.getEntity();
        if (entity instanceof Zombie)
        {
            Zombie zombie = (Zombie) entity;
            if (shouldBeInfectingZombie())
            {
                zombie.setCustomName("Infecting Zombie");
                zombie.setCustomNameVisible(true);
            }
        }
    }

    private boolean shouldBeInfectingZombie()
    {
        return random.nextDouble() < 0.5;
    }

    private boolean isInfectingZombie(Zombie zombie)
    {
        return zombie.getCustomName() != null && zombie.getCustomName().equals("Infecting Zombie");
    }

    private void loadInfectedPlayers()
    {
        infectedPlayers = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(dataFile)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                infectedPlayers.add(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveInfectedPlayers()
    {
        try (FileWriter writer = new FileWriter(dataFile))
        {
            for (String pUUID : infectedPlayers)
            {
                writer.write(pUUID + System.lineSeparator());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
