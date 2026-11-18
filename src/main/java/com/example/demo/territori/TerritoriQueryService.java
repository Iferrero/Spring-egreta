package com.example.demo.territori;

import com.example.demo.territori.TerritoriModel.Agent;
import com.example.demo.territori.TerritoriModel.ConvAward;
import com.example.demo.territori.TerritoriModel.ConvRel;
import com.example.demo.territori.TerritoriModel.ProjAward;
import com.example.demo.territori.TerritoriModel.ProjRel;
import com.example.demo.territori.TerritoriModel.Snapshot;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Consultes filtrades sobre la instantània: aplica els mateixos filtres i agregacions que feia
 * abans el navegador (deduplicació d'imports per award_uuid, recompte únic d'interaccions, etc.).
 */
final class TerritoriQueryService {

    static final int PAGE_SIZE = 50;
    private static final String SOCI = "Soci / Col·laborador de consorci";
    private static final String FIN = "Entitat finançadora / Promotora";
    private static final String FIN_SOCI = "Finançador i Soci de consorci";
    private static final String PP = "Publico-Públic";
    private static final String PRIV = "Publico-Privat";
    private static final String MIXT = "Publico-Mixt / Tercer Sector";

    private TerritoriQueryService() {}

    // ------------------------------------------------------------------------------------
    // Filtres
    // ------------------------------------------------------------------------------------

