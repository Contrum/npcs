/*
 * This file is part of npc-lib, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2022-2023 Julian M., Pasqual K. and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.github.juliarn.npclib.bukkit.track;

import static com.github.juliarn.npclib.api.NpcActionController.SPAWN_DISTANCE;

import com.github.juliarn.npclib.api.Npc;
import com.github.juliarn.npclib.api.Position;
import com.github.juliarn.npclib.bukkit.util.BukkitPlatformUtil;
import com.github.juliarn.npclib.common.CommonNpcTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class BukkitNpcTracker extends CommonNpcTracker<World, Player, ItemStack, Plugin> {

  private static final Logger LOGGER = Bukkit.getLogger();

  // Maximum NPCs to spawn per player per cycle - increased for better responsiveness
  private static final int MAX_SPAWNS_PER_CYCLE = 5;

  // How often to run the visibility check (in milliseconds) - more frequent
  private static final long VISIBILITY_CHECK_INTERVAL = 50L;

  // How often to process the spawn queue (in milliseconds) - more frequent
  private static final long QUEUE_PROCESSING_INTERVAL = 50L;

  // Cache distances to avoid recalculating
  private final Map<UUID, Map<Integer, Double>> distanceCache = new ConcurrentHashMap<>();

  // Track when we last calculated distances
  private final Map<UUID, Long> lastDistanceCheck = new ConcurrentHashMap<>();

  // How long to keep distance calculations in cache (in milliseconds) - shorter cache for more accurate distance tracking
  private static final long DISTANCE_CACHE_TTL = 100L;

  public BukkitNpcTracker() {
    // Schedule visibility checks at a reasonable interval
    executor.scheduleAtFixedRate(() -> {
      try {
        performVisibilityCheck();
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE, "Error during NPC visibility check", e);
      }
    }, 0L, VISIBILITY_CHECK_INTERVAL, TimeUnit.MILLISECONDS);

    // Process the queue at a less frequent interval
    executor.scheduleAtFixedRate(() -> {
      try {
        processSpawnQueue();
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE, "Error during NPC spawn queue processing", e);
      }
    }, 0L, QUEUE_PROCESSING_INTERVAL, TimeUnit.MILLISECONDS);

    // Full resync every 5 seconds to catch any missed NPCs
    executor.scheduleAtFixedRate(() -> {
      try {
        performFullResync();
      } catch (Exception e) {
        LOGGER.log(Level.SEVERE, "Error during full NPC resync", e);
      }
    }, 5000L, 5000L, TimeUnit.MILLISECONDS);
  }

  private void performVisibilityCheck() {
    long currentTime = System.currentTimeMillis();

    // Create a copy to avoid ConcurrentModificationException
    List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());

    for (Player player : onlinePlayers) {
      try {
        if (player == null || !player.isOnline()) {
          continue;
        }

        UUID playerUuid = player.getUniqueId();

        // Clean up distance cache for players that haven't been checked recently
        if (currentTime - lastDistanceCheck.getOrDefault(playerUuid, 0L) > DISTANCE_CACHE_TTL * 2) {
          distanceCache.remove(playerUuid);
          lastDistanceCheck.remove(playerUuid);
        }

        // Update the distance cache for this player
        Map<Integer, Double> playerDistances = distanceCache.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        lastDistanceCheck.put(playerUuid, currentTime);

        // Check NPC visibility - create defensive copy
        List<Npc<World, Player, ItemStack, Plugin>> npcsToCheck = new ArrayList<>(trackedNpcs());

        for (Npc<World, Player, ItemStack, Plugin> npc : npcsToCheck) {
          try {
            checkNpcVisibilityForPlayer(npc, player, playerDistances, currentTime, playerUuid);
          } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error checking NPC visibility for player " + player.getName(), e);
          }
        }
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Error processing player " + (player != null ? player.getName() : "null"), e);
      }
    }
  }

  private void checkNpcVisibilityForPlayer(Npc<World, Player, ItemStack, Plugin> npc, Player player,
    Map<Integer, Double> playerDistances, long currentTime, UUID playerUuid) {

    // Null checks
    if (npc == null || player == null) {
      return;
    }

    // Log with null checks
    String npcName = "unknown";
    try {
      if (npc.profile() != null && npc.profile().name() != null) {
        npcName = npc.profile().name();
      }
    } catch (Exception e) {
      LOGGER.log(Level.FINE, "Could not get NPC name", e);
    }

    Position pos = npc.position();
    World npcWorld = npc.world();
    World playerWorld = player.getWorld();

    // Null checks for critical objects
    if (pos == null || npcWorld == null || playerWorld == null) {
      LOGGER.warning("Null position or world detected for NPC " + npcName);
      return;
    }

    // Skip NPCs in unloaded chunks or different worlds
    if (!npcWorld.equals(playerWorld)) {
      safeStopTracking(npc, player);
      playerDistances.remove(npc.entityId());
      return;
    }

    // Check chunk loading safely
    try {
      if (!npcWorld.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
        safeStopTracking(npc, player);
        playerDistances.remove(npc.entityId());
        return;
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error checking chunk loaded status for NPC " + npcName, e);
      return;
    }

    // Calculate or get cached distance
    double distance;
    try {
      if (currentTime - lastDistanceCheck.getOrDefault(playerUuid, 0L) > DISTANCE_CACHE_TTL) {
        distance = BukkitPlatformUtil.distance(npc, player.getLocation());
        playerDistances.put(npc.entityId(), distance);
      } else {
        distance = playerDistances.getOrDefault(npc.entityId(),
          BukkitPlatformUtil.distance(npc, player.getLocation()));
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error calculating distance for NPC " + npcName, e);
      return;
    }

    int spawnDistance = SPAWN_DISTANCE.defaultValue() * SPAWN_DISTANCE.defaultValue();

    if (distance > spawnDistance) {
      // Out of range, stop tracking
      safeStopTracking(npc, player);
    } else if (!npc.isPlayerTracked(player)) {
      // In range but not tracked, immediately spawn if close or queue for spawning
      if (distance <= (spawnDistance * 0.25)) {
        // Very close, spawn immediately to prevent flicker
        try {
          npc.trackPlayer(player);
        } catch (Exception e) {
          LOGGER.log(Level.WARNING, "Error immediately tracking close NPC", e);
          addToQueue(player, npc);
        }
      } else {
        // Further away, queue for spawning if not already queued
        Set<Npc<World, Player, ItemStack, Plugin>> playerQueue = npcqueue.get(player);
        if (playerQueue == null || !playerQueue.contains(npc)) {
          addToQueue(player, npc);
        }
      }
    }
  }

  private void processSpawnQueue() {
    // Create defensive copy to avoid ConcurrentModificationException
    List<Map.Entry<Player, Set<Npc<World, Player, ItemStack, Plugin>>>> entries =
      new ArrayList<>(this.npcqueue.entrySet());

    for (Map.Entry<Player, Set<Npc<World, Player, ItemStack, Plugin>>> entry : entries) {
      try {
        Player player = entry.getKey();
        if (player == null || !player.isOnline()) {
          npcqueue.remove(player);
          continue;
        }

        Set<Npc<World, Player, ItemStack, Plugin>> npcs = entry.getValue();
        if (npcs == null || npcs.isEmpty()) continue;

        // Convert to list and sort by distance
        List<Npc<World, Player, ItemStack, Plugin>> sortedNpcs = new ArrayList<>(npcs);
        UUID playerUuid = player.getUniqueId();

        // Use cached distances when sorting
        Map<Integer, Double> playerDistances = distanceCache.getOrDefault(playerUuid, Collections.emptyMap());
        sortedNpcs.sort((npc1, npc2) -> {
          try {
            double distance1 = playerDistances.getOrDefault(npc1.entityId(),
              calculateDistance(player, npc1));
            double distance2 = playerDistances.getOrDefault(npc2.entityId(),
              calculateDistance(player, npc2));
            return Double.compare(distance1, distance2);
          } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error comparing NPC distances", e);
            return 0;
          }
        });

        // Process only a limited number of NPCs per cycle
        AtomicInteger spawnCount = new AtomicInteger(0);

        // Use safe iteration
        List<Npc<World, Player, ItemStack, Plugin>> toRemove = new ArrayList<>();

        for (Npc<World, Player, ItemStack, Plugin> npc : sortedNpcs) {
          if (spawnCount.get() >= MAX_SPAWNS_PER_CYCLE) {
            break;
          }

          try {
            if (npc != null && npc.world() != null && npc.world().equals(player.getWorld())) {
              npc.trackPlayer(player);
              toRemove.add(npc);
              spawnCount.incrementAndGet();
            } else {
              // Remove NPCs in different worlds from queue
              toRemove.add(npc);
            }
          } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing NPC in spawn queue", e);
            toRemove.add(npc); // Remove problematic NPC
          }
        }

        // Remove processed NPCs safely
        npcs.removeAll(toRemove);

      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Error processing spawn queue entry", e);
      }
    }
  }

  @Override
  public double calculateDistance(Player player, Npc<World, Player, ItemStack, Plugin> npc) {
    try {
      if (player == null || npc == null) {
        return Double.MAX_VALUE;
      }
      return BukkitPlatformUtil.distance(npc, player.getLocation());
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error calculating distance", e);
      return Double.MAX_VALUE;
    }
  }

  // Safe wrapper for stop tracking
  private void safeStopTracking(Npc<World, Player, ItemStack, Plugin> npc, Player player) {
    try {
      npc.stopTrackingPlayer(player);
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error stopping NPC tracking", e);
    }
  }

  // Clear the distance cache for a player when they disconnect
  public void removePlayerFromCache(Player player) {
    try {
      if (player != null) {
        UUID playerUuid = player.getUniqueId();
        distanceCache.remove(playerUuid);
        lastDistanceCheck.remove(playerUuid);
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error removing player from cache", e);
    }
  }

  // Perform full resync for all players - catches any missed NPCs
  private void performFullResync() {
    List<Player> onlinePlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
    for (Player player : onlinePlayers) {
      if (player != null && player.isOnline()) {
        forceResyncNpcsForPlayer(player);
      }
    }
  }

  // Force resync NPCs for a player - useful for fixing visibility issues
  public void forceResyncNpcsForPlayer(Player player) {
    if (player == null || !player.isOnline()) {
      return;
    }

    try {
      // Clear cache for immediate recalculation
      UUID playerUuid = player.getUniqueId();
      distanceCache.remove(playerUuid);
      lastDistanceCheck.remove(playerUuid);

      // Check all NPCs for this player
      List<Npc<World, Player, ItemStack, Plugin>> npcsToCheck = new ArrayList<>(trackedNpcs());
      int spawnDistance = SPAWN_DISTANCE.defaultValue() * SPAWN_DISTANCE.defaultValue();

      for (Npc<World, Player, ItemStack, Plugin> npc : npcsToCheck) {
        try {
          if (npc.world().equals(player.getWorld())) {
            double distance = calculateDistance(player, npc);
            
            if (distance <= spawnDistance && !npc.isPlayerTracked(player)) {
              // Should be visible but isn't - force track
              npc.trackPlayer(player);
            } else if (distance > spawnDistance && npc.isPlayerTracked(player)) {
              // Shouldn't be visible but is - stop tracking
              npc.stopTrackingPlayer(player);
            }
          }
        } catch (Exception e) {
          LOGGER.log(Level.WARNING, "Error during force resync for NPC", e);
        }
      }
    } catch (Exception e) {
      LOGGER.log(Level.WARNING, "Error during force resync for player " + player.getName(), e);
    }
  }
}
