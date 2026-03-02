package ch.njol.skript.util;

import ch.njol.skript.Skript;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SkriptScheduler {

	private static final AtomicInteger FOLIA_TASK_IDS = new AtomicInteger(Integer.MIN_VALUE);
	private static final Map<Integer, FoliaTaskRef> FOLIA_TASKS = new ConcurrentHashMap<>();

	private SkriptScheduler() {}

	public static int scheduleSyncDelayedTask(Plugin plugin, Runnable runnable) {
		return scheduleSyncDelayedTask(plugin, runnable, 0L);
	}

	public static int scheduleSyncDelayedTask(Plugin plugin, Runnable runnable, long delay) {
		if (!Skript.isRunningFolia())
			return Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, runnable, delay);
		Object task = delay <= 0
			? invokeFoliaScheduler("getGlobalRegionScheduler", "run", plugin, runnable)
			: invokeFoliaScheduler("getGlobalRegionScheduler", "runDelayed", plugin, runnable, delay);
		return registerFoliaTask(plugin, task);
	}

	public static int scheduleSyncRepeatingTask(Plugin plugin, Runnable runnable, long delay, long period) {
		if (!Skript.isRunningFolia())
			return Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, runnable, delay, period);
		Object task = invokeFoliaScheduler("getGlobalRegionScheduler", "runAtFixedRate", plugin, runnable, delay, period);
		return registerFoliaTask(plugin, task);
	}

	public static void runTask(Plugin plugin, Runnable runnable) {
		scheduleSyncDelayedTask(plugin, runnable);
	}

	public static void runTaskLater(Plugin plugin, Runnable runnable, long delay) {
		scheduleSyncDelayedTask(plugin, runnable, delay);
	}

	public static int runTaskLaterAsynchronously(Plugin plugin, Runnable runnable, long delay) {
		if (!Skript.isRunningFolia())
			return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, runnable, delay).getTaskId();
		Object task;
		if (delay <= 0) {
			task = invokeFoliaScheduler("getAsyncScheduler", "runNow", plugin, runnable);
		} else {
			task = invokeFoliaScheduler("getAsyncScheduler", "runDelayed", plugin, runnable, ticksToMillis(delay), TimeUnit.MILLISECONDS);
		}
		return registerFoliaTask(plugin, task);
	}

	public static int runTaskTimerAsynchronously(Plugin plugin, Runnable runnable, long delay, long period) {
		if (!Skript.isRunningFolia())
			return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delay, period).getTaskId();
		Object task = invokeFoliaScheduler("getAsyncScheduler", "runAtFixedRate", plugin, runnable, ticksToMillis(delay), ticksToMillis(period), TimeUnit.MILLISECONDS);
		return registerFoliaTask(plugin, task);
	}

	public static void runTaskAsynchronously(Plugin plugin, Runnable runnable) {
		runTaskLaterAsynchronously(plugin, runnable, 0L);
	}

	public static void cancelTask(int taskId) {
		FoliaTaskRef foliaTask = FOLIA_TASKS.remove(taskId);
		if (foliaTask != null) {
			cancelFoliaTask(foliaTask.task());
			return;
		}
		Bukkit.getScheduler().cancelTask(taskId);
	}

	public static void cancelTasks(Plugin plugin) {
		if (!Skript.isRunningFolia()) {
			Bukkit.getScheduler().cancelTasks(plugin);
			return;
		}
		FOLIA_TASKS.entrySet().removeIf(entry -> {
			if (!entry.getValue().plugin().equals(plugin))
				return false;
			cancelFoliaTask(entry.getValue().task());
			return true;
		});
	}

	public static <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> callable) {
		if (!Skript.isRunningFolia())
			return Bukkit.getScheduler().callSyncMethod(plugin, callable);
		if (Bukkit.isPrimaryThread()) {
			CompletableFuture<T> future = new CompletableFuture<>();
			try {
				future.complete(callable.call());
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
			return future;
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		runTask(plugin, () -> {
			try {
				future.complete(callable.call());
			} catch (Exception e) {
				future.completeExceptionally(e);
			}
		});
		return future;
	}

	private static int registerFoliaTask(Plugin plugin, @Nullable Object task) {
		if (task == null)
			throw new UnsupportedOperationException("Unable to schedule Folia task");
		int taskId = FOLIA_TASK_IDS.getAndIncrement();
		FOLIA_TASKS.put(taskId, new FoliaTaskRef(plugin, task));
		return taskId;
	}

	private static void cancelFoliaTask(Object task) {
		try {
			task.getClass().getMethod("cancel").invoke(task);
		} catch (ReflectiveOperationException ignored) {}
	}

	private static @Nullable Object invokeFoliaScheduler(String schedulerMethod, String scheduleMethod, Object... args) {
		try {
			Object scheduler = Bukkit.class.getMethod(schedulerMethod).invoke(null);
			Method method = findCompatibleFoliaMethod(scheduler.getClass(), scheduleMethod, args);
			return method.invoke(scheduler, adaptFoliaArguments(method.getParameterTypes(), args));
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
			if (arg instanceof Runnable && java.util.function.Consumer.class == parameterType)
				continue;
			if (!wrap(parameterType).isInstance(arg))
				return false;
		}
		return true;
	}

	private static Object[] adaptFoliaArguments(Class<?>[] parameterTypes, Object[] args) {
		Object[] adapted = args.clone();
		for (int i = 0; i < parameterTypes.length; i++) {
			if (adapted[i] instanceof Runnable runnable && java.util.function.Consumer.class == parameterTypes[i])
				adapted[i] = (java.util.function.Consumer<Object>) ignored -> runnable.run();
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

	private record FoliaTaskRef(Plugin plugin, Object task) {}
}