    record Filters(Integer yearFrom, Integer yearTo, String prov, String municipi, String interaction,
                   String tipologia, String ambit, String unitat, String q, String convTipus, String convNat,
                   String projRol, String projLider, String projTipus) {

        static Filters from(Map<String, String> p) {
            return new Filters(
                    intOrNull(p.get("yearFrom")), intOrNull(p.get("yearTo")),
                    all(p.get("prov")), blankToNull(p.get("municipi")), all(p.get("interaction")),
                    all(p.get("tipologia")), all(p.get("ambit")), all(p.get("unitat")),
                    blankToNull(p.get("q") == null ? null : p.get("q").strip().toLowerCase(Locale.ROOT)),
                    all(p.get("convTipus")), all(p.get("convNat")),
                    all(p.get("projRol")), all(p.get("projLider")), all(p.get("projTipus")));
        }

        private static Integer intOrNull(String s) {
            if (s == null || s.isBlank()) return null;
            try {
                return Integer.valueOf(s.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        private static String blankToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }

        private static String all(String s) {
            return s == null || s.isBlank() || s.equals("ALL") ? null : s;
        }

        boolean hasYearFilter() {
            return yearFrom != null || yearTo != null;
        }
    }

    // ------------------------------------------------------------------------------------
    // Resultat filtrat
    // ------------------------------------------------------------------------------------

    static final class AgentAgg {
        int nConv, nProjSoci, nProjFin;
        double impUab;
        final Set<String> convSeen = new HashSet<>();
        final Set<String> projSeen = new HashSet<>();

        int nProj() {
            return projSeen.size();
        }

        int totInt() {
            return convSeen.size() + projSeen.size();
        }
    }

    static final class Result {
        final List<ConvRel> convs = new ArrayList<>();
        final List<ProjRel> projs = new ArrayList<>();
        /** Ordre d'inserció = ordre de primera aparició (conveni abans que projecte), com al navegador. */
        final Map<Integer, AgentAgg> aggs = new LinkedHashMap<>();

        AgentAgg agg(int idx) {
            return aggs.computeIfAbsent(idx, k -> new AgentAgg());
        }
    }

    private static boolean contains(String q, String text) {
        return text != null && !text.isEmpty() && text.toLowerCase(Locale.ROOT).contains(q);
    }

    private static boolean matchesSearch(String q, Agent ag, String... texts) {
        if (q == null) return true;
        for (String t : texts) if (contains(q, t)) return true;
        return contains(q, ag.nom()) || contains(q, ag.municipi()) || contains(q, ag.cp()) || contains(q, ag.nif());
    }

    private static boolean agentOk(Agent ag, Filters f) {
        if (f.prov() != null && !ag.provincia().equals(f.prov())) return false;
        if (f.municipi() != null && !ag.municipiKey().equals(f.municipi())) return false;
        if (f.interaction() != null && !ag.tipusInteraccio().equals(f.interaction())) return false;
        if (f.tipologia() != null && !ag.tipologia().equals(f.tipologia())) return false;
        return true;
    }

    private static boolean yearOk(Integer yr, Filters f) {
        if (!f.hasYearFilter()) return true;
        if (yr == null || yr == 0) return false;
        if (f.yearFrom() != null && yr < f.yearFrom()) return false;
        if (f.yearTo() != null && yr > f.yearTo()) return false;
        return true;
    }

    static Result filter(Snapshot s, Filters f) {
        Result res = new Result();
        boolean[] ok = new boolean[s.agents().size()];
        for (int i = 0; i < ok.length; i++) ok[i] = agentOk(s.agents().get(i), f);

        for (ConvRel r : s.convs()) {
            if (!ok[r.agent()]) continue;
            ConvAward a = r.aw();
            if (!yearOk(a.any(), f)) continue;
            if (f.ambit() != null && !a.ambit().equals(f.ambit())) continue;
            if (f.unitat() != null && !a.unitat().equals(f.unitat())) continue;
            if (f.convTipus() != null && !a.tipus().equals(f.convTipus())) continue;
            if (f.convNat() != null && !a.naturalesa().equals(f.convNat())) continue;
            Agent ag = s.agents().get(r.agent());
            if (!matchesSearch(f.q(), ag, a.titol(), a.desc(), a.unitat(), a.ip(), a.ref(), a.naturalesa())) continue;
            res.convs.add(r);
            AgentAgg g = res.agg(r.agent());
            g.nConv++;
            if (g.convSeen.add(a.awardUuid())) g.impUab += a.impUab();
        }

        for (ProjRel r : s.projs()) {
            if (!ok[r.agent()]) continue;
            ProjAward a = r.aw();
            if (!yearOk(a.any(), f)) continue;
            if (f.ambit() != null && !a.ambit().equals(f.ambit())) continue;
            if (f.unitat() != null && !a.unitat().equals(f.unitat())) continue;
            if (f.projRol() != null && !r.rol().equals(f.projRol())) continue;
            if (f.projLider() != null && !a.uabLider().equals(f.projLider())) continue;
            if (f.projTipus() != null && !a.tipus().equals(f.projTipus())) continue;
            Agent ag = s.agents().get(r.agent());
            if (!matchesSearch(f.q(), ag, a.titol(), a.desc(), a.unitat(), a.ip(), a.ref(), a.convocatoria(), r.altresSocis())) continue;
            res.projs.add(r);
            AgentAgg g = res.agg(r.agent());
            if (r.rol().equals(SOCI) || r.rol().equals(FIN_SOCI)) g.nProjSoci++;
            if (r.rol().equals(FIN) || r.rol().equals(FIN_SOCI)) g.nProjFin++;
            if (g.projSeen.add(a.awardUuid())) g.impUab += a.impUab();
        }
        return res;
    }

    // ------------------------------------------------------------------------------------
    // Opcions dels desplegables (sobre les dades sense filtrar)
    // ------------------------------------------------------------------------------------

    private static List<List<Object>> byCount(Map<String, Integer> counts) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(counts.entrySet());
        es.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        List<List<Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : es) out.add(List.of(e.getKey(), e.getValue()));
        return out;
    }

