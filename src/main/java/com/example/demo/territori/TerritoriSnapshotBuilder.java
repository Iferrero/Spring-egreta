package com.example.demo.territori;

import com.example.demo.territori.TerritoriLookups.Indicator;
import com.example.demo.territori.TerritoriModel.Agent;
import com.example.demo.territori.TerritoriModel.ConvAward;
import com.example.demo.territori.TerritoriModel.ConvRel;
import com.example.demo.territori.TerritoriModel.ProjAward;
import com.example.demo.territori.TerritoriModel.ProjRel;
import com.example.demo.territori.TerritoriModel.Snapshot;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.demo.territori.TerritoriLookups.CONVENI_TYPES;
import static com.example.demo.territori.TerritoriLookups.ESFERA_ORG_UUID;
import static com.example.demo.territori.TerritoriLookups.PROJECT_TYPES;
import static com.example.demo.territori.TerritoriLookups.UAB_EXTERNAL_SELF_UUID;
import static com.example.demo.territori.TerritoriLookups.UAB_ORG_UUID;
import static com.example.demo.territori.TerritoriText.cleanDescription;
import static com.example.demo.territori.TerritoriText.containsWord;
import static com.example.demo.territori.TerritoriText.joinDistinct;
import static com.example.demo.territori.TerritoriText.list;
import static com.example.demo.territori.TerritoriText.map;
import static com.example.demo.territori.TerritoriText.normalize;
import static com.example.demo.territori.TerritoriText.parseDate;
import static com.example.demo.territori.TerritoriText.parseFloat;
import static com.example.demo.territori.TerritoriText.path;
import static com.example.demo.territori.TerritoriText.pickLang;
import static com.example.demo.territori.TerritoriText.round;
import static com.example.demo.territori.TerritoriText.str;
import static com.example.demo.territori.TerritoriText.truthy;

/**
 * Port Java de {@code scripts/export_mapa_interaccions_territori.py}: llegeix Mongo (kraken) i construeix
 * la instantània d'agents, convenis i projectes del territori català.
 */
