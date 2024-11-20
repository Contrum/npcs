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

package com.github.juliarn.npclib.common;

import com.github.juliarn.npclib.api.Npc;
import com.github.juliarn.npclib.api.NpcTracker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

public abstract class CommonNpcTracker<W, P, I, E> implements NpcTracker<W, P, I, E> {

  protected final Logger logger = Logger.getLogger("npc-lib");

  protected final Set<Npc<W, P, I, E>> trackedNpcs = Collections.synchronizedSet(new HashSet<>());
  protected final Map<P, Set<Npc<W, P, I, E>>> npcqueue = new ConcurrentHashMap<>();

  protected final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2,
    r -> {
      Thread thread = new Thread(r);
      thread.setName("npc-lib NPC Tracker");
      thread.setDaemon(true);
      thread.setUncaughtExceptionHandler((t, e) -> {
        logger.severe("Uncaught exception in npc-lib NPC Tracker thread");
        e.printStackTrace();
      });
      return thread;
    }
  );

  public CommonNpcTracker() {
    executor.scheduleAtFixedRate(() -> {
      for (Map.Entry<P, Set<Npc<W, P, I, E>>> entry : this.npcqueue.entrySet()) {
        P player = entry.getKey();
        List<Npc<W, P, I, E>> npcs = new ArrayList<>(entry.getValue());

        npcs.sort((npc1, npc2) -> {
          double distance1 = calculateDistance(player, npc1);
          double distance2 = calculateDistance(player, npc2);
          return Double.compare(distance1, distance2);
        });

        if (npcs.isEmpty()) continue;

        Npc<W, P, I, E> npc = npcs.iterator().next();
        npc.trackPlayer(player);
        entry.getValue().remove(npc);
      }
    }, 0L, 1L, TimeUnit.SECONDS);
  }

  public abstract double calculateDistance(P player, Npc<W, P, I, E> npc);

  @Override
  public @Nullable Npc<W, P, I, E> npcById(int entityId) {
    for (Npc<W, P, I, E> trackedNpc : this.trackedNpcs) {
      if (trackedNpc.entityId() == entityId) {
        return trackedNpc;
      }
    }

    return null;
  }

  @Override
  public @Nullable Npc<W, P, I, E> npcByUniqueId(@NotNull UUID uniqueId) {
    for (Npc<W, P, I, E> trackedNpc : this.trackedNpcs) {
      if (trackedNpc.profile().uniqueId().equals(uniqueId)) {
        return trackedNpc;
      }
    }

    return null;
  }

  @Override
  public void trackNpc(@NotNull Npc<W, P, I, E> npc) {
    this.trackedNpcs.add(npc);
  }

  @Override
  public void stopTrackingNpc(@NotNull Npc<W, P, I, E> npc) {
    this.trackedNpcs.remove(npc);
  }

  @Override
  public @UnmodifiableView @NotNull Collection<Npc<W, P, I, E>> trackedNpcs() {
    return Collections.unmodifiableCollection(this.trackedNpcs);
  }

  @Override
  public void addToQueue(@NotNull P player, @NotNull Npc<W, P, I, E> npc) {
    npcqueue.computeIfAbsent(player, k -> new HashSet<>()).add(npc);
  }

  @Override
  public void removeFromQueue(@NotNull P player, @NotNull Npc<W, P, I, E> npc) {
    Set<Npc<W, P, I, E>> npcs = npcqueue.get(player);
    if (npcs != null) {
      npcs.remove(npc);
    }
  }
}
