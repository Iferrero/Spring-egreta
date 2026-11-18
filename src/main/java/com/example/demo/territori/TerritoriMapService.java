package com.example.demo.territori;

import com.example.demo.territori.TerritoriModel.Snapshot;
import com.example.demo.territori.TerritoriQueryService.Filters;
import com.example.demo.territori.TerritoriQueryService.Result;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mapa d'interaccions UAB–territori català: manté una instantània en memòria (construïda des de
 * Mongo) amb TTL i serveix les consultes filtrades de la pàgina {@code mapa-interaccions-territori.html}.
 *
 * <p>La instantània es reconstrueix en un fil propi. Mentre es reconstrueix (o si la reconstrucció
 * falla) es continua servint l'anterior; si encara no n'hi ha cap, els endpoints retornen 503.</p>
 */
@Service
public class TerritoriMapService {

    private static final Logger log = LoggerFactory.getLogger(TerritoriMapService.class);
    private static final Duration RETRY_AFTER_ERROR = Duration.ofSeconds(60);

    /** No hi ha cap instantània disponible (construint-se o ha fallat). */
    public static final class NotReadyException extends RuntimeException {
        private final String state;

        NotReadyException(String state, String message) {
            super(message);
            this.state = state;
        }

        public String state() {
            return state;
        }
    }

    private final TerritoriSnapshotBuilder builder;
    private final Duration ttl;
    private final boolean warmup;
    private final AtomicReference<Snapshot> current = new AtomicReference<>();
    private final AtomicBoolean building = new AtomicBoolean(false);
    private volatile String lastError;
    private volatile Instant lastFailure;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "territori-snapshot");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public TerritoriMapService(
            @Qualifier("mongoTemplate") MongoTemplate mongoTemplate,
            @Value("${app.territori.cache-ttl-minutes:60}") long ttlMinutes,
            @Value("${app.territori.warmup-on-startup:false}") boolean warmup) {
        this.builder = new TerritoriSnapshotBuilder(mongoTemplate, TerritoriLookups.load());
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
        this.warmup = warmup;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (warmup) triggerRebuild();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------------------------
    // Cicle de vida de la instantània
    // ------------------------------------------------------------------------------------

    /** Llança una reconstrucció asíncrona si no n'hi ha cap en curs. Retorna true si l'ha iniciada. */
    public boolean triggerRebuild() {
        if (!building.compareAndSet(false, true)) return false;
        try {
            executor.execute(() -> {
                try {
                    Snapshot s = builder.build();
                    current.set(s);
                    lastError = null;
                    lastFailure = null;
                } catch (Throwable t) {
                    log.error("[territori] Ha fallat la construcció de la instantània", t);
                    lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                    lastFailure = Instant.now();
                } finally {
                    building.set(false);
                }
            });
            return true;
        } catch (RuntimeException e) {
            building.set(false);
            throw e;
        }
    }

    Snapshot snapshot() {
        Snapshot s = current.get();
        if (s == null) {
            Instant failed = lastFailure;
            boolean mayRetry = failed == null || Duration.between(failed, Instant.now()).compareTo(RETRY_AFTER_ERROR) > 0;
            if (mayRetry) triggerRebuild();
            if (lastError != null && !building.get()) {
                throw new NotReadyException("error", "No s'han pogut carregar les dades: " + lastError);
            }
            throw new NotReadyException("building", "Les dades s'estan carregant des de MongoDB; torna-ho a provar en uns segons.");
        }
        if (Duration.between(s.builtAt(), Instant.now()).compareTo(ttl) > 0) triggerRebuild();
        return s;
    }

    public Map<String, Object> status() {
        Snapshot s = current.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", s != null ? "ready" : (building.get() ? "building" : (lastError != null ? "error" : "idle")));
        m.put("building", building.get());
        m.put("builtAt", s == null ? null : s.builtAt().toString());
        m.put("ttlMinutes", ttl.toMinutes());
        m.put("error", lastError);
        return m;
    }

    // ------------------------------------------------------------------------------------
    // Consultes
    // ------------------------------------------------------------------------------------

    public Map<String, Object> meta() {
        Snapshot s = snapshot();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("dataExtraccio", s.builtAt().atZone(ZoneOffset.UTC).toLocalDate().toString());
        meta.put("builtAt", s.builtAt().toString());
        meta.put("stats", s.stats());
        meta.put("rc0025r", s.rc0025r());
        meta.put("coordOrigins", s.coordOrigins());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("meta", meta);
        out.put("options", TerritoriQueryService.options(s));
        return out;
    }

    public Map<String, Object> summary(Map<String, String> params) {
        Snapshot s = snapshot();
        return TerritoriQueryService.summary(s, TerritoriQueryService.filter(s, Filters.from(params)));
    }

    public Map<String, Object> agents(Map<String, String> params, String sort, boolean asc, int page, boolean all) {
        Snapshot s = snapshot();
        Result r = TerritoriQueryService.filter(s, Filters.from(params));
        return TerritoriQueryService.agentsPage(s, r, sort, asc, page, all);
    }

    public Map<String, Object> convenis(Map<String, String> params, String sort, boolean asc, int page, boolean all) {
        Snapshot s = snapshot();
        Result r = TerritoriQueryService.filter(s, Filters.from(params));
        return TerritoriQueryService.convsPage(s, r, sort, asc, page, all);
    }

    public Map<String, Object> projectes(Map<String, String> params, String sort, boolean asc, int page, boolean all) {
        Snapshot s = snapshot();
        Result r = TerritoriQueryService.filter(s, Filters.from(params));
        return TerritoriQueryService.projsPage(s, r, sort, asc, page, all);
    }

    /** Retorna null si l'índex d'agent no existeix. */
    public Map<String, Object> agentDetail(int idx, Map<String, String> params) {
        Snapshot s = snapshot();
        if (idx < 0 || idx >= s.agents().size()) return null;
        return TerritoriQueryService.agentDetail(s, TerritoriQueryService.filter(s, Filters.from(params)), idx);
    }
}
