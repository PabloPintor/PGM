package tc.oc.pgm.start;

import static net.kyori.adventure.bossbar.BossBar.bossBar;
import static net.kyori.adventure.text.Component.empty;
import static net.kyori.adventure.text.Component.space;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import tc.oc.pgm.api.Config;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchModule;
import tc.oc.pgm.api.match.MatchPhase;
import tc.oc.pgm.api.match.MatchScope;
import tc.oc.pgm.api.match.factory.MatchModuleFactory;
import tc.oc.pgm.api.module.exception.ModuleLoadException;
import tc.oc.pgm.countdowns.SingleCountdownContext;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.events.PlayerJoinMatchEvent;
import tc.oc.pgm.events.PlayerLeaveMatchEvent;

@ListenerScope(MatchScope.LOADED)
public class StartMatchModule implements MatchModule, Listener {

  public static class Factory implements MatchModuleFactory<StartMatchModule> {

    @Override
    public StartMatchModule createMatchModule(Match match) throws ModuleLoadException {
      return new StartMatchModule(match);
    }
  }

  protected final Match match;
  protected final BossBar unreadyBar;
  protected final Set<UnreadyReason> unreadyReasons = new HashSet<>();
  protected boolean autoStart; // Initialized from config, but is mutable
  protected ScheduledFuture<?> barTask;

  private StartMatchModule(Match match) {
    this.match = match;
    this.unreadyBar = bossBar(space(), 1, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    this.autoStart = !PGM.get().getConfiguration().getStartTime().isNegative();
  }

  @Override
  public void load() {
    update();
    // Schedule an update to prevent the unready bar deciding it wants to hide itself
    // This is probably because of async magic
    barTask = match.getExecutor(MatchScope.LOADED).schedule(this::update, 1, TimeUnit.SECONDS);
  }

  @Override
  public void unload() {
    if (barTask != null) {
      barTask.cancel(true);
      barTask = null;
    }
    match.hideBossBar(unreadyBar);
    unreadyReasons.clear();
  }

  @Override
  public void enable() {
    // When the match starts, unload the unready bar
    this.unload();
  }

  @EventHandler
  public void onJoin(PlayerJoinMatchEvent event) {
    if (!match.isRunning()) {
      event.getPlayer().showBossBar(unreadyBar);
    }
  }

  @EventHandler
  public void onLeave(PlayerLeaveMatchEvent event) {
    event.getPlayer().hideBossBar(unreadyBar);
  }

  // FIXME: Unsafe cast to SingleCountdownContext
  private SingleCountdownContext cc() {
    return (SingleCountdownContext) match.getCountdown();
  }

  /** If true, the match start countdown will automatically start when conditions allow it */
  public boolean isAutoStart() {
    return autoStart;
  }

  /** Enable/disable auto-start and return true if the setting was changed */
  public boolean setAutoStart(boolean autoStart) {
    if (this.autoStart != autoStart) {
      this.autoStart = autoStart;
      return true;
    } else {
      return false;
    }
  }

  public Component formatUnreadyReason() {
    if (unreadyReasons.isEmpty()) {
      return empty();
    } else {
      return unreadyReasons.iterator().next().getReason().color(NamedTextColor.RED);
    }
  }

  /**
   * Get all {@link UnreadyReason}s currently preventing the match from starting. If forceStart is
   * true, only return the reasons that prevent the match from being force started.
   */
  public Collection<UnreadyReason> getUnreadyReasons(boolean forceStart) {
    if (forceStart) {
      List<UnreadyReason> reasons = new ArrayList<>(unreadyReasons.size());
      for (UnreadyReason reason : unreadyReasons) {
        if (!reason.canForceStart()) reasons.add(reason);
      }
      return reasons;
    } else {
      return unreadyReasons;
    }
  }

  /** Add the given unready reason, replacing any existing reasons of the same type */
  public void addUnreadyReason(UnreadyReason newReason) {
    addUnreadyReason(newReason.getClass(), newReason);
  }

  /** Atomically replace all unready reasons of the given type with one other reason */
  public void addUnreadyReason(
      Class<? extends UnreadyReason> oldReasonType, UnreadyReason newReason) {
    boolean removed = removeUnreadyReasonsNoUpdate(oldReasonType);
    boolean added = addUnreadyReasonNoUpdate(newReason);
    if (removed || added) update();
  }

  /** Withdraw all unready reasons of the given type */
  public void removeUnreadyReason(Class<? extends UnreadyReason> reasonType) {
    if (removeUnreadyReasonsNoUpdate(reasonType)) {
      update();
    }
  }

  private boolean addUnreadyReasonNoUpdate(UnreadyReason reason) {
    if (unreadyReasons.add(reason)) {
      match.getLogger().fine("Added " + reason);
      return true;
    }
    return false;
  }

  private boolean removeUnreadyReasonsNoUpdate(Class<? extends UnreadyReason> reasonType) {
    boolean removed = false;
    for (Iterator<UnreadyReason> iterator = unreadyReasons.iterator(); iterator.hasNext(); ) {
      UnreadyReason reason = iterator.next();
      if (reasonType.isInstance(reason)) {
        iterator.remove();
        removed = true;
        match.getLogger().fine("Removed " + reason);
      }
    }
    return removed;
  }

  public boolean canStart(boolean force) {
    if (!match.getPhase().canTransitionTo(MatchPhase.STARTING)) return false;
    for (UnreadyReason reason : unreadyReasons) {
      if (!force || !reason.canForceStart()) return false;
    }
    return true;
  }

  /** Force the countdown to start with default duration if at all possible */
  public boolean forceStartCountdown() {
    return forceStartCountdown(null, null);
  }

  /** Force the countdown to start with the given duration if at all possible */
  public boolean forceStartCountdown(@Nullable Duration duration, @Nullable Duration huddle) {
    return canStart(true) && startCountdown(duration, huddle, true);
  }

  /**
   * Start the countdown with default duration if auto-start is enabled, and there are no soft
   * {@link UnreadyReason}s preventing it.
   */
  public boolean autoStartCountdown() {
    return isAutoStart() && canStart(false) && startCountdown(null, null, false);
  }

  private boolean startCountdown(
      @Nullable Duration duration, @Nullable Duration huddle, boolean force) {
    final Config config = PGM.get().getConfiguration();
    if (duration == null) duration = config.getStartTime();
    // In case the start config is set to -1 used to disable autostart
    if (duration.isNegative()) duration = Duration.ofSeconds(30);
    if (huddle == null) huddle = config.getHuddleTime();

    match.getLogger().fine("STARTING countdown");
    cc().start(new StartCountdown(match, force, huddle), duration);

    return true;
  }

  private boolean cancelCountdown() {
    if (cc().cancelAll(StartCountdown.class)) {
      match.getLogger().fine("Cancelled countdown");
      return true;
    }
    return false;
  }

  public void update() {
    final StartCountdown countdown = cc().getCountdown(StartCountdown.class);
    final boolean ready = canStart(countdown != null && countdown.isForced());
    final boolean empty = match.getPlayers().isEmpty();
    if (countdown == null && ready && isAutoStart()) {
      startCountdown(null, null, false);
    } else if (countdown != null && !ready) {
      cancelCountdown();
    }

    unreadyBar.name(formatUnreadyReason());

    if (!ready && isAutoStart() && !empty) {
      match.hideBossBar(unreadyBar);
      match.showBossBar(unreadyBar);
    } else if (!empty) {
      match.hideBossBar(unreadyBar);
    }
  }
}
