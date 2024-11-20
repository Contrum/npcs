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
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class BukkitNpcTracker extends CommonNpcTracker<World, Player, ItemStack, Plugin> {

  public BukkitNpcTracker() {
    super();
    executor.scheduleAtFixedRate(() -> {
      for (Player player : Bukkit.getOnlinePlayers()) {
        for (Npc<World, Player, ItemStack, Plugin> npc : trackedNpcs()) {
          int spawnDistance = SPAWN_DISTANCE.defaultValue() * SPAWN_DISTANCE.defaultValue();

          Position pos = npc.position();
          if (!npc.world().equals(player.getWorld()) || !npc.world().isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
            npc.stopTrackingPlayer(player);
            continue;
          }

          double distance = BukkitPlatformUtil.distance(npc, player.getLocation());
          if (distance > spawnDistance) {
            npc.stopTrackingPlayer(player);
          } else {

            if (npc.isPlayerTracked(player)) {
              continue;
            }

            if (this.npcqueue.get(player) != null && this.npcqueue.get(player).contains(npc))
              continue;

            addToQueue(player, npc);
          }
        }
      }
    }, 0L, 20L, TimeUnit.MILLISECONDS);
  }

  @Override
  public double calculateDistance(Player player, Npc<World, Player, ItemStack, Plugin> npc) {
    return BukkitPlatformUtil.distance(npc, player.getLocation());
  }

}
