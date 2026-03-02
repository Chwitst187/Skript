package ch.njol.skript.util;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import ch.njol.skript.ScriptLoader;
import ch.njol.skript.Skript;
import ch.njol.util.Closeable;

/**
 * @author Peter Güttinger
 */
@SuppressWarnings("removal")
public abstract class Task implements Runnable, Closeable {

	private final boolean async;
	private final Plugin plugin;
	
	private boolean useScriptLoaderExecutor;
	private long period = -1;
	private int taskID = -1;
	private @Nullable Object foliaTask;

	/**
	 * Creates a new task that will run after the given delay and then repeat every period ticks.
	 * <p>
	 * @param plugin The plugin that owns this task.
	 * @param delay Delay in ticks before the task is run for the first time.
	 * @param period Period in ticks between subsequent executions of the task.
	 */
	public Task(Plugin plugin, long delay, long period) {
		this(plugin, delay, period, false);
	}

	/**
	 * Creates a new task that will run after the given delay and then repeat every period ticks optionally asyncronously.
	 * <p>
	 * @param plugin The plugin that owns this task.
	 * @param delay Delay in ticks before the task is run for the first time.
	 * @param period Period in ticks between subsequent executions of the task.
	 * @param async Whether to run the task asynchronously
	 */
	public Task(Plugin plugin, long delay, long period, boolean async) {
		this.plugin = plugin;
		this.period = period;
		this.async = async;
		schedule(delay);
	}

	/**
	 * Creates a new task that will run after the given delay.
	 * <p>
	 * @param plugin The plugin that owns this task.
	 * @param delay Delay in ticks before the task is run for the first time.
	 */
	public Task(Plugin plugin, long delay) {
		this(plugin, false, delay, false);
	}

	/**
	 * Creates a new task that will run optionally on the script loader executor.
	 * <p>
	 * @param plugin The plugin that owns this task.
	 * @param useScriptLoaderExecutor Whether to use the script loader executor. Setting is based on the config.sk user setting.
	 */
	public Task(Plugin plugin, boolean useScriptLoaderExecutor) {
		this(plugin, useScriptLoaderExecutor, 0, false);
	}

	/**
	 * Creates a new task that will run after the given delay and optionally asynchronously.
	 * <p>
	 * @param plugin The plugin that owns this task.
	 * @param delay Delay in ticks before the task is run for the first time.
	 * @param async Whether to run the task asynchronously
	 */
	public Task(Plugin plugin, long delay, boolean async) {
		this(plugin, delay, -1, async);
	}

	/**
	 * Creates a new task that will run optionally on the script loader executor and after a delay.
	 * <p>
	 * @param plugin The plugin that owns this task.
	 * @param useScriptLoaderExecutor Whether to use the script loader executor. Setting is based on the config.sk user setting.
	 * @param delay Delay in ticks before the task is run for the first time.
	 */
	public Task(Plugin plugin, boolean useScriptLoaderExecutor, long delay) {
		this(plugin, useScriptLoaderExecutor, delay, false);
	}

	// Private because async and useScriptLoaderExecutor contradict each other, as the script loader executor may be asynchronous.
	private Task(Plugin plugin, boolean useScriptLoaderExecutor, long delay, boolean async) {
		this.useScriptLoaderExecutor = useScriptLoaderExecutor;
		this.plugin = plugin;
		this.async = async;
		schedule(delay);
	}