    static Map<String, Object> options(Snapshot s) {
        Set<Integer> years = new TreeSet<>(Comparator.reverseOrder());
        Map<String, Integer> tip = new LinkedHashMap<>();
        Map<String, Integer> units = new LinkedHashMap<>();
        Map<String, Integer> nat = new LinkedHashMap<>();
        Map<String, Integer> projTipus = new LinkedHashMap<>();
        Set<String> leaders = new TreeSet<>();
        for (Agent a : s.agents()) tip.merge(a.tipologia(), 1, Integer::sum);
        for (ConvRel r : s.convs()) {
            ConvAward a = r.aw();
            if (a.any() != null && a.any() != 0) years.add(a.any());
            if (!a.unitat().isEmpty()) units.merge(a.unitat(), 1, Integer::sum);
            if (!a.naturalesa().isEmpty()) nat.merge(a.naturalesa(), 1, Integer::sum);
        }
        for (ProjRel r : s.projs()) {
            ProjAward a = r.aw();
            if (a.any() != null && a.any() != 0) years.add(a.any());
            if (!a.unitat().isEmpty()) units.merge(a.unitat(), 1, Integer::sum);
            if (!a.uabLider().isEmpty()) leaders.add(a.uabLider());
            if (!a.tipus().isEmpty()) projTipus.merge(a.tipus(), 1, Integer::sum);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("years", new ArrayList<>(years));
        out.put("tipologies", byCount(tip));
        out.put("units", byCount(units));
        out.put("natures", byCount(nat));
        out.put("leaders", new ArrayList<>(leaders));
        out.put("projTipus", byCount(projTipus));
        return out;
    }

    // ------------------------------------------------------------------------------------
    // Resum: KPIs, mapa, rànquing i gràfics
    // ------------------------------------------------------------------------------------

    static Map<String, Object> summary(Snapshot s, Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Agent> agents = s.agents();

        // KPIs d'agents
        int pub = 0, priv = 0, mix = 0;
        for (Integer idx : r.aggs.keySet()) {
            String t = agents.get(idx).tipusInteraccio();
            if (t.equals(PP)) pub++;
            else if (t.equals(PRIV)) priv++;
            else mix++;
        }
        Map<String, double[]> convAw = new LinkedHashMap<>();
        for (ConvRel c : r.convs) convAw.putIfAbsent(c.aw().awardUuid(), new double[] {c.aw().impTot(), c.aw().impUab()});
        double convTot = 0, convUab = 0;
        for (double[] v : convAw.values()) {
            convTot += v[0];
            convUab += v[1];
        }
        Map<String, double[]> projAw = new LinkedHashMap<>();
        int soc = 0, fin = 0;
        for (ProjRel p : r.projs) {
            projAw.putIfAbsent(p.aw().awardUuid(), new double[] {p.aw().impTot(), p.aw().impUab()});
            if (p.rol().equals(SOCI)) soc++;
            else if (p.rol().equals(FIN)) fin++;
            else {
                soc++;
                fin++;
            }
        }
        double projTot = 0, projUab = 0;
        for (double[] v : projAw.values()) {
            projTot += v[0];
            projUab += v[1];
        }
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("agents", r.aggs.size());
        kpis.put("agentsPub", pub);
        kpis.put("agentsPriv", priv);
        kpis.put("agentsMixt", mix);
        kpis.put("convUnics", convAw.size());
        kpis.put("convRels", r.convs.size());
        kpis.put("convImpUab", convUab);
        kpis.put("convImpTot", convTot);
        kpis.put("projUnics", projAw.size());
        kpis.put("projRels", r.projs.size());
        kpis.put("projSoci", soc);
        kpis.put("projFin", fin);
        kpis.put("projImpUab", projUab);
        kpis.put("projImpTot", projTot);
        out.put("kpis", kpis);

        // Mapa per municipi
        record Muni(String key, String prov, double[] sums, int[] counts, List<int[]> top) {}
        Map<String, Muni> muniMap = new LinkedHashMap<>();
        // sums: lat, lon ; counts: count, pubInt, privInt, mixInt, conv, proj, tot
        for (Map.Entry<Integer, AgentAgg> e : r.aggs.entrySet()) {
            Agent ag = agents.get(e.getKey());
            AgentAgg g = e.getValue();
            String key = ag.municipi().isEmpty() ? (ag.provincia().isEmpty() ? "Catalunya" : ag.provincia()) : ag.municipi();
            Muni m = muniMap.computeIfAbsent(key, k -> new Muni(k, ag.provincia(), new double[2], new int[7], new ArrayList<>()));
            m.sums()[0] += ag.lat();
            m.sums()[1] += ag.lon();
            m.counts()[0]++;
            m.counts()[4] += g.nConv;
            m.counts()[5] += g.nProj();
            m.counts()[6] += g.totInt();
            int slot = ag.tipusInteraccio().equals(PP) ? 1 : (ag.tipusInteraccio().equals(PRIV) ? 2 : 3);
            m.counts()[slot] += g.totInt();
            m.top().add(new int[] {e.getKey(), g.totInt()});
        }
        List<Muni> munis = new ArrayList<>(muniMap.values());
        munis.sort(Comparator.comparingInt((Muni m) -> m.counts()[6]).reversed());
        List<Map<String, Object>> muniOut = new ArrayList<>();
        for (Muni m : munis) {
            m.top().sort(Comparator.comparingInt((int[] a) -> a[1]).reversed());
            List<Map<String, Object>> top = new ArrayList<>();
            for (int i = 0; i < Math.min(4, m.top().size()); i++) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("idx", m.top().get(i)[0]);
                t.put("nom", agents.get(m.top().get(i)[0]).nom());
                t.put("totInt", m.top().get(i)[1]);
                top.add(t);
            }
            Map<String, Object> mo = new LinkedHashMap<>();
            mo.put("key", m.key());
            mo.put("prov", m.prov());
            mo.put("lat", m.sums()[0] / m.counts()[0]);
            mo.put("lon", m.sums()[1] / m.counts()[0]);
            mo.put("count", m.counts()[0]);
            mo.put("pubInt", m.counts()[1]);
            mo.put("privInt", m.counts()[2]);
            mo.put("mixInt", m.counts()[3]);
            mo.put("conv", m.counts()[4]);
            mo.put("proj", m.counts()[5]);
            mo.put("tot", m.counts()[6]);
            mo.put("top", top);
            muniOut.add(mo);
        }
        out.put("muni", muniOut);

        // Rànquing d'agents (top 25)
        List<Map.Entry<Integer, AgentAgg>> ranked = new ArrayList<>(r.aggs.entrySet());
        ranked.sort(Comparator.comparingInt((Map.Entry<Integer, AgentAgg> e) -> e.getValue().totInt()).reversed());
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < Math.min(25, ranked.size()); i++) top.add(agentItem(agents, ranked.get(i).getKey(), ranked.get(i).getValue()));
        out.put("topAgents", top);

