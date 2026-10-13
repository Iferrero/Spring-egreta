package com.example.demo.curation.reconciler;

import com.example.demo.curation.model.ReconciliationProgress;
import com.example.demo.curation.model.ReconciliationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encapsulated concurrency and state registry for external organization batch reconciliation jobs.
 * Replaces unmanaged controller fields, manual threads, and locks with a cohesive, thread-safe module.
 */
@Component
public class ReconciliationJobRegistry {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJobRegistry.class);
    private static final int MAX_IN_MEMORY_LOGS = 200;

    public static class JobState {
        private final ReconciliationType type;
        private final Object lock = new Object();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicInteger total = new AtomicInteger(0);
        private final AtomicInteger processed = new AtomicInteger(0);
        private final AtomicInteger applied = new AtomicInteger(0);
        private volatile double threshold = 0.90;
        private final List<String> logs = new Vector<>();
        private final File logFile;

        public JobState(ReconciliationType type) {
            this.type = type;
            this.logFile = new File(type.getLogFilePath());
        }

        public Object getLock() {
            return lock;
        }

        public boolean isRunning() {
            return running.get();
        }

        public File getLogFile() {
            return logFile;
        }
    }

    private final Map<ReconciliationType, JobState> states = new EnumMap<>(ReconciliationType.class);

    public ReconciliationJobRegistry() {
        for (ReconciliationType type : ReconciliationType.values()) {
            states.put(type, new JobState(type));
        }
    }

    public Object getLock(ReconciliationType type) {
        return getState(type).getLock();
    }

    public boolean isRunning(ReconciliationType type) {
        return getState(type).isRunning();
    }

    public File getLogFile(ReconciliationType type) {
        return getState(type).getLogFile();
    }

    /**
     * Attempts to start a reconciliation run.
     * Returns true if successfully started, false if already running.
     */
    public boolean startRun(ReconciliationType type, String env, double threshold, String headerTitle) {
        JobState s = getState(type);
        synchronized (s.lock) {
            if (s.running.get()) {
                return false;
            }
            s.running.set(true);
            s.total.set(0);
            s.processed.set(0);
            s.applied.set(0);
            s.threshold = threshold;
            s.logs.clear();

            String envTag = (env == null || env.isBlank()) ? "DEFAULT" : env.toUpperCase();
            String thresholdPct = Math.round(threshold * 100) + "%";
            String header = "=== INICI DE " + headerTitle + " (" + envTag + ", Llindar: " + thresholdPct + ") === "
                    + LocalDateTime.now() + System.lineSeparator();

            writeToLogFile(s, header, false);
            appendLogLine(s, header.trim());
            return true;
        }
    }

    /**
     * Requests stopping an active reconciliation run.
     * Returns true if stop requested, false if was not running.
     */
    public boolean requestStop(ReconciliationType type) {
        JobState s = getState(type);
        synchronized (s.lock) {
            if (!s.running.get()) {
                return false;
            }
            s.running.set(false);
            appendLogLine(s, "Petició d'aturar el procés rebuda.");
            return true;
        }
    }

    public void setTotal(ReconciliationType type, int total) {
        JobState s = getState(type);
        s.total.set(total);
    }

    public void incrementProcessed(ReconciliationType type) {
        JobState s = getState(type);
        s.processed.incrementAndGet();
    }

    public void incrementApplied(ReconciliationType type) {
        JobState s = getState(type);
        s.applied.incrementAndGet();
    }

    public void logMessage(ReconciliationType type, String message) {
        JobState s = getState(type);
        appendLogLine(s, message);
    }

    public void finishRun(ReconciliationType type, String summaryMessage) {
        JobState s = getState(type);
        synchronized (s.lock) {
            appendLogLine(s, summaryMessage);
            s.running.set(false);
        }
    }

    public void failRun(ReconciliationType type, String errorMessage) {
        JobState s = getState(type);
        synchronized (s.lock) {
            appendLogLine(s, "[FATAL ERROR] " + errorMessage);
            s.running.set(false);
        }
    }

    public ReconciliationProgress getProgress(ReconciliationType type) {
        JobState s = getState(type);
        synchronized (s.lock) {
            int tot = s.total.get();
            int proc = s.processed.get();
            double pct = tot > 0 ? (double) proc / tot * 100.0 : 0.0;
            return new ReconciliationProgress(
                    type,
                    s.running.get(),
                    tot,
                    proc,
                    s.applied.get(),
                    pct,
                    s.threshold,
                    new ArrayList<>(s.logs)
            );
        }
    }

    private JobState getState(ReconciliationType type) {
        JobState s = states.get(type);
        if (s == null) {
            throw new IllegalArgumentException("Unknown reconciliation type: " + type);
        }
        return s;
    }

    private void appendLogLine(JobState s, String message) {
        log.info("[{}] {}", s.type.getKey(), message);
        s.logs.add(message);
        if (s.logs.size() > MAX_IN_MEMORY_LOGS) {
            s.logs.remove(0);
        }
        writeToLogFile(s, message + System.lineSeparator(), true);
    }

    private void writeToLogFile(JobState s, String text, boolean append) {
        try {
            File f = s.logFile;
            if (f.getParentFile() != null && !f.getParentFile().exists()) {
                f.getParentFile().mkdirs();
            }
            try (FileWriter fw = new FileWriter(f, append);
                 PrintWriter pw = new PrintWriter(fw)) {
                pw.print(text);
            }
        } catch (Exception e) {
            log.warn("Error writing to log file '{}': {}", s.logFile.getPath(), e.getMessage());
        }
    }
}
