package com.example.demo.egreta;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SyncProgressRegistry {
    private final Map<String, SyncProgress> progressMap = new ConcurrentHashMap<>();

    public void setProgress(String key, int total, int current) {
        progressMap.put(key, new SyncProgress(total, current, false, null));
    }

    public void markCompleted(String key) {
        SyncProgress existing = progressMap.getOrDefault(key, new SyncProgress(0, 0, false, null));
        progressMap.put(key, new SyncProgress(existing.total, existing.total, true, null));
    }

    public void markError(String key, String message) {
        SyncProgress existing = progressMap.getOrDefault(key, new SyncProgress(0, 0, false, null));
        progressMap.put(key, new SyncProgress(existing.total, existing.current, false, message));
    }

    public SyncProgress getProgress(String key) {
        return progressMap.getOrDefault(key, new SyncProgress(0, 0, false, null));
    }

    public void clearProgress(String key) {
        progressMap.remove(key);
    }

    public static class SyncProgress {
        public final int total;
        public final int current;
        public final boolean completed;
        public final String error;

        public SyncProgress(int total, int current, boolean completed, String error) {
            this.total = total;
            this.current = current;
            this.completed = completed;
            this.error = error;
        }
    }
}