final class TerritoriSnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(TerritoriSnapshotBuilder.class);

    private final MongoTemplate mongo;
    private final TerritoriLookups lk;

    TerritoriSnapshotBuilder(MongoTemplate mongo, TerritoriLookups lookups) {
        this.mongo = mongo;
        this.lk = lookups;
    }

    // ------------------------------------------------------------------------------------
    // Tipus interns
    // ------------------------------------------------------------------------------------

    private record OrgInfo(String nom, String tipus, String ambit) {}

    private record Detect(boolean catalan, String metode, String provincia) {}

    private record Caracter(String caracter, String origen, String tipusInteraccio) {}

    private static final class CatAgent {
        String uuid, nom, caracter, origenCaracter, tipusInteraccio, tipologia, municipi, cp, provincia,
                coordenades, metode, ror, nif;
    }

    private record Collab(String uuid, String role, boolean lead) {}

    private record LinkedProject(String projectUuid, String description, List<Collab> extCollabs,
                                 boolean hasCollabs, boolean internalLead) {}

    private record AppDoc(String foppUuid, List<Collab> extCollabs, List<String> extOrgs) {}

    private record Fopp(String title, String type, String programa) {}

    private static final class RoleInfo {
        boolean funder, partner, externalLead;
        final List<String> partnerRoles = new ArrayList<>();

        void addRole(String r) {
            if (!partnerRoles.contains(r)) partnerRoles.add(r);
        }
    }

    private static final class AwardMeta {
        String awardUuid, projectUuid, codiRef, titol, tipusAward, naturalesa;
        Object pureId;
        Integer any;
        String inici, fi;
        double impTot, impUab;
        String unitat, tipusUnitat, ambit, ip;
        int nMembres;
        String desc, convocatoria, programa;
        List<LinkedProject> linkedProjects = List.of();
        List<Collab> appExtCollabs = new ArrayList<>();
        List<String> appExtOrgs = new ArrayList<>();
    }

    private static final class Agg {
        final CatAgent info;
        final Set<String> convUuids = new HashSet<>();
        double convImp;
        final List<Integer> convYears = new ArrayList<>();
        final Set<String> projConsorci = new HashSet<>();
        final Set<String> projFunder = new HashSet<>();
        final Set<String> projAll = new HashSet<>();
        double projImp;
        final List<Integer> projYears = new ArrayList<>();
        final Map<String, Integer> units = new LinkedHashMap<>();

        Agg(CatAgent info) {
            this.info = info;
        }
    }

    private record PendingConv(ConvAward aw, String agentUuid, String rol) {}

    private record PendingProj(ProjAward aw, String agentUuid, String rol, String rolDetall, String altres) {}

    // ------------------------------------------------------------------------------------
    // Classificació territorial i caràcter
    // ------------------------------------------------------------------------------------

    private boolean isUabSelfReference(String euUuid, String nameNorm) {
        if (UAB_EXTERNAL_SELF_UUID.equals(euUuid)) return true;
        if (nameNorm.contains("universitat autonoma de barcelona")
                || nameNorm.contains("universidad autonoma de barcelona")
                || containsWord(nameNorm, "uab")) {
            for (String kw : lk.uabKeepKeywords) {
                if (containsWord(nameNorm, kw)) return false;
            }
            return true;
        }
        return false;
    }

    private static final Detect NOT_CAT = new Detect(false, "", "");

    private Detect detectCatalanTerritory(Document eo) {
        String nameNorm = normalize(pickLang(eo.get("name")));
        if (lk.placeholderOrgNames.contains(nameNorm) || nameNorm.startsWith("sense entitat")) return NOT_CAT;

        Map<String, Object> addr = map(eo.get("address"));
        if (addr == null) addr = Map.of();
        String countryUri = str(path(addr, "country", "uri")).strip().toLowerCase();
        String countryTermNorm = normalize(pickLang(path(addr, "country", "term")));

        if (!countryUri.isEmpty() && !countryUri.endsWith("/es")) return NOT_CAT;
        if (countryUri.isEmpty() && !countryTermNorm.isEmpty()
                && !(countryTermNorm.equals("spain") || countryTermNorm.equals("espana") || countryTermNorm.equals("espanya"))) {
            return NOT_CAT;
        }

        String cpDigits = str(addr.get("postalCode")).strip().replaceAll("[^0-9]", "");
        String cityNorm = normalize(str(addr.get("city")).strip());
        String addr1Norm = normalize(str(addr.get("address1")));

        // 1. Codi postal de 5 dígits
        if (cpDigits.length() == 5) {
            String prov = lk.cpPrefixProvince.get(cpDigits.substring(0, 2));
            return prov != null ? new Detect(true, "Confirmada per Codi Postal", prov) : NOT_CAT;
        }

        // 2. Municipi informat
        if (!cityNorm.isEmpty() && !Set.of("-", "unknown", "desconegut", "desconocido", "n a").contains(cityNorm)) {
            for (String nc : lk.nonCatCities) {
                if (cityNorm.equals(nc) || containsWord(cityNorm, nc)) return NOT_CAT;
            }
            for (Map.Entry<String, String> e : lk.catMunicipisProv.entrySet()) {
                if (cityNorm.equals(e.getKey()) || containsWord(cityNorm, e.getKey())) {
                    return new Detect(true, "Confirmada per Municipi", e.getValue());
                }
            }
            for (Indicator ind : lk.catNameIndicators) {
                if (containsWord(cityNorm, ind.keyword()) || containsWord(addr1Norm, ind.keyword())
                        || containsWord(nameNorm, ind.keyword())) {
                    return new Detect(true, "Confirmada per Municipi / Adreça", ind.provincia());
                }
            }
            return NOT_CAT;
        }

        // 3. Sense CP vàlid ni municipi: adreça i nom
        String combined = (addr1Norm + " " + nameNorm).strip();
        for (List<String> group : List.of(lk.nonCatCities, lk.nonCatRegions)) {
            for (String nc : group) {
                if (containsWord(combined, nc)) {
                    if (nc.equals("valencia") && !combined.contains("valenciana") && combined.contains("catalunya")) continue;
                    return NOT_CAT;
                }
            }
        }
        for (Indicator ind : lk.catNameIndicators) {
            if (containsWord(combined, ind.keyword())) {
                return new Detect(true, "Inferida pel Nom de l'Entitat", ind.provincia());
            }
        }
        return NOT_CAT;
    }

    private Caracter extractCaracter(Document eo) {
        String caracterVal = "";
        for (Object kgo : list(eo.get("keywordGroups"))) {
            Map<String, Object> kg = map(kgo);
            if (kg == null) continue;
            if (!str(kg.get("logicalName")).toLowerCase().contains("caracter")) continue;
            for (Object clo : list(kg.get("classifications"))) {
                Map<String, Object> cl = map(clo);
                String val = cl == null ? "" : pickLang(cl.get("term"));
                if (!val.isEmpty()) {
                    caracterVal = val;
                    break;
                }
            }
            if (caracterVal.isEmpty()) {
                for (Object kco : list(kg.get("keywordContainers"))) {
                    Map<String, Object> kc = map(kco);
                    String val = kc == null ? "" : pickLang(path(kc, "structuredKeyword", "term"));
                    if (!val.isEmpty()) {
                        caracterVal = val;
                        break;
                    }
                }
            }
            if (!caracterVal.isEmpty()) break;
        }

        if (!caracterVal.isEmpty()) {
            String c = normalize(caracterVal);
            if (Set.of("publica", "public", "publico").contains(c)) return new Caracter("Pública", "EGRETA validat", "Publico-Públic");
            if (Set.of("privada", "private", "privado").contains(c)) return new Caracter("Privada", "EGRETA validat", "Publico-Privat");
            if (Set.of("mixta", "mixto", "mixed").contains(c)) return new Caracter("Mixta", "EGRETA validat", "Publico-Mixt / Tercer Sector");
            if (c.equals("altres")) return new Caracter("Altres", "EGRETA validat", "Publico-Mixt / Tercer Sector");
        }

        String tipusNorm = normalize(pickLang(path(eo, "type", "term")));
        String nameNorm = normalize(pickLang(eo.get("name")));
        final String inferit = "Inferit per tipologia/nom";

        for (String k : lk.privateUniKeywords) {
            if (containsWord(nameNorm, k)) return new Caracter("Privada (inferida)", inferit, "Publico-Privat");
        }
        boolean suffix = false;
        for (String s : lk.privateLegalSuffixes) {
            if (containsWord(nameNorm, s)) {
                suffix = true;
                break;
            }
        }
        if (tipusNorm.equals("entitat empresarial") || suffix) {
            return new Caracter("Privada (inferida)", inferit, "Publico-Privat");
        }
        boolean publicType = Set.of(
                "agencia estatal", "govern", "organisme public d investigacio", "universitat",
                "departament universitari", "institut universitari d investigacio",
                "entitat gestora del sistema nacional de salut", "administracio internacional",
                "sector administracio publica").contains(tipusNorm);
        if (!publicType) {
            for (String k : lk.publicKeywords) {
                if (containsWord(nameNorm, k)) {
                    publicType = true;
                    break;
                }
            }
        }
        if (publicType) return new Caracter("Pública (inferida)", inferit, "Publico-Públic");
        return new Caracter("Sense classificar (Tercer sector / Altres)", "No classificat a EGRETA", "Publico-Mixt / Tercer Sector");
    }

    private String[] extractIdentifiers(Document eo) {
        String ror = "";
        String nif = "";
        for (Object o : list(eo.get("identifiers"))) {
            Map<String, Object> id = map(o);
            if (id == null) continue;
            String typeUri = str(path(id, "type", "uri")).toLowerCase();
            String idSrc = str(id.get("idSource")).toLowerCase();
            String val = str(truthy(id.get("value")) ? id.get("value") : id.get("id")).strip();
            if (val.isEmpty()) continue;
            if (ror.isEmpty() && (typeUri.contains("/ror") || idSrc.equals("ror") || val.toLowerCase().contains("ror.org"))) {
                ror = val;
            } else if (nif.isEmpty() && (typeUri.contains("/nif") || idSrc.equals("nif"))) {
                nif = val;
            }
        }
        return new String[] {ror, nif};
    }

    // ------------------------------------------------------------------------------------
    // Càrrega de catàlegs
    // ------------------------------------------------------------------------------------

    private MongoCollection<Document> col(String name) {
        return mongo.getCollection(name);
    }

    private Map<String, OrgInfo> loadInternalOrgs() {
        Map<String, String> nom = new HashMap<>();
        Map<String, String> tipus = new HashMap<>();
        Map<String, List<String>> parents = new HashMap<>();
        for (Document d : col("Organizations").find()
                .projection(new Document("uuid", 1).append("name", 1).append("type", 1).append("parents", 1))) {
            String u = str(d.get("uuid"));
            if (u == null || u.isEmpty()) continue;
            nom.put(u, pickLang(d.get("name")));
            tipus.put(u, pickLang(path(d, "type", "term")));
            List<String> ps = new ArrayList<>();
            for (Object p : list(d.get("parents"))) {
                Map<String, Object> pm = map(p);
                if (pm != null && truthy(pm.get("uuid"))) ps.add(str(pm.get("uuid")));
            }
            parents.put(u, ps);
        }
        Map<String, OrgInfo> out = new HashMap<>();
        for (String u : nom.keySet()) {
            Set<String> visited = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            queue.add(u);
            String res = "UAB / Altre";
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                if (!visited.add(cur)) continue;
                if (cur.equals(ESFERA_ORG_UUID)) {
                    res = "Esfera UAB";
                    break;
                }
                if (cur.equals(UAB_ORG_UUID)) {
                    res = "UAB";
                    break;
                }
                for (String p : parents.getOrDefault(cur, List.of())) {
                    if (!visited.contains(p)) queue.add(p);
                }
            }
            out.put(u, new OrgInfo(nom.get(u), tipus.get(u), res));
        }
        return out;
    }

    private Map<String, CatAgent> loadCatalanAgents(Map<String, Object> stats) {
        Map<String, CatAgent> catalan = new LinkedHashMap<>();
        int totalEo = 0, placeholders = 0, autoref = 0;
        Document proj = new Document("uuid", 1).append("name", 1).append("address", 1).append("type", 1)
                .append("keywordGroups", 1).append("identifiers", 1);
        for (Document doc : col("ExternalOrganizations").find().projection(proj)) {
            totalEo++;
            String u = str(doc.get("uuid"));
            if (u == null || u.isEmpty()) continue;
            String name = pickLang(doc.get("name"));
            String nameNorm = normalize(name);
            if (lk.placeholderOrgNames.contains(nameNorm) || nameNorm.startsWith("sense entitat")) {
                placeholders++;
                continue;
            }
            if (isUabSelfReference(u, nameNorm)) {
                autoref++;
                continue;
            }
            Detect det = detectCatalanTerritory(doc);
            if (!det.catalan()) continue;

            Caracter car = extractCaracter(doc);
            String[] ids = extractIdentifiers(doc);
            Map<String, Object> addr = map(doc.get("address"));
            if (addr == null) addr = Map.of();
            String tipologia = pickLang(path(doc, "type", "term"));
            String city = str(addr.get("city")).strip();
            if (city.equals("-")) city = "";

            CatAgent a = new CatAgent();
            a.uuid = u;
            a.nom = name;
            a.caracter = car.caracter();
            a.origenCaracter = car.origen();
            a.tipusInteraccio = car.tipusInteraccio();
            a.tipologia = tipologia.isEmpty() ? "Desconegut" : tipologia;
            a.municipi = city;
            a.cp = str(addr.get("postalCode")).strip();
            a.provincia = det.provincia().isEmpty() ? "Catalunya (general)" : det.provincia();
            a.coordenades = str(path(addr, "geoLocation", "point")).strip();
            a.metode = det.metode();
            a.ror = ids[0];
            a.nif = ids[1];
            catalan.put(u, a);
        }
        stats.put("totalEo", totalEo);
        stats.put("placeholdersExclosos", placeholders);
        stats.put("autoreferenciesExcloses", autoref);
        return catalan;
    }

    private Map<String, List<LinkedProject>> loadProjectsByCluster() {
        Map<String, List<LinkedProject>> byCluster = new HashMap<>();
        Document proj = new Document("uuid", 1).append("pureId", 1).append("title", 1).append("descriptions", 1)
                .append("collaborators", 1).append("awardClusters", 1).append("workflow", 1).append("type", 1);
        for (Document doc : col("Projects").find().projection(proj)) {
            List<String> clusters = new ArrayList<>();
            for (Object c : list(doc.get("awardClusters"))) {
                Map<String, Object> cm = map(c);
                if (cm != null && truthy(cm.get("uuid"))) clusters.add(str(cm.get("uuid")));
            }
            if (clusters.isEmpty()) continue;
            List<Collab> ext = new ArrayList<>();
            boolean internalLead = false;
            boolean hasCollabs = truthy(doc.get("collaborators"));
            for (Object co : list(doc.get("collaborators"))) {
                Map<String, Object> c = map(co);
                if (c == null) continue;
                String disc = str(c.get("typeDiscriminator"));
                if (disc.equals("InternalCollaboratorAssociation") && truthy(c.get("leadCollaborator"))) {
                    internalLead = true;
                } else if (disc.equals("ExternalCollaboratorAssociation")) {
                    String eu = str(path(c, "externalOrganization", "uuid"));
                    if (!eu.isEmpty()) {
                        String role = pickLang(path(c, "type", "term"));
                        ext.add(new Collab(eu, role.isEmpty() ? "Col·laborador" : role, truthy(c.get("leadCollaborator"))));
                    }
                }
            }
            LinkedProject lp = new LinkedProject(str(doc.get("uuid")), cleanDescription(doc.get("descriptions")),
                    ext, hasCollabs, internalLead);
            for (String cl : clusters) byCluster.computeIfAbsent(cl, k -> new ArrayList<>()).add(lp);
        }
        return byCluster;
    }

    private Map<String, AppDoc> loadApplications(Map<String, Fopp> fopps) {
        for (Document doc : col("FundingOpportunities").find()
                .projection(new Document("uuid", 1).append("title", 1).append("type", 1).append("keywordGroups", 1))) {
            String u = str(doc.get("uuid"));
            if (u == null || u.isEmpty()) continue;
            List<String> programes = new ArrayList<>();
            for (Object kgo : list(doc.get("keywordGroups"))) {
                Map<String, Object> kg = map(kgo);
                if (kg == null || !"/uab/fundingopportunities/programes".equals(kg.get("logicalName"))) continue;
                for (Object kco : list(kg.get("keywordContainers"))) {
                    String t = pickLang(path(kco, "structuredKeyword", "term"));
                    if (!t.isEmpty()) programes.add(t);
                }
                for (Object clo : list(kg.get("classifications"))) {
                    String t = pickLang(path(clo, "term"));
                    if (!t.isEmpty()) programes.add(t);
                }
            }
            fopps.put(u, new Fopp(pickLang(doc.get("title")), pickLang(path(doc, "type", "term")), joinDistinct(programes)));
        }

        Map<String, AppDoc> apps = new HashMap<>();
        for (Document doc : col("Applications").find()
                .projection(new Document("uuid", 1).append("fundingOpportunity", 1).append("collaborators", 1).append("externalOrganizations", 1))) {
            String u = str(doc.get("uuid"));
            if (u == null || u.isEmpty()) continue;
            List<Collab> ext = new ArrayList<>();
            for (Object co : list(doc.get("collaborators"))) {
                Map<String, Object> c = map(co);
                if (c != null && "ExternalCollaboratorAssociation".equals(c.get("typeDiscriminator"))) {
                    String eu = str(path(c, "externalOrganization", "uuid"));
                    if (!eu.isEmpty()) {
                        String role = pickLang(path(c, "type", "term"));
                        ext.add(new Collab(eu, role.isEmpty() ? "Col·laborador sol·licitud" : role, truthy(c.get("leadCollaborator"))));
                    }
                }
            }
            List<String> extOrgs = new ArrayList<>();
            for (Object eo : list(doc.get("externalOrganizations"))) {
                Map<String, Object> em = map(eo);
                if (em != null && truthy(em.get("uuid"))) extOrgs.add(str(em.get("uuid")));
            }
            apps.put(u, new AppDoc(str(path(doc, "fundingOpportunity", "uuid")), ext, extOrgs));
        }
        return apps;
    }

    // ------------------------------------------------------------------------------------
    // Awards
    // ------------------------------------------------------------------------------------

    private static String stripCommaSpace(String s) {
        int a = 0, b = s.length();
        while (a < b && (s.charAt(a) == ',' || s.charAt(a) == ' ')) a++;
        while (b > a && (s.charAt(b - 1) == ',' || s.charAt(b - 1) == ' ')) b--;
        return s.substring(a, b);
    }

    private AwardMeta extractAwardMetadata(Document aw, Map<String, OrgInfo> orgs,
                                           Map<String, List<LinkedProject>> projectsByCluster,
                                           Map<String, AppDoc> apps, Map<String, Fopp> fopps) {
        String refCode = "", fenixId = "", primaryId = "";
        for (Object io : list(aw.get("identifiers"))) {
            Map<String, Object> id = map(io);
            if (id == null) continue;
            String val = str(truthy(id.get("id")) ? id.get("id") : id.get("value")).strip();
            if (val.isEmpty()) continue;
            String tUri = str(path(id, "type", "uri")).toLowerCase();
            String disc = str(id.get("typeDiscriminator")).toLowerCase();
            if (tUri.contains("reference") || tUri.contains("referencia")) refCode = val;
            else if (tUri.contains("id_fenix")) fenixId = val;
            else if (disc.equals("primaryid") && primaryId.isEmpty()) primaryId = val;
        }

        AwardMeta m = new AwardMeta();
        m.codiRef = !refCode.isEmpty() ? refCode : (!fenixId.isEmpty() ? fenixId : primaryId);

        LocalDate dAward = parseDate(aw.get("awardDate"));
        Map<String, Object> period = map(aw.get("actualPeriod"));
        LocalDate dStart = period == null ? null : parseDate(period.get("startDate"));
        LocalDate dEnd = period == null ? null : parseDate(period.get("endDate"));
        m.any = dAward != null ? Integer.valueOf(dAward.getYear()) : (dStart != null ? Integer.valueOf(dStart.getYear()) : null);
        m.inici = dStart != null ? dStart.toString() : "";
        m.fi = dEnd != null ? dEnd.toString() : "";

        String moUuid = str(path(aw, "managingOrganization", "uuid"));
        String effective = moUuid;
        for (Object co : list(aw.get("coManagingOrganizations"))) {
            Map<String, Object> cm = map(co);
            if (cm != null && truthy(cm.get("uuid"))) {
                effective = str(cm.get("uuid"));
                break;
            }
        }
        OrgInfo mo = orgs.get(effective);
        if (mo == null) mo = orgs.get(moUuid);
        m.unitat = mo == null ? "" : mo.nom();
        m.tipusUnitat = mo == null ? "" : mo.tipus();
        m.ambit = mo == null ? "" : mo.ambit();

        List<String> ips = new ArrayList<>();
        List<String> coips = new ArrayList<>();
        String firstHolder = "";
        Set<String> personIds = new HashSet<>();
        List<Object> holders = list(aw.get("awardHolders"));
        for (Object ho : holders) {
            Map<String, Object> h = map(ho);
            if (h == null) continue;
            String pu = str(path(h, "person", "uuid"));
            if (!pu.isEmpty()) personIds.add(pu);
            Map<String, Object> nm = map(h.get("name"));
            String fn = nm == null ? "" : str(nm.get("firstName")).strip();
            String ln = nm == null ? "" : str(nm.get("lastName")).strip();
            String full = (!ln.isEmpty() || !fn.isEmpty()) ? stripCommaSpace(ln + ", " + fn).strip() : "";
            if (!full.isEmpty() && firstHolder.isEmpty()) firstHolder = full;
            String roleStr = normalize(pickLang(path(h, "role", "term")));
            if (!full.isEmpty()) {
                if (roleStr.contains("co investigador") || roleStr.contains("co principal")) coips.add(full);
                else if (roleStr.contains("investigador a principal") || roleStr.contains("principal investigator")) ips.add(full);
            }
        }
        String ipUab = joinDistinct(ips);
        if (ipUab.isEmpty()) ipUab = joinDistinct(coips);
        if (ipUab.isEmpty()) ipUab = firstHolder;
        m.ip = ipUab;
        m.nMembres = !personIds.isEmpty() ? personIds.size() : holders.size();

        List<String> natures = new ArrayList<>();
        for (Object no : list(aw.get("natureTypes"))) {
            Map<String, Object> nm = map(no);
            if (nm != null) {
                String t = pickLang(nm.get("term"));
                if (!t.isEmpty()) natures.add(t);
            }
        }
        m.naturalesa = String.join("; ", natures);

        double impTot = 0.0, impUab = 0.0;
        for (Object fo : list(aw.get("fundings"))) {
            Map<String, Object> f = map(fo);
            if (f == null) continue;
            impTot += parseFloat(path(f, "awardedAmount", "value"));
            for (Object fco : list(f.get("fundingCollaborators"))) {
                Map<String, Object> fc = map(fco);
                if (fc == null) continue;
                if (UAB_ORG_UUID.equals(path(fc, "collaborator", "uuid"))) {
                    impUab += parseFloat(path(fc, "institutionalPart", "value"));
                }
            }
        }
        m.impTot = round(impTot, 2);
        m.impUab = round(impUab, 2);

        String clusterUuid = str(path(aw, "cluster", "uuid"));
        m.linkedProjects = projectsByCluster.getOrDefault(clusterUuid, List.of());
        m.projectUuid = m.linkedProjects.isEmpty() ? "" : m.linkedProjects.get(0).projectUuid();

        String desc = cleanDescription(aw.get("descriptions"));
        if (desc.isEmpty()) {
            for (LinkedProject lp : m.linkedProjects) {
                if (!lp.description().isEmpty()) {
                    desc = lp.description();
                    break;
                }
            }
        }
        m.desc = desc;

        List<String> titles = new ArrayList<>();
        List<String> progs = new ArrayList<>();
        for (Object ao : list(aw.get("applications"))) {
            Map<String, Object> ar = map(ao);
            if (ar == null) continue;
            AppDoc app = apps.get(str(ar.get("uuid")));
            if (app == null) continue;
            m.appExtCollabs.addAll(app.extCollabs());
            m.appExtOrgs.addAll(app.extOrgs());
            Fopp fopp = fopps.get(app.foppUuid());
            if (fopp != null) {
                if (!fopp.title().isEmpty()) titles.add(fopp.title());
                if (!fopp.programa().isEmpty()) progs.add(fopp.programa());
            }
        }
        m.convocatoria = joinDistinct(titles);
        m.programa = joinDistinct(progs);

        m.awardUuid = str(aw.get("uuid"));
        Object pid = aw.get("pureId");
        m.pureId = pid instanceof Number n ? (Object) n.longValue() : str(pid);
        m.titol = pickLang(aw.get("title"));
        m.tipusAward = pickLang(path(aw, "type", "term"));
        return m;
    }

    /** Resultat de collectAwardExternalRoles: rols per entitat, lideratge UAB i nombre de socis externs. */
    private record Roles(Map<String, RoleInfo> byExt, String uabLider, int totalSocis) {}

    private Roles collectAwardExternalRoles(Document aw, AwardMeta meta) {
        Map<String, RoleInfo> roles = new LinkedHashMap<>();
        Set<String> partners = new HashSet<>();

        // 1. Finançadors i col·laboradors externs de finançament
        for (Object fo : list(aw.get("fundings"))) {
            Map<String, Object> f = map(fo);
            if (f == null) continue;
            String fu = str(path(f, "funder", "uuid"));
            if (!fu.isEmpty()) roles.computeIfAbsent(fu, k -> new RoleInfo()).funder = true;
            for (Object fco : list(f.get("fundingCollaborators"))) {
                Map<String, Object> fc = map(fco);
                if (fc == null) continue;
                Map<String, Object> collab = map(fc.get("collaborator"));
                if (collab != null && "ExternalOrganization".equals(collab.get("systemName")) && truthy(collab.get("uuid"))) {
                    String eu = str(collab.get("uuid"));
                    partners.add(eu);
                    RoleInfo e = roles.computeIfAbsent(eu, k -> new RoleInfo());
                    e.partner = true;
                    e.addRole("Soci / Col·laborador finançament");
                }
            }
        }

        // 2. Award.collaborators i externalOrganizations
        boolean hasAnyCollabList = truthy(aw.get("collaborators"));
        boolean internalLead = false;
        boolean externalLead = false;
        for (Object co : list(aw.get("collaborators"))) {
            Map<String, Object> c = map(co);
            if (c == null) continue;
            String disc = str(c.get("typeDiscriminator"));
            if (disc.equals("InternalCollaboratorAssociation") && truthy(c.get("leadCollaborator"))) {
                internalLead = true;
            } else if (disc.equals("ExternalCollaboratorAssociation")) {
                String eu = str(path(c, "externalOrganization", "uuid"));
                if (!eu.isEmpty()) {
                    partners.add(eu);
                    RoleInfo e = roles.computeIfAbsent(eu, k -> new RoleInfo());
                    e.partner = true;
                    String r = pickLang(path(c, "type", "term"));
                    e.addRole(r.isEmpty() ? "Col·laborador" : r);
                    if (truthy(c.get("leadCollaborator"))) {
                        e.externalLead = true;
                        externalLead = true;
                    }
                }
            }
        }
        for (Object eo : list(aw.get("externalOrganizations"))) {
            Map<String, Object> em = map(eo);
            if (em != null && truthy(em.get("uuid"))) {
                String eu = str(em.get("uuid"));
                partners.add(eu);
                RoleInfo e = roles.computeIfAbsent(eu, k -> new RoleInfo());
                e.partner = true;
                e.addRole("Entitat col·laboradora");
            }
        }

        // 3. Col·laboradors dels projectes enllaçats
        for (LinkedProject lp : meta.linkedProjects) {
            if (lp.hasCollabs()) hasAnyCollabList = true;
            if (lp.internalLead()) internalLead = true;
            for (Collab ec : lp.extCollabs()) {
                partners.add(ec.uuid());
                RoleInfo e = roles.computeIfAbsent(ec.uuid(), k -> new RoleInfo());
                e.partner = true;
                e.addRole(ec.role());
                if (ec.lead()) {
                    e.externalLead = true;
                    externalLead = true;
                }
            }
        }

        // 4. Col·laboradors i entitats de les sol·licituds
        for (Collab ec : meta.appExtCollabs) {
            partners.add(ec.uuid());
            RoleInfo e = roles.computeIfAbsent(ec.uuid(), k -> new RoleInfo());
            e.partner = true;
            e.addRole(ec.role());
            if (ec.lead()) {
                e.externalLead = true;
                externalLead = true;
            }
        }
        for (String eu : meta.appExtOrgs) {
            partners.add(eu);
            RoleInfo e = roles.computeIfAbsent(eu, k -> new RoleInfo());
            e.partner = true;
            e.addRole("Entitat col·laboradora (sol·licitud)");
        }

        String lider;
        if (!hasAnyCollabList && partners.isEmpty()) lider = "Individual / Sense consorci extern";
        else if (internalLead) lider = "Sí (UAB coordinadora/líder)";
        else if (externalLead) lider = "No (Coordinat per soci extern)";
        else if (!partners.isEmpty()) lider = "Consorci sense líder marcat";
        else lider = "Sí";
        return new Roles(roles, lider, partners.size());
    }

    private static boolean isResignedAward(Document aw) {
        if ("DeclinedAwardStatus".equals(str(path(aw, "status", "typeDiscriminator")))) return true;
        for (Object kgo : list(aw.get("keywordGroups"))) {
            Map<String, Object> kg = map(kgo);
            if (kg == null) continue;
            for (Object kco : list(kg.get("keywordContainers"))) {
                if (str(path(kco, "structuredKeyword", "uri")).toLowerCase().contains("resigned")) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------
    // Construcció de la instantània
    // ------------------------------------------------------------------------------------

    Snapshot build() {
        long t0 = System.currentTimeMillis();
        log.info("[territori] Construint instantània des de Mongo (kraken)...");

        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, OrgInfo> internalOrgs = loadInternalOrgs();
        Map<String, CatAgent> catalan = loadCatalanAgents(stats);
        Map<String, List<LinkedProject>> projectsByCluster = loadProjectsByCluster();
        Map<String, Fopp> fopps = new HashMap<>();
        Map<String, AppDoc> apps = loadApplications(fopps);
        log.info("[territori] Catàlegs carregats: {} unitats, {} agents catalans (de {} EO)",
                internalOrgs.size(), catalan.size(), stats.get("totalEo"));

        Set<String> targetTypes = new LinkedHashSet<>(CONVENI_TYPES);
        targetTypes.addAll(PROJECT_TYPES);
        Document query = new Document("workflow.step", new Document("$in", List.of("validated", "approved")))
                .append("$or", List.of(
                        new Document("type.term.ca_ES", new Document("$in", new ArrayList<>(targetTypes))),
                        new Document("categoria", "Ajudes no competitives")));
        Document projection = new Document();
        for (String f : List.of("uuid", "pureId", "title", "type", "categoria", "workflow", "status", "keywordGroups",
                "awardDate", "actualPeriod", "identifiers", "natureTypes", "descriptions", "managingOrganization",
                "coManagingOrganizations", "awardHolders", "fundings", "collaborators", "externalOrganizations",
                "cluster", "applications")) {
            projection.append(f, 1);
        }

        Map<String, ConvAward> convAwards = new LinkedHashMap<>();
        Map<String, ProjAward> projAwards = new LinkedHashMap<>();
        List<PendingConv> convRows = new ArrayList<>();
        List<PendingProj> projRows = new ArrayList<>();
        Map<String, Agg> aggs = new LinkedHashMap<>();

        for (Document aw : col("Awards").find(query).projection(projection)) {
            if (isResignedAward(aw)) continue;
            String tipusCa = pickLang(path(aw, "type", "term"));
            String cat = str(aw.get("categoria"));
            boolean isConveni = CONVENI_TYPES.contains(tipusCa) || cat.equals("Ajudes no competitives");
            boolean isProject = !isConveni && PROJECT_TYPES.contains(tipusCa);
            if (!isConveni && !isProject) continue;

            AwardMeta meta = extractAwardMetadata(aw, internalOrgs, projectsByCluster, apps, fopps);
            Roles roles = collectAwardExternalRoles(aw, meta);
            List<String> catUuids = new ArrayList<>();
            for (String u : roles.byExt().keySet()) if (catalan.containsKey(u)) catUuids.add(u);
            if (catUuids.isEmpty()) continue;

            if (isConveni) {
                ConvAward ca = convAwards.computeIfAbsent(meta.awardUuid, k -> new ConvAward(
                        meta.awardUuid, meta.pureId, meta.codiRef, meta.tipusAward, meta.naturalesa, meta.any,
                        meta.inici, meta.fi, meta.impTot, meta.impUab, meta.titol, meta.desc, meta.convocatoria,
                        meta.unitat, meta.tipusUnitat, meta.ambit, meta.ip, meta.nMembres));
                for (String eu : catUuids) {
                    CatAgent ag = catalan.get(eu);
                    RoleInfo r = roles.byExt().get(eu);
                    String rol = r.funder && r.partner ? "Finançador i Col·laborador"
                            : (r.funder ? "Finançador / Contractant" : "Col·laborador");
                    convRows.add(new PendingConv(ca, eu, rol));

                    Agg agg = aggs.computeIfAbsent(eu, k -> new Agg(ag));
                    if (agg.convUuids.add(meta.awardUuid)) {
                        agg.convImp += meta.impUab;
                        if (meta.any != null && meta.any != 0) agg.convYears.add(meta.any);
                    }
                    if (!meta.unitat.isEmpty()) agg.units.merge(meta.unitat, 1, Integer::sum);
                }
            } else {
                ProjAward pa = projAwards.computeIfAbsent(meta.awardUuid, k -> new ProjAward(
                        meta.awardUuid, meta.projectUuid, meta.pureId, meta.codiRef, meta.tipusAward, meta.naturalesa,
                        meta.any, meta.inici, meta.fi, meta.impTot, meta.impUab, roles.uabLider(), roles.totalSocis(),
                        meta.titol, meta.desc, meta.convocatoria, meta.programa, meta.unitat, meta.tipusUnitat,
                        meta.ambit, meta.ip, meta.nMembres));
                for (String eu : catUuids) {
                    CatAgent ag = catalan.get(eu);
                    RoleInfo r = roles.byExt().get(eu);
                    String rolProj = r.funder && r.partner ? "Finançador i Soci de consorci"
                            : (r.partner ? "Soci / Col·laborador de consorci" : "Entitat finançadora / Promotora");
                    List<String> altres = new ArrayList<>();
                    for (String ou : catUuids) if (!ou.equals(eu)) altres.add(catalan.get(ou).nom);
                    String rolDetall = !r.partnerRoles.isEmpty() ? String.join("; ", r.partnerRoles) : (r.funder ? "Finançador" : "");
                    projRows.add(new PendingProj(pa, eu, rolProj, rolDetall, String.join("; ", altres)));

                    Agg agg = aggs.computeIfAbsent(eu, k -> new Agg(ag));
                    if (agg.projAll.add(meta.awardUuid)) {
                        agg.projImp += meta.impUab;
                        if (meta.any != null && meta.any != 0) agg.projYears.add(meta.any);
                    }
                    if (r.partner) agg.projConsorci.add(meta.awardUuid);
                    if (r.funder) agg.projFunder.add(meta.awardUuid);
                    if (!meta.unitat.isEmpty()) agg.units.merge(meta.unitat, 1, Integer::sum);
                }
            }
        }

        // Agents: ordenats per interaccions i import (desc), igual que el CSV d'agents
        record AgentRow(Agent agent, double sortImp) {}
        Map<String, Integer> coordOrigins = new LinkedHashMap<>();
        List<AgentRow> agentRows = new ArrayList<>();
        for (Agg agg : aggs.values()) {
            CatAgent ag = agg.info;
            int nConv = agg.convUuids.size();
            int nPc = agg.projConsorci.size();
            int nPf = agg.projFunder.size();
            int nPtot = agg.projAll.size();
            String cy = agg.convYears.isEmpty() ? "" : agg.convYears.stream().min(Integer::compare).get() + "-" + agg.convYears.stream().max(Integer::compare).get();
            String py = agg.projYears.isEmpty() ? "" : agg.projYears.stream().min(Integer::compare).get() + "-" + agg.projYears.stream().max(Integer::compare).get();
            List<Map.Entry<String, Integer>> units = new ArrayList<>(agg.units.entrySet());
            units.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            List<String> topUnits = new ArrayList<>();
            for (int i = 0; i < Math.min(3, units.size()); i++) topUnits.add(units.get(i).getKey() + " (" + units.get(i).getValue() + ")");

            TerritoriLookups.Resolved rc = lk.resolveCoords(ag.coordenades, ag.municipi, ag.cp, ag.provincia);
            coordOrigins.merge(rc.origin(), 1, Integer::sum);
            double impConv = round(agg.convImp, 2);
            double impProj = round(agg.projImp, 2);
            Agent a = new Agent(ag.uuid, ag.nom, ag.caracter, ag.tipusInteraccio, ag.tipologia, ag.municipi, ag.cp,
                    ag.provincia, rc.lat(), rc.lon(), ag.ror, ag.nif, ag.metode,
                    nConv, impConv, cy, nPc, nPf, impProj, py, nConv + nPtot, String.join("; ", topUnits));
            agentRows.add(new AgentRow(a, impConv + impProj));
        }
        agentRows.sort(Comparator.<AgentRow>comparingInt(r -> r.agent().totInt())
                .thenComparingDouble(AgentRow::sortImp).reversed());
        List<Agent> agents = new ArrayList<>(agentRows.size());
        Map<String, Integer> idxByUuid = new HashMap<>();
        for (AgentRow r : agentRows) {
            idxByUuid.put(r.agent().uuid(), agents.size());
            agents.add(r.agent());
        }

        List<ConvRel> convs = new ArrayList<>(convRows.size());
        for (PendingConv p : convRows) convs.add(new ConvRel(p.aw(), idxByUuid.get(p.agentUuid()), p.rol()));
        convs.sort(Comparator.<ConvRel>comparingInt(r -> r.aw().any() == null ? 0 : r.aw().any())
                .thenComparingDouble(r -> r.aw().impUab()).reversed());
        List<ProjRel> projs = new ArrayList<>(projRows.size());
        for (PendingProj p : projRows) projs.add(new ProjRel(p.aw(), idxByUuid.get(p.agentUuid()), p.rol(), p.rolDetall(), p.altres()));
        projs.sort(Comparator.<ProjRel>comparingInt(r -> r.aw().any() == null ? 0 : r.aw().any())
                .thenComparingDouble(r -> r.aw().impUab()).reversed());

        stats.put("agentsCatalunya", catalan.size());
        stats.put("agentsActius", agents.size());
        stats.put("relacionsConveni", convs.size());
        stats.put("relacionsProjecte", projs.size());
        stats.put("convenisUnics", convAwards.size());
        stats.put("projectesUnics", projAwards.size());

        List<List<Object>> rc = List.of();
        try {
            rc = Rc0025rReader.read();
        } catch (RuntimeException e) {
            log.warn("[territori] No s'ha pogut llegir RC0025R: {}", e.getMessage());
        }
        log.info("[territori] Instantània llesta en {} ms: {} agents actius, {} relacions conveni, {} relacions projecte",
                System.currentTimeMillis() - t0, agents.size(), convs.size(), projs.size());
        return new Snapshot(List.copyOf(agents), List.copyOf(convs), List.copyOf(projs), stats, coordOrigins, rc, Instant.now());
    }
}
