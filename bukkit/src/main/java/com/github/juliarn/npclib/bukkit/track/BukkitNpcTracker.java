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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class BukkitNpcTracker extends CommonNpcTracker<World, Player, ItemStack, Plugin> {

  // Maximum NPCs to spawn per player per cycle
  private static final int MAX_SPAWNS_PER_CYCLE = 1;

  // How often to run the visibility check (in milliseconds)
  private static final long VISIBILITY_CHECK_INTERVAL = 100L;

  // How often to process the spawn queue (in milliseconds)
  private static final long QUEUE_PROCESSING_INTERVAL = 200L;

  // Cache distances to avoid recalculating
  private final Map<UUID, Map<Integer, Double>> distanceCache = new ConcurrentHashMap<>();

  // Track when we last calculated distances
  private final Map<UUID, Long> lastDistanceCheck = new ConcurrentHashMap<>();

  // How long to keep distance calculations in cache (in milliseconds)
  private static final long DISTANCE_CACHE_TTL = 500L;

  public BukkitNpcTracker() {
    // Schedule visibility checks at a reasonable interval
    executor.scheduleAtFixedRate(() -> {
      long currentTime = System.currentTimeMillis();

      for (Player player : Bukkit.getOnlinePlayers()) {
        UUID playerUuid = player.getUniqueId();

        // Clean up distance cache for players that haven't been checked recently
        if (currentTime - lastDistanceCheck.getOrDefault(playerUuid, 0L) > DISTANCE_CACHE_TTL * 2) {
          distanceCache.remove(playerUuid);
          lastDistanceCheck.remove(playerUuid);
        }

        // Update the distance cache for this player
        Map<Integer, Double> playerDistances = distanceCache.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>());
        lastDistanceCheck.put(playerUuid, currentTime);

        // Check NPC visibility
        for (Npc<World, Player, ItemStack, Plugin> npc : trackedNpcs()) {
          Position pos = npc.position();

          // Skip NPCs in unloaded chunks or different worlds
          if (!npc.world().equals(player.getWorld()) || !npc.world().isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
            npc.stopTrackingPlayer(player);
            playerDistances.remove(npc.entityId());
            continue;
          }

          // Calculate or get cached distance
          double distance;
          if (currentTime - lastDistanceCheck.getOrDefault(playerUuid, 0L) > DISTANCE_CACHE_TTL) {
            distance = BukkitPlatformUtil.distance(npc, player.getLocation());
            playerDistances.put(npc.entityId(), distance);
          } else {
            distance = playerDistances.getOrDefault(npc.entityId(),
              BukkitPlatformUtil.distance(npc, player.getLocation()));
          }

          int spawnDistance = SPAWN_DISTANCE.defaultValue() * SPAWN_DISTANCE.defaultValue();

          if (distance > spawnDistance) {
            // Out of range, stop tracking
            npc.stopTrackingPlayer(player);
          } else if (!npc.isPlayerTracked(player)) {
            // In range but not tracked, queue for spawning if not already queued
            Set<Npc<World, Player, ItemStack, Plugin>> playerQueue = npcqueue.get(player);
            if (playerQueue == null || !playerQueue.contains(npc)) {
              addToQueue(player, npc);
            }
          }
        }
      }
    }, 0L, VISIBILITY_CHECK_INTERVAL, TimeUnit.MILLISECONDS);

    // Process the queue at a less frequent interval
    executor.scheduleAtFixedRate(() -> {
      for (Map.Entry<Player, Set<Npc<World, Player, ItemStack, Plugin>>> entry : this.npcqueue.entrySet()) {
        Player player = entry.getKey();
        if (!player.isOnline()) {
          npcqueue.remove(player);
          continue;
        }

        Set<Npc<World, Player, ItemStack, Plugin>> npcs = entry.getValue();
        if (npcs.isEmpty()) continue;

        // Convert to list and sort by distance
        List<Npc<World, Player, ItemStack, Plugin>> sortedNpcs = new ArrayList<>(npcs);
        UUID playerUuid = player.getUniqueId();

        // Use cached distances when sorting
        Map<Integer, Double> playerDistances = distanceCache.getOrDefault(playerUuid, Collections.emptyMap());
        sortedNpcs.sort((npc1, npc2) -> {
          double distance1 = playerDistances.getOrDefault(npc1.entityId(),
            calculateDistance(player, npc1));
          double distance2 = playerDistances.getOrDefault(npc2.entityId(),
            calculateDistance(player, npc2));
          return Double.compare(distance1, distance2);
        });

        // Process only a limited number of NPCs per cycle
        AtomicInteger spawnCount = new AtomicInteger(0);
        Iterator<Npc<World, Player, ItemStack, Plugin>> iterator = sortedNpcs.iterator();

        while (iterator.hasNext() && spawnCount.get() < MAX_SPAWNS_PER_CYCLE) {
          Npc<World, Player, ItemStack, Plugin> npc = iterator.next();
          if (npc.world().equals(player.getWorld())) {
            npc.trackPlayer(player);
            npcs.remove(npc);
            spawnCount.incrementAndGet();
          } else {
            // Remove NPCs in different worlds from queue
            npcs.remove(npc);
          }
        }
      }
    }, 0L, QUEUE_PROCESSING_INTERVAL, TimeUnit.MILLISECONDS);
  }

  @Override
  public double calculateDistance(Player player, Npc<World, Player, ItemStack, Plugin> npc) {
    return BukkitPlatformUtil.distance(npc, player.getLocation());
  }

  // Clear the distance cache for a player when they disconnect
  public void removePlayerFromCache(Player player) {
    UUID playerUuid = player.getUniqueId();
    distanceCache.remove(playerUuid);
    lastDistanceCheck.remove(playerUuid);
  }
}