	/**
	 * Only call this if the task is not alive.
	 *
	 * @param delay
	 */
	private void schedule(final long delay) {
		assert !isAlive();
		if (!Skript.getInstance().isEnabled())
			return;
		if (Skript.isRunningFolia()) {
			scheduleFolia(delay);
			return;
		}
		if (useScriptLoaderExecutor) {
			Executor executor = ScriptLoader.getExecutor();
			if (delay > 0) {
				taskID = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> executor.execute(this), delay);
			} else {
				executor.execute(this);
			}
		} else {
			if (period == -1) {
				if (async) {
					taskID = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this, delay).getTaskId();
				} else {
					taskID = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, this, delay);
				}
			} else {
				if (async) {
					taskID = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this, delay, period).getTaskId();
				} else {
					taskID = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, this, delay, period);
				}
			}
			assert taskID != -1;
		}
	}

	private void scheduleFolia(final long delay) {
		// Folia does not support BukkitScheduler async scheduling methods.
		if (useScriptLoaderExecutor) {
			Executor executor = ScriptLoader.getExecutor();
			if (delay > 0) {
				foliaTask = invokeFoliaScheduler("getGlobalRegionScheduler", "runDelayed", new Class<?>[] {Plugin.class, Runnable.class, long.class}, plugin, (Runnable) () -> executor.execute(this), delay);
			} else {
				executor.execute(this);
			}
			return;
		}

		if (async) {
			long delayMillis = ticksToMillis(delay);
			if (period == -1) {
				if (delay <= 0) {
					foliaTask = invokeFoliaScheduler("getAsyncScheduler", "runNow", new Class<?>[] {Plugin.class, Runnable.class}, plugin, (Runnable) this::run);
				} else {
					foliaTask = invokeFoliaScheduler("getAsyncScheduler", "runDelayed", new Class<?>[] {Plugin.class, Runnable.class, long.class, TimeUnit.class}, plugin, (Runnable) this::run, delayMillis, TimeUnit.MILLISECONDS);
				}
			} else {
				foliaTask = invokeFoliaScheduler("getAsyncScheduler", "runAtFixedRate", new Class<?>[] {Plugin.class, Runnable.class, long.class, long.class, TimeUnit.class}, plugin, (Runnable) this::run, delayMillis, ticksToMillis(period), TimeUnit.MILLISECONDS);
			}
		} else {
			if (period == -1) {
				if (delay <= 0) {
					foliaTask = invokeFoliaScheduler("getGlobalRegionScheduler", "run", new Class<?>[] {Plugin.class, Runnable.class}, plugin, (Runnable) this::run);
				} else {
					foliaTask = invokeFoliaScheduler("getGlobalRegionScheduler", "runDelayed", new Class<?>[] {Plugin.class, Runnable.class, long.class}, plugin, (Runnable) this::run, delay);
				}
			} else {
				foliaTask = invokeFoliaScheduler("getGlobalRegionScheduler", "runAtFixedRate", new Class<?>[] {Plugin.class, Runnable.class, long.class, long.class}, plugin, (Runnable) this::run, delay, period);
			}
		}
	}

	private static @Nullable Object invokeFoliaScheduler(String schedulerMethod, String scheduleMethod, Class<?>[] parameterTypes, Object... args) {
		try {
			Object scheduler = Bukkit.class.getMethod(schedulerMethod).invoke(null);
			try {
				Method method = scheduler.getClass().getMethod(scheduleMethod, parameterTypes);
				return method.invoke(scheduler, args);
			} catch (NoSuchMethodException ignored) {
				Method method = findCompatibleFoliaMethod(scheduler.getClass(), scheduleMethod, args);
				return method.invoke(scheduler, adaptFoliaArguments(method.getParameterTypes(), args));
			}
		} catch (ReflectiveOperationException e) {
			throw new UnsupportedOperationException("Unable to schedule Folia task", e);
		}
	}

	private static Method findCompatibleFoliaMethod(Class<?> schedulerClass, String methodName, Object[] args) throws NoSuchMethodException {
		for (Method method : schedulerClass.getMethods()) {
			if (!method.getName().equals(methodName))
				continue;
			Class<?>[] parameterTypes = method.getParameterTypes();
			if (parameterTypes.length != args.length)
				continue;
			if (isCompatibleFoliaMethod(parameterTypes, args))
				return method;
		}
		throw new NoSuchMethodException(schedulerClass.getName() + "#" + methodName);
	}

	private static boolean isCompatibleFoliaMethod(Class<?>[] parameterTypes, Object[] args) {
		for (int i = 0; i < parameterTypes.length; i++) {
			Object arg = args[i];
			Class<?> parameterType = parameterTypes[i];
			if (arg == null) {
				if (parameterType.isPrimitive())
					return false;
				continue;
			}
			if (arg instanceof Runnable && parameterType == Consumer.class)
				continue;
			if (!wrap(parameterType).isInstance(arg))
				return false;
		}
		return true;
	}

	private static Object[] adaptFoliaArguments(Class<?>[] parameterTypes, Object[] args) {
		Object[] adapted = args.clone();
		for (int i = 0; i < parameterTypes.length; i++) {
			if (adapted[i] instanceof Runnable runnable && parameterTypes[i] == Consumer.class)
				adapted[i] = (Consumer<Object>) ignored -> runnable.run();
		}
		return adapted;
	}

	private static Class<?> wrap(Class<?> clazz) {
		if (!clazz.isPrimitive())
			return clazz;
		if (clazz == boolean.class)
			return Boolean.class;
		if (clazz == byte.class)
			return Byte.class;
		if (clazz == char.class)
			return Character.class;
		if (clazz == double.class)
			return Double.class;
		if (clazz == float.class)
			return Float.class;
		if (clazz == int.class)
			return Integer.class;
		if (clazz == long.class)
			return Long.class;
		if (clazz == short.class)
			return Short.class;
		return Void.class;
	}

	private static long ticksToMillis(long ticks) {
		return Math.max(0L, ticks * 50L);
	}

	/**
	 * @return Whether this task is still running, i.e. whether it will run later or is currently running.
	 */
	public final boolean isAlive() {
		if (Skript.isRunningFolia()) {
			Object task = foliaTask;
			if (task == null)
				return false;
			try {
				Object state = task.getClass().getMethod("getExecutionState").invoke(task);
				String name = state.toString();
				return !"CANCELLED".equals(name) && !"FINISHED".equals(name);
			} catch (ReflectiveOperationException e) {
				return true;
			}
		}
		if (taskID == -1)
			return false;
		return Bukkit.getScheduler().isQueued(taskID) || Bukkit.getScheduler().isCurrentlyRunning(taskID);
	}

	/**
	 * Cancels this task.
	 */
	public final void cancel() {
		if (foliaTask != null) {
			try {
				foliaTask.getClass().getMethod("cancel").invoke(foliaTask);
			} catch (ReflectiveOperationException ignored) {}
			foliaTask = null;
		}
		if (taskID != -1) {
			Bukkit.getScheduler().cancelTask(taskID);
			taskID = -1;
		}
	}

	@Override
	public void close() {
		cancel();
	}

	/**
	 * Re-schedules the task to run next after the given delay. If this task was repeating it will continue so using the same period as before.
	 *
	 * @param delay
	 */
	public void setNextExecution(final long delay) {
		assert delay >= 0;
		cancel();
		schedule(delay);
	}

	/**
	 * Sets the period of this task. This will re-schedule the task to be run next after the given period if the task is still running.
	 *
	 * @param period Period in ticks or -1 to cancel the task and make it non-repeating
	 */
	public void setPeriod(final long period) {
		assert period == -1 || period > 0;
		if (period == this.period)
			return;
		this.period = period;
		if (isAlive()) {
			cancel();
			if (period != -1)
				schedule(period);
		}
	}

	/**
	 * Equivalent to <tt>{@link #callSync(Callable, Plugin) callSync}(c, {@link Skript#getInstance()})</tt>
	 */
	@Nullable
	public static <T> T callSync(final Callable<T> c) {
		return callSync(c, Skript.getInstance());
	}

	/**
	 * Calls a method on Bukkit's main thread.
	 * <p>
	 * Hint: Use a Callable&lt;Void&gt; to make a task which blocks your current thread until it is completed.
	 *
	 * @param c The method
	 * @param p The plugin that owns the task. Must be enabled.
	 * @return What the method returned or null if it threw an error or was stopped (usually due to the server shutting down)
	 */
	@Nullable
	public static <T> T callSync(final Callable<T> c, final Plugin p) {
		if (Bukkit.isPrimaryThread()) {
			try {
				return c.call();
			} catch (final Exception e) {
				Skript.exception(e);
			}
		}
		final Future<T> f = Bukkit.getScheduler().callSyncMethod(p, c);
		try {
			while (true) {
				try {
					return f.get();
				} catch (final InterruptedException e) {}
			}
		} catch (final ExecutionException e) {
			Skript.exception(e);
		} catch (final CancellationException e) {}
		return null;
	}

}