        out.put("charts", charts(s, r));
        out.put("counts", Map.of("agents", r.aggs.size(), "conv", r.convs.size(), "proj", r.projs.size()));
        return out;
    }

    private static Map<String, Object> charts(Snapshot s, Result r) {
        List<Agent> agents = s.agents();
        Map<String, Object> ch = new LinkedHashMap<>();

        // 1. Evolució anual 2010–2026 (únics per award)
        Map<Integer, Set<String>> yc = new LinkedHashMap<>();
        Map<Integer, Set<String>> yp = new LinkedHashMap<>();
        for (ConvRel c : r.convs) {
            Integer y = c.aw().any();
            if (y != null && y >= 2010 && y <= 2026) yc.computeIfAbsent(y, k -> new HashSet<>()).add(c.aw().awardUuid());
        }
        for (ProjRel p : r.projs) {
            Integer y = p.aw().any();
            if (y != null && y >= 2010 && y <= 2026) yp.computeIfAbsent(y, k -> new HashSet<>()).add(p.aw().awardUuid());
        }
        List<Integer> years = new ArrayList<>();
        List<Integer> convY = new ArrayList<>();
        List<Integer> projY = new ArrayList<>();
        for (int y = 2010; y <= 2026; y++) {
            years.add(y);
            convY.add(yc.containsKey(y) ? yc.get(y).size() : 0);
            projY.add(yp.containsKey(y) ? yp.get(y).size() : 0);
        }
        ch.put("evolution", Map.of("years", years, "conv", convY, "proj", projY));

        // 2. Públic vs privat
        int[] ppConv = new int[3];
        int[] ppProj = new int[3];
        for (ConvRel c : r.convs) ppConv[catSlot(agents.get(c.agent()).tipusInteraccio())]++;
        for (ProjRel p : r.projs) ppProj[catSlot(agents.get(p.agent()).tipusInteraccio())]++;
        ch.put("publicPrivate", Map.of("conv", List.of(ppConv[0], ppConv[1], ppConv[2]),
                "proj", List.of(ppProj[0], ppProj[1], ppProj[2])));

        // 3. Top 10 tipologies
        ch.put("typologies", topSplit(r, 10,
                c -> agents.get(c.agent()).tipologia(), p -> agents.get(p.agent()).tipologia()));
        // 4. Top 12 unitats UAB
        ch.put("units", topSplit(r, 12, c -> c.aw().unitat(), p -> p.aw().unitat()));
        return ch;
    }

    private static int catSlot(String t) {
        return t.equals(PP) ? 0 : (t.equals(PRIV) ? 1 : 2);
    }

    private static Map<String, Object> topSplit(Result r, int n, Function<ConvRel, String> convKey,
                                                Function<ProjRel, String> projKey) {
        Map<String, Integer> conv = new LinkedHashMap<>();
        Map<String, Integer> proj = new LinkedHashMap<>();
        Map<String, Integer> all = new LinkedHashMap<>();
        for (ConvRel c : r.convs) {
            String k = convKey.apply(c);
            if (k == null || k.isEmpty()) continue;
            conv.merge(k, 1, Integer::sum);
            all.merge(k, 1, Integer::sum);
        }
        for (ProjRel p : r.projs) {
            String k = projKey.apply(p);
            if (k == null || k.isEmpty()) continue;
            proj.merge(k, 1, Integer::sum);
            all.merge(k, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> es = new ArrayList<>(all.entrySet());
        es.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        List<String> labels = new ArrayList<>();
        List<Integer> cv = new ArrayList<>();
        List<Integer> pv = new ArrayList<>();
        for (int i = 0; i < Math.min(n, es.size()); i++) {
            String k = es.get(i).getKey();
            labels.add(k);
            cv.add(conv.getOrDefault(k, 0));
            pv.add(proj.getOrDefault(k, 0));
        }
        return Map.of("labels", labels, "conv", cv, "proj", pv);
    }

    // ------------------------------------------------------------------------------------
    // Pàgines de taules
    // ------------------------------------------------------------------------------------

    static Map<String, Object> agentItem(List<Agent> agents, int idx, AgentAgg g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", idx);
        m.put("ag", agents.get(idx).tuple());
        m.put("nConv", g.nConv);
        m.put("nProjSoci", g.nProjSoci);
        m.put("nProjFin", g.nProjFin);
        m.put("nProj", g.nProj());
        m.put("impUab", g.impUab);
        m.put("totInt", g.totInt());
        return m;
    }

    private static final Collator CA = Collator.getInstance(Locale.forLanguageTag("ca"));

    private static int cmp(String a, String b) {
        return CA.compare(a == null ? "" : a, b == null ? "" : b);
    }

    static Map<String, Object> agentsPage(Snapshot s, Result r, String col, boolean asc, int page, boolean all) {
        List<Agent> ag = s.agents();
        List<Map.Entry<Integer, AgentAgg>> list = new ArrayList<>(r.aggs.entrySet());
        Comparator<Map.Entry<Integer, AgentAgg>> c = switch (col == null ? "" : col) {
            case "nom" -> (a, b) -> cmp(ag.get(a.getKey()).nom(), ag.get(b.getKey()).nom());
            case "interaccio" -> (a, b) -> cmp(ag.get(a.getKey()).tipusInteraccio(), ag.get(b.getKey()).tipusInteraccio());
            case "tipologia" -> (a, b) -> cmp(ag.get(a.getKey()).tipologia(), ag.get(b.getKey()).tipologia());
            case "municipi" -> (a, b) -> cmp(ag.get(a.getKey()).municipiKey(), ag.get(b.getKey()).municipiKey());
            case "nConv" -> Comparator.comparingInt(e -> e.getValue().nConv);
            case "nProj" -> Comparator.comparingInt(e -> e.getValue().nProj());
            case "impUab" -> Comparator.comparingDouble(e -> e.getValue().impUab);
            default -> Comparator.comparingInt(e -> e.getValue().totInt());
        };
        list.sort(asc ? c : c.reversed());
        List<Map<String, Object>> items = new ArrayList<>();
        int from = all ? 0 : page * PAGE_SIZE;
        int to = all ? list.size() : Math.min(list.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) items.add(agentItem(ag, list.get(i).getKey(), list.get(i).getValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", list.size());
        out.put("page", page);
        out.put("pageSize", PAGE_SIZE);
        out.put("items", items);
        return out;
    }

    static Map<String, Object> convsPage(Snapshot s, Result r, String col, boolean asc, int page, boolean all) {
        List<Agent> ag = s.agents();
        List<ConvRel> list = new ArrayList<>(r.convs);
        Comparator<ConvRel> c = switch (col == null ? "" : col) {
            case "any" -> Comparator.comparingInt(x -> x.aw().any() == null ? 0 : x.aw().any());
            case "titol" -> (a, b) -> cmp(a.aw().titol(), b.aw().titol());
            case "agent" -> (a, b) -> cmp(ag.get(a.agent()).nom(), ag.get(b.agent()).nom());
            case "interaccio" -> (a, b) -> cmp(ag.get(a.agent()).tipusInteraccio(), ag.get(b.agent()).tipusInteraccio());
            case "unitat" -> (a, b) -> cmp(a.aw().unitat(), b.aw().unitat());
            default -> Comparator.comparingDouble(x -> x.aw().impUab());
        };
        list.sort(asc ? c : c.reversed());
        return relPage(ag, list, ConvRel::agent, ConvRel::row, page, all);
    }

    static Map<String, Object> projsPage(Snapshot s, Result r, String col, boolean asc, int page, boolean all) {
        List<Agent> ag = s.agents();
        List<ProjRel> list = new ArrayList<>(r.projs);
        Comparator<ProjRel> c = switch (col == null ? "" : col) {
            case "any" -> Comparator.comparingInt(x -> x.aw().any() == null ? 0 : x.aw().any());
            case "titol" -> (a, b) -> cmp(a.aw().titol(), b.aw().titol());
            case "agent" -> (a, b) -> cmp(ag.get(a.agent()).nom(), ag.get(b.agent()).nom());
            case "rol" -> (a, b) -> cmp(a.rol(), b.rol());
            case "unitat" -> (a, b) -> cmp(a.aw().unitat(), b.aw().unitat());
            default -> Comparator.comparingDouble(x -> x.aw().impUab());
        };
        list.sort(asc ? c : c.reversed());
        return relPage(ag, list, ProjRel::agent, ProjRel::row, page, all);
    }

    private static <T> Map<String, Object> relPage(List<Agent> ag, List<T> list, Function<T, Integer> agentOf,
                                                   Function<T, List<Object>> rowOf, int page, boolean all) {
        int from = all ? 0 : page * PAGE_SIZE;
        int to = all ? list.size() : Math.min(list.size(), from + PAGE_SIZE);
        List<List<Object>> rows = new ArrayList<>();
        Map<Integer, List<Object>> agents = new LinkedHashMap<>();
        for (int i = from; i < to; i++) {
            T t = list.get(i);
            rows.add(rowOf.apply(t));
            int idx = agentOf.apply(t);
            agents.computeIfAbsent(idx, k -> ag.get(k).tuple());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", list.size());
        out.put("page", page);
        out.put("pageSize", PAGE_SIZE);
        out.put("items", rows);
        out.put("agents", agents);
        return out;
    }

    /** Fitxa d'un agent: totals històrics + registres que compleixen els filtres vigents. */
    static Map<String, Object> agentDetail(Snapshot s, Result r, int idx) {
        List<List<Object>> convs = new ArrayList<>();
        for (ConvRel c : r.convs) if (c.agent() == idx) convs.add(c.row());
        List<List<Object>> projs = new ArrayList<>();
        for (ProjRel p : r.projs) if (p.agent() == idx) projs.add(p.row());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("idx", idx);
        out.put("agent", s.agents().get(idx).tuple());
        out.put("convs", convs);
        out.put("projs", projs);
        return out;
    }
}
