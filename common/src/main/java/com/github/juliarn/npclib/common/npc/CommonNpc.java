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

package com.github.juliarn.npclib.common.npc;

import com.github.juliarn.npclib.api.Npc;
import com.github.juliarn.npclib.api.NpcTracker;
import com.github.juliarn.npclib.api.Platform;
import com.github.juliarn.npclib.api.Position;
import com.github.juliarn.npclib.api.flag.NpcFlag;
import com.github.juliarn.npclib.api.profile.Profile;
import com.github.juliarn.npclib.api.protocol.NpcSpecificOutboundPacket;
import com.github.juliarn.npclib.api.protocol.enums.EntityAnimation;
import com.github.juliarn.npclib.api.protocol.enums.ItemSlot;
import com.github.juliarn.npclib.api.protocol.enums.PlayerInfoAction;
import com.github.juliarn.npclib.api.protocol.meta.EntityMetadataFactory;
import com.github.juliarn.npclib.api.settings.NpcSettings;
import com.github.juliarn.npclib.api.util.Util;
import com.github.juliarn.npclib.common.event.DefaultHideNpcEvent;
import com.github.juliarn.npclib.common.event.DefaultShowNpcEvent;
import com.github.juliarn.npclib.common.flag.CommonNpcFlaggedObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.contrum.holograms.api.Hologram;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

public class CommonNpc<W, P, I, E> extends CommonNpcFlaggedObject implements Npc<W, P, I, E> {

  protected final int entityId;
  protected Profile.Resolved profile;

  protected final W world;
  protected Position pos;

  protected final Platform<W, P, I, E> platform;
  protected final NpcSettings<P> npcSettings;

  protected final Set<P> trackedPlayers = Collections.synchronizedSet(new HashSet<>());
  protected final Set<P> includedPlayers = Collections.synchronizedSet(new HashSet<>());
  protected final Map<ItemSlot, I> equipment = Collections.synchronizedMap(new HashMap<>());

  protected final List<String> commands = Collections.synchronizedList(new ArrayList<>());

  protected Hologram hologram;

  protected Consumer<P> onRightClick;
  protected Consumer<P> onLeftClick;

  public CommonNpc(
    @NotNull Map<NpcFlag<?>, Optional<?>> flags,
    int entityId,
    @NotNull Profile.Resolved profile,
    @NotNull W world,
    @NotNull Position pos,
    @NotNull Platform<W, P, I, E> platform,
    @NotNull NpcSettings<P> npcSettings
  ) {
    super(flags);
    this.entityId = entityId;
    this.profile = profile;
    this.world = world;
    this.pos = pos;
    this.platform = platform;
    this.npcSettings = npcSettings;
  }

  @Override
  public int entityId() {
    return this.entityId;
  }

  @Override
  public @NotNull Profile.Resolved profile() {
    return this.profile;
  }

  public void setProfile(@NotNull Profile.Resolved profile) {
    this.profile = profile;
  }

  @Override
  public @NotNull W world() {
    return this.world;
  }

  @Override
  public @NotNull Position position() {
    return this.pos;
  }

  @Override
  public @NotNull Npc<W, P, I, E> teleport(@NotNull Position position) {
    this.pos = position;
    this.platform.packetFactory().createRotationPacket(position.yaw(), position.pitch()).toSpecific(this);
    return this;
  }

  @Override
  public @NotNull NpcSettings<P> settings() {
    return this.npcSettings;
  }

  @Override
  public @NotNull Platform<W, P, I, E> platform() {
    return this.platform;
  }

  @Override
  public @NotNull NpcTracker<W, P, I, E> npcTracker() {
    return this.platform.npcTracker();
  }

  @Override
  public boolean shouldIncludePlayer(@NotNull P player) {
    return this.npcSettings.trackingRule().shouldTrack(this, player);
  }

  @Override
  public @UnmodifiableView @NotNull Collection<P> includedPlayers() {
    return Collections.unmodifiableSet(this.includedPlayers);
  }

