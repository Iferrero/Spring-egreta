package com.example.demo.egreta;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SyncProgressRegistry {
    private final Map<String, SyncProgress> progressMap = new ConcurrentHashMap<>();

    public void setProgress(String key, int total, int current) {
        setProgress(key, total, current, null);
    }

    public void setProgress(String key, int total, int current, String message) {
        progressMap.put(key, new SyncProgress(total, current, false, null, message));
    }

    public void markCompleted(String key) {
        SyncProgress existing = progressMap.getOrDefault(key, new SyncProgress(0, 0, false, null, null));
        progressMap.put(key, new SyncProgress(existing.total, existing.total, true, null, null));
    }

    public void markError(String key, String message) {
        SyncProgress existing = progressMap.getOrDefault(key, new SyncProgress(0, 0, false, null, null));
        progressMap.put(key, new SyncProgress(existing.total, existing.current, false, message, null));
    }

    public SyncProgress getProgress(String key) {
        return progressMap.getOrDefault(key, new SyncProgress(0, 0, false, null, null));
    }

    public void clearProgress(String key) {
        progressMap.remove(key);
    }

    public static class SyncProgress {
        public final int total;
        public final int current;
        public final boolean completed;
        public final String error;
        public final String message;

        public SyncProgress(int total, int current, boolean completed, String error) {
            this(total, current, completed, error, null);
        }

        public SyncProgress(int total, int current, boolean completed, String error, String message) {
            this.total = total;
            this.current = current;
            this.completed = completed;
            this.error = error;
            this.message = message;
        }
    }
}
