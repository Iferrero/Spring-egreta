package com.example.demo.territori;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Estructures immutables de la instantània en memòria del mapa d'interaccions amb el territori. */
final class TerritoriModel {

    private TerritoriModel() {}

    /** Agent del territori català amb almenys una interacció amb la UAB. */
    record Agent(
            String uuid, String nom, String caracter, String tipusInteraccio, String tipologia,
            String municipi, String cp, String provincia, double lat, double lon,
            String ror, String nif, String metode,
            int nConv, double impConv, String anysConv,
            int nProjSoci, int nProjFin, double impProj, String anysProj,
            int totInt, String unitats) {

        /** Mateix layout que l'array d'agents que consumeix la pàgina (23 posicions). */
        List<Object> tuple() {
            List<Object> t = new ArrayList<>(23);
            t.add(uuid); t.add(nom); t.add(caracter); t.add(tipusInteraccio); t.add(tipologia);
            t.add(municipi); t.add(cp); t.add(provincia); t.add(lat); t.add(lon);
            t.add(ror); t.add(nif); t.add(metode);
            t.add(nConv); t.add(impConv); t.add(anysConv);
            t.add(nProjSoci); t.add(nProjFin); t.add(impProj); t.add(anysProj);
            t.add(totInt); t.add(unitats); t.add("");
            return t;
        }

        String municipiKey() {
            return municipi.isEmpty() ? provincia : municipi;
        }
    }

    record ConvAward(
            String awardUuid, Object pureId, String ref, String tipus, String naturalesa,
            Integer any, String inici, String fi, double impTot, double impUab,
            String titol, String desc, String convocatoria, String unitat, String tipusUnitat,
            String ambit, String ip, int nMembres) {}

    record ConvRel(ConvAward aw, int agent, String rol) {

        List<Object> row() {
            ConvAward a = aw;
            return Arrays.asList(a.awardUuid(), a.pureId(), a.ref(), a.tipus(), a.naturalesa(),
                    a.any(), a.inici(), a.fi(), a.impTot(), a.impUab(),
                    agent, rol, a.titol(), a.desc(), a.convocatoria(), a.unitat(), a.tipusUnitat(),
                    a.ambit(), a.ip(), a.nMembres());
        }
    }

    record ProjAward(
            String awardUuid, String projectUuid, Object pureId, String ref, String tipus, String naturalesa,
            Integer any, String inici, String fi, double impTot, double impUab,
            String uabLider, int totSocis, String titol, String desc, String convocatoria, String programa,
            String unitat, String tipusUnitat, String ambit, String ip, int nMembres) {}

    record ProjRel(ProjAward aw, int agent, String rol, String rolDetall, String altresSocis) {

        List<Object> row() {
            ProjAward a = aw;
            return Arrays.asList(a.awardUuid(), a.projectUuid(), a.pureId(), a.ref(), a.tipus(), a.naturalesa(),
                    a.any(), a.inici(), a.fi(), a.impTot(), a.impUab(),
                    agent, rol, rolDetall, a.uabLider(), a.totSocis(), altresSocis, a.titol(), a.desc(),
                    a.convocatoria(), a.programa(), a.unitat(), a.tipusUnitat(), a.ambit(), a.ip(), a.nMembres());
        }
    }

    record Snapshot(
            List<Agent> agents, List<ConvRel> convs, List<ProjRel> projs,
            Map<String, Object> stats, Map<String, Integer> coordOrigins,
            List<List<Object>> rc0025r, Instant builtAt) {}
}