  @Override
  public boolean includesPlayer(@NotNull P player) {
    return this.includedPlayers.contains(player);
  }

  public I equipment(@NotNull ItemSlot slot) {
    return this.equipment.get(slot);
  }

  @Override
  public @NotNull Npc<W, P, I, E> addIncludedPlayer(@NotNull P player) {
    this.includedPlayers.add(player);
    return this;
  }

  @Override
  public @NotNull Npc<W, P, I, E> removeIncludedPlayer(@NotNull P player) {
    this.includedPlayers.remove(player);
    return this;
  }

  public boolean isPlayerTracked(P player) {
    return this.trackedPlayers.contains(player);
  }

  @Override
  @SuppressWarnings("unchecked")
  public @NotNull Npc<W, P, I, E> unlink() {
    // remove this npc from the tracked ones, do it first to prevent further player tracking
    this.npcTracker().stopTrackingNpc(this);

    // remove this npc for all tracked players
    Object[] players = this.trackedPlayers.toArray();
    for (Object player : players) {
      this.stopTrackingPlayer((P) player);
    }

    // for chaining
    return this;
  }

  @Override
  public @UnmodifiableView @NotNull Collection<P> trackedPlayers() {
    return Collections.unmodifiableSet(this.trackedPlayers);
  }

  @Override
  public boolean tracksPlayer(@NotNull P player) {
    return this.trackedPlayers.contains(player);
  }

  @Override
  public @NotNull Npc<W, P, I, E> trackPlayer(@NotNull P player) {
    // check if we should track the player
    if (this.shouldIncludePlayer(player)) {
      return this.forceTrackPlayer(player);
    }

    // nothing to do
    return this;
  }

  @Override
  public @NotNull Npc<W, P, I, E> forceTrackPlayer(@NotNull P player) {
    // check if the player is not already tracked
    if (!this.trackedPlayers.contains(player)) {
      // break early if the add is not wanted by plugin
      if (this.platform.eventManager().post(DefaultShowNpcEvent.pre(this, player)).cancelled()) {
        return this;
      }

      // register the player, prevent duplicate spawns in case the entity was spawned
      // by a different thread during processing of the pre-track event
      if (!this.trackedPlayers.add(player)) {
        return this;
      }

      // send the player info packet & schedule the actual add of the
      // player entity into the target world

      this.platform.packetFactory().createPlayerInfoPacket(PlayerInfoAction.ADD_PLAYER)
        .schedule(player, this);
      this.platform.taskManager().scheduleDelayedAsync(() -> {
        this.platform.packetFactory().createEntitySpawnPacket().schedule(player, this);

        this.platform.eventManager().post(DefaultShowNpcEvent.post(this, player));
      }, 10);
    }

    return this;
  }

  @Override
  public @NotNull Npc<W, P, I, E> stopTrackingPlayer(@NotNull P player) {
    // check if the player was previously tracked
    if (this.trackedPlayers.contains(player)) {
      // break early if the removal is not wanted by plugin
      if (this.platform.eventManager().post(DefaultHideNpcEvent.pre(this, player)).cancelled()) {
        return this;
      }

      // unregister the player, prevent duplicate remove packets in case the entity
      // was removed by a different thread during processing of the pre-hide event
      if (!this.trackedPlayers.remove(player)) {
        return this;
      }

      // schedule an entity remove (the player list change is not needed normally, but to make sure that the npc is gone)
      this.platform.packetFactory().createEntityRemovePacket().schedule(player, this);
      this.platform.packetFactory().createPlayerInfoPacket(PlayerInfoAction.REMOVE_PLAYER).schedule(player, this);

      // post the finish of the removal to all plugins
      this.platform.eventManager().post(DefaultHideNpcEvent.post(this, player));
    }

    // for chaining
    return this;
  }

