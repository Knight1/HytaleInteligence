package de.tobiassachs;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static de.tobiassachs.CommandUtils.*;

public class LagCommand extends AbstractCommand {

    public LagCommand(String name, String description) {
        super(name, description);
        requirePermission("hytale.intelligence.info");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {

        // ---- World Status ----
        context.sendMessage(Message.raw("---- World Status ----"));
        try {
            Universe universe = Universe.get();
            Map<String, World> worlds = universe.getWorlds();

            for (Map.Entry<String, World> entry : worlds.entrySet()) {
                World world = entry.getValue();
                context.sendMessage(Message.raw("World: " + entry.getKey()));
                context.sendMessage(Message.raw("  Tick:    " + world.getTick()));
                context.sendMessage(Message.raw("  Alive:   " + world.isAlive()));
                context.sendMessage(Message.raw("  Paused:  " + world.isPaused()));
                context.sendMessage(Message.raw("  Ticking: " + world.isTicking()));
                context.sendMessage(Message.raw("  Players: " + world.getPlayerCount()));
            }
        } catch (Exception e) {
            context.sendMessage(Message.raw("  Failed to read world status: " + e.getMessage()));
        }

        // ---- CPU Usage ----
        context.sendMessage(Message.raw("---- CPU ----"));
        try {
            OperatingSystemMXBean osMx = ManagementFactory.getOperatingSystemMXBean();
            double loadAvg = osMx.getSystemLoadAverage();
            int processors = osMx.getAvailableProcessors();

            context.sendMessage(Message.raw("Available Processors: " + processors));
            context.sendMessage(Message.raw("System Load Average:  " + (loadAvg >= 0 ? String.format("%.2f", loadAvg) : "Unavailable")));
            context.sendMessage(Message.raw("Load per Core:        " + (loadAvg >= 0 ? String.format("%.2f", loadAvg / processors) : "Unavailable")));

            // com.sun extensions for more detailed CPU info
            if (osMx instanceof com.sun.management.OperatingSystemMXBean) {
                com.sun.management.OperatingSystemMXBean sunOs = (com.sun.management.OperatingSystemMXBean) osMx;
                context.sendMessage(Message.raw("Process CPU Load:     " + String.format("%.1f%%", sunOs.getProcessCpuLoad() * 100)));
                context.sendMessage(Message.raw("System CPU Load:      " + String.format("%.1f%%", sunOs.getCpuLoad() * 100)));
                context.sendMessage(Message.raw("Process CPU Time:     " + formatNanos(sunOs.getProcessCpuTime())));
            }
        } catch (Exception e) {
            context.sendMessage(Message.raw("  CPU info error: " + e.getMessage()));
        }

        // ---- Load Average ----
        context.sendMessage(Message.raw("---- Load Average (/proc/loadavg) ----"));
        String loadavg = readFileSafe("/proc/loadavg");
        context.sendMessage(Message.raw("  " + loadavg));

        // ---- IO Wait & CPU Stats (/proc/stat) ----
        context.sendMessage(Message.raw("---- CPU Stats (/proc/stat) ----"));
        String procStat = readFileSafe("/proc/stat");
        if (!procStat.equals("Unavailable")) {
            for (String line : procStat.split("\n")) {
                if (line.startsWith("cpu")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 8) {
                        context.sendMessage(Message.raw("  " + parts[0] + ":"));
                        context.sendMessage(Message.raw("    user=" + parts[1]
                                + " nice=" + parts[2]
                                + " system=" + parts[3]
                                + " idle=" + parts[4]));
                        context.sendMessage(Message.raw("    iowait=" + parts[5]
                                + " irq=" + parts[6]
                                + " softirq=" + parts[7]));
                        if (parts.length >= 9) {
                            context.sendMessage(Message.raw("    steal=" + parts[8]));
                        }
                    }
                }
                if (line.startsWith("procs_running") || line.startsWith("procs_blocked")) {
                    context.sendMessage(Message.raw("  " + line));
                }
                if (line.startsWith("ctxt") || line.startsWith("processes")) {
                    context.sendMessage(Message.raw("  " + line));
                }
            }
        } else {
            context.sendMessage(Message.raw("  Unavailable"));
        }

        // ---- JVM Threads ----
        context.sendMessage(Message.raw("---- JVM Threads ----"));
        try {
            ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
            context.sendMessage(Message.raw("Thread Count:      " + threadMx.getThreadCount()));
            context.sendMessage(Message.raw("Peak Thread Count: " + threadMx.getPeakThreadCount()));
            context.sendMessage(Message.raw("Daemon Threads:    " + threadMx.getDaemonThreadCount()));
            context.sendMessage(Message.raw("Total Started:     " + threadMx.getTotalStartedThreadCount()));

            long[] deadlocked = threadMx.findDeadlockedThreads();
            if (deadlocked != null && deadlocked.length > 0) {
                context.sendMessage(Message.raw("DEADLOCKED THREADS: " + deadlocked.length));
            } else {
                context.sendMessage(Message.raw("Deadlocked Threads: None"));
            }
        } catch (Exception e) {
            context.sendMessage(Message.raw("  Thread info error: " + e.getMessage()));
        }

        // ---- GC Pressure ----
        context.sendMessage(Message.raw("---- GC Pressure ----"));
        try {
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                context.sendMessage(Message.raw("  " + gc.getName()
                        + " → collections: " + gc.getCollectionCount()
                        + ", time: " + gc.getCollectionTime() + "ms"));
            }
        } catch (Exception e) {
            context.sendMessage(Message.raw("  GC info: Unavailable"));
        }

        // ---- Uptime ----
        context.sendMessage(Message.raw("---- Uptime ----"));
        try {
            long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
            context.sendMessage(Message.raw("JVM Uptime: " + formatUptime(uptime)));
        } catch (Exception e) {
            context.sendMessage(Message.raw("  Uptime: Unavailable"));
        }

        // ---- System Uptime ----
        context.sendMessage(Message.raw("System Uptime: " + readFileSafe("/proc/uptime")));

        return CompletableFuture.completedFuture(null);
    }

    private String formatUptime(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return days + "d " + hours + "h " + minutes + "m " + secs + "s";
    }

    private String formatNanos(long nanos) {
        if (nanos < 0) return "Unavailable";
        long ms = nanos / 1_000_000;
        return formatUptime(ms);
    }
}