  @Override
  public @NotNull NpcSpecificOutboundPacket<W, P, I, E> lookAt(@NotNull Position position) {
    double diffX = position.x() - this.pos.x();
    double diffY = position.y() - this.pos.y();
    double diffZ = position.z() - this.pos.z();

    double distanceXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
    double distanceY = Math.sqrt(distanceXZ * distanceXZ + diffY * diffY);

    double yaw = Math.toDegrees(Math.acos(diffX / distanceXZ));
    double pitch = Math.toDegrees(Math.acos(diffY / distanceY)) - 90;

    // correct yaw according to difference
    if (diffZ < 0) {
      yaw += Math.abs(180 - yaw) * 2;
    }
    yaw -= 90;

    return this.platform.packetFactory().createRotationPacket((float) yaw, (float) pitch).toSpecific(this);
  }

  @Override
  public @NotNull NpcSpecificOutboundPacket<W, P, I, E> playAnimation(@NotNull EntityAnimation animation) {
    return this.platform.packetFactory().createAnimationPacket(animation).toSpecific(this);
  }

  @Override
  public @NotNull NpcSpecificOutboundPacket<W, P, I, E> changeItem(@NotNull ItemSlot slot, @NotNull I item) {
    this.equipment.put(slot, item);
    return this.platform.packetFactory().createEquipmentPacket(slot, item).toSpecific(this);
  }

  @Override
  public @NotNull Npc<W, P, I, E> lookAtPlayer(@NotNull P player, @NotNull Position position) {
    double diffX = position.x() - this.pos.x();
    double diffY = position.y() - this.pos.y();
    double diffZ = position.z() - this.pos.z();

    double distanceXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
    double distanceY = Math.sqrt(distanceXZ * distanceXZ + diffY * diffY);

    double yaw = Math.toDegrees(Math.acos(diffX / distanceXZ));
    double pitch = Math.toDegrees(Math.acos(diffY / distanceY)) - 90;

    // correct yaw according to difference
    if (diffZ < 0) {
      yaw += Math.abs(180 - yaw) * 2;
    }
    yaw -= 90;

    this.platform.packetFactory().createRotationPacket((float) yaw, (float) pitch).schedule(player, this);
    return this;
  }

  @Override
  public @NotNull Npc<W, P, I, E> addCommand(@NotNull String command) {
    this.commands.add(command);
    return this;
  }

  @Override
  public @NotNull Npc<W, P, I, E> removeCommand(@NotNull String command) {
    this.commands.remove(command);
    return this;
  }

  @Override
  public @NotNull Npc<W, P, I, E> clearCommands() {
    this.commands.clear();
    return this;
  }

  @Override
  public @NotNull List<String> getCommands() {
    return this.commands;
  }

  @Override
  public @NotNull <T, O> NpcSpecificOutboundPacket<W, P, I, E> changeMetadata(
    @NotNull EntityMetadataFactory<T, O> metadata,
    @NotNull T value
  ) {
    return this.platform.packetFactory().createEntityMetaPacket(metadata, value).toSpecific(this);
  }

  @Override
  public @NotNull Hologram hologram() {
    return hologram;
  }

  @Override
  public @NotNull Npc<W, P, I, E> hologram(@NotNull Hologram hologram) {
    this.hologram = hologram;
    return this;
  }

  @Override
  public Consumer<P> onRightClick() {
    return onRightClick;
  }

  @Override
  public Consumer<P> onLeftClick() {
    return onLeftClick;
  }

  @Override
  public @NotNull Npc<W, P, I, E> onRightClick(@NotNull Consumer<P> onRightClick) {
    this.onRightClick = onRightClick;
    return this;
  }

  @Override
  public @NotNull Npc<W, P, I, E> onLeftClick(@NotNull Consumer<P> onLeftClick) {
    this.onLeftClick = onLeftClick;
    return this;
  }

  @Override
  public int hashCode() {
    return Integer.hashCode(this.entityId());
  }

  @Override
  public boolean equals(Object obj) {
    return Util.equals(Npc.class, this, obj, (orig, comp) -> orig.entityId() == comp.entityId());
  }
}
