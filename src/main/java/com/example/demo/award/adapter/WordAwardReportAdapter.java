package com.example.demo.award.adapter;

import com.example.demo.award.model.AwardCountryReportCriteria;
import com.example.demo.award.port.AwardReportPort;
import com.example.demo.service.ResearchOutputJournalLinkService;
import org.apache.poi.xwpf.usermodel.*;
import org.bson.Document;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class WordAwardReportAdapter implements AwardReportPort {

    private final MongoTemplate mongoTemplate;
    private final ResearchOutputJournalLinkService researchOutputService;

    public WordAwardReportAdapter(MongoTemplate mongoTemplate, ResearchOutputJournalLinkService researchOutputService) {
        this.mongoTemplate = mongoTemplate;
        this.researchOutputService = researchOutputService;
    }

    @Override
    public byte[] generateCountryWordReportBytes(AwardCountryReportCriteria criteria) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        generateCountryWordReport(criteria, baos);
        return baos.toByteArray();
    }

    @Override
    public void generateCountryWordReport(AwardCountryReportCriteria criteria, OutputStream out) throws Exception {
        String countryCode = criteria.countryCode();
        String startDate = criteria.startDate() != null ? criteria.startDate() : "1968-01-01";
        String endDate = criteria.endDate();
        boolean projectes = criteria.projectes();
        boolean convenis = criteria.convenis();
        boolean beques = criteria.beques();
        boolean xarxes = criteria.xarxes();
        boolean tesis = criteria.tesis();
        boolean articles = criteria.articles();
        boolean llibres = criteria.llibres();
        boolean capitols = criteria.capitols();
        String code = countryCode.trim().toUpperCase();
        String effectiveEnd = (endDate == null || endDate.isBlank())
                ? LocalDate.now().toString() : endDate;

        Date startDateD = Date.from(LocalDate.parse(startDate).atStartOfDay(ZoneId.of("UTC")).toInstant());
        Date endDateD   = Date.from(LocalDate.parse(effectiveEnd).atStartOfDay(ZoneId.of("UTC")).toInstant());

        // --- Resolve funder UUIDs for this country ---
        Set<String> funderUuids = new HashSet<>();
        Map<String, String> funderNamesMap = new HashMap<>();
        mongoTemplate.getCollection("ExternalOrganizations")
                .find(new Document("address.country.uri",
                        new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")))
                .projection(new Document("uuid", 1).append("name", 1).append("address.country", 1))
                .forEach(org -> {
                    String uuid = org.getString("uuid");
                    if (uuid == null) return;
                    funderUuids.add(uuid);
                    Document nd = (Document) org.get("name");
                    String name = nd == null ? "" :
                            nd.containsKey("ca_ES") ? nd.getString("ca_ES") :
                            nd.containsKey("es_ES") ? nd.getString("es_ES") :
                            nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                    funderNamesMap.put(uuid, name);
                    // resolve display name for country
                });

        // Resolve country display name from first matching org
        String[] countryNameHolder = {code};
        mongoTemplate.getCollection("ExternalOrganizations")
                .find(new Document("address.country.uri",
                        new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")))
                .limit(1)
                .forEach(org -> {
                    Document addr = (Document) org.get("address");
                    if (addr == null) return;
                    Document country = (Document) addr.get("country");
                    if (country == null) return;
                    Document term = (Document) country.get("term");
                    if (term == null) return;
                    String n = term.containsKey("ca_ES") ? term.getString("ca_ES") :
                               term.containsKey("es_ES") ? term.getString("es_ES") :
                               term.containsKey("en_GB") ? term.getString("en_GB") : code;
                    if (n != null) countryNameHolder[0] = n;
                });
        String countryName = countryNameHolder[0];

        // --- Resolve person UUIDs for this country ---
        Set<String> personUuids = new HashSet<>();
        mongoTemplate.getCollection("Persons")
                .find(new Document("$or", List.of(
                        new Document("nationality.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")),
                        new Document("nationalityType.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i")),
                        new Document("nationalityTypes.uri", new Document("$regex", "/" + code.toLowerCase() + "$").append("$options", "i"))
                )))
                .projection(new Document("uuid", 1))
                .forEach(p -> { String u = p.getString("uuid"); if (u != null) personUuids.add(u); });

        // Build $or conditions
        List<Document> countryOr = new ArrayList<>();
        if (!funderUuids.isEmpty())
            countryOr.add(new Document("fundings.funder.uuid", new Document("$in", new ArrayList<>(funderUuids))));
        if (!personUuids.isEmpty())
            countryOr.add(new Document("awardHolders.person.uuid", new Document("$in", new ArrayList<>(personUuids))));

        // --- Load template ---
        InputStream tplIs = getClass().getClassLoader().getResourceAsStream("informe_pais.docx");
        try (XWPFDocument doc = (tplIs != null ? new XWPFDocument(tplIs) : new XWPFDocument())) {

            // Clear template body
            CTBody b = doc.getDocument().getBody();
            for (int i = b.sizeOfPArray() - 1; i >= 0; i--) b.removeP(i);
            for (int i = b.sizeOfTblArray() - 1; i >= 0; i--) b.removeTbl(i);

            // Fill header bookmark "pais" with country name
            for (XWPFHeader hdr : doc.getHeaderList()) {
                List<XWPFParagraph> hdrParas = new ArrayList<>();
                hdrParas.addAll(hdr.getParagraphs());
                for (XWPFTable hdrTbl : hdr.getTables()) {
                    for (XWPFTableRow hdrRow : hdrTbl.getRows()) {
                        for (XWPFTableCell hdrCell : hdrRow.getTableCells()) {
                            hdrParas.addAll(hdrCell.getParagraphs());
                        }
                    }
                }
                for (XWPFParagraph hp : hdrParas) {
                    for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBookmark bm : hp.getCTP().getBookmarkStartList()) {
                        if ("pais".equals(bm.getName())) {
                            XWPFRun hr = hp.createRun();
                            hr.setBold(true);
                            hr.setColor("FFFFFF");
                            hr.setFontFamily("Calibri");
                            hr.setFontSize(11);
                            hr.setText(countryName);
                            org.w3c.dom.Node next = bm.getDomNode().getNextSibling();
                            while (next != null && !next.getNodeName().contains("bookmarkEnd")) {
                                org.w3c.dom.Node toRemove = next;
                                next = next.getNextSibling();
                                hp.getCTP().getDomNode().removeChild(toRemove);
                            }
                            hp.getCTP().getDomNode().insertBefore(hr.getCTR().getDomNode(), bm.getDomNode());
                        }
                    }
                }
            }

            // Intro
            XWPFParagraph pDate = doc.createParagraph();
            pDate.setAlignment(ParagraphAlignment.BOTH);
            XWPFRun rDate = pDate.createRun();
            rDate.setFontFamily("Calibri"); rDate.setFontSize(9);
            rDate.setText("Data d'extracció: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));

            XWPFParagraph pPeriod = doc.createParagraph();
            pPeriod.setAlignment(ParagraphAlignment.BOTH);
            XWPFRun rPeriod = pPeriod.createRun();
            rPeriod.setFontFamily("Calibri"); rPeriod.setFontSize(9);
            rPeriod.setText("Període: " + startDate + " — " + effectiveEnd);

            XWPFParagraph pCountry = doc.createParagraph();
            pCountry.setAlignment(ParagraphAlignment.BOTH);
            XWPFRun rCountry = pCountry.createRun();
            rCountry.setFontFamily("Calibri"); rCountry.setFontSize(9);
            rCountry.setBold(true);
            rCountry.setText("País: " + countryName + " (" + code + ")");

            doc.createParagraph();
            boolean[] actRealizWritten = {false};

            // ---- Section: Projectes (Ajudes competitives) ----
            if (projectes && !countryOr.isEmpty()) {
                Document projFilter = new Document("workflow.step", "validated")
                        .append("categoria", new Document("$regex", "^Ajudes competitives"))
                        .append("actualPeriod.startDate", new Document("$lte", endDateD))
                        .append("$and", Arrays.asList(
                                new Document("$or", countryOr),
                                new Document("$or", Arrays.asList(
                                        new Document("actualPeriod.endDate", null),
                                        new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                                ))
                        ));

                List<Document> projRaw = mongoTemplate.getCollection("Awards")
                        .find(projFilter)
                        .sort(new Document("actualPeriod.startDate", -1))
                        .into(new ArrayList<>());

                // Batch-fetch funder names
                Set<String> pfUuids = new HashSet<>();
                for (Document aw : projRaw) {
                    List<Document> awf = castList(aw.get("fundings"));
                    if (awf != null) for (Document f : awf) { Document fd = (Document) f.get("funder"); if (fd != null && fd.getString("uuid") != null) pfUuids.add(fd.getString("uuid")); }
                }
                if (!pfUuids.isEmpty()) {
                    mongoTemplate.getCollection("ExternalOrganizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(pfUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES") : nd.containsKey("es_ES") ? nd.getString("es_ES") : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                funderNamesMap.put(org.getString("uuid"), n);
                            });
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph secTitle = doc.createParagraph();
                secTitle.setStyle("Ttol2");
                secTitle.createRun().setText("Projectes de recerca");
                doc.createParagraph();

                int idx = 1;
                for (Document aw : projRaw) {
                    writeAwardBlock(doc, aw, idx++, funderNamesMap);
                }
                XWPFParagraph secFoot = doc.createParagraph();
                XWPFRun fr = secFoot.createRun();
                fr.setFontFamily("Calibri"); fr.setFontSize(9); fr.setItalic(true);
                fr.setText("Total projectes: " + projRaw.size());
                doc.createParagraph();
            }

            // ---- Section: Convenis ----
            if (convenis && !countryOr.isEmpty()) {
                Document convFilter = new Document("workflow.step", "validated")
                        .append("type.term.ca_ES", "Concessió conveni")
                        .append("actualPeriod.startDate", new Document("$lte", endDateD))
                        .append("$and", Arrays.asList(
                                new Document("$or", countryOr),
                                new Document("$or", Arrays.asList(
                                        new Document("actualPeriod.endDate", null),
                                        new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                                ))
                        ));

                List<Document> convRaw = mongoTemplate.getCollection("Awards")
                        .find(convFilter)
                        .sort(new Document("actualPeriod.startDate", -1))
                        .into(new ArrayList<>());

                Set<String> cfUuids = new HashSet<>();
                for (Document cv : convRaw) {
                    List<Document> cvf = castList(cv.get("fundings"));
                    if (cvf != null) for (Document f : cvf) { Document fd = (Document) f.get("funder"); if (fd != null && fd.getString("uuid") != null) cfUuids.add(fd.getString("uuid")); }
                }
                if (!cfUuids.isEmpty()) {
                    mongoTemplate.getCollection("ExternalOrganizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(cfUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES") : nd.containsKey("es_ES") ? nd.getString("es_ES") : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                funderNamesMap.put(org.getString("uuid"), n);
                            });
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph secTitle = doc.createParagraph();
                secTitle.setStyle("Ttol2");
                secTitle.createRun().setText("Convenis");
                doc.createParagraph();

                int idx = 1;
                for (Document cv : convRaw) {
                    writeAwardBlock(doc, cv, idx++, funderNamesMap);
                }
                XWPFParagraph secFoot = doc.createParagraph();
                XWPFRun fr = secFoot.createRun();
                fr.setFontFamily("Calibri"); fr.setFontSize(9); fr.setItalic(true);
                fr.setText("Total convenis: " + convRaw.size());
            }

            // ---- Section: Beques ----
            if (beques && !countryOr.isEmpty()) {
                Document becaFilter = new Document("workflow.step", "validated")
                        .append("type.term.ca_ES", "Beques")
                        .append("actualPeriod.startDate", new Document("$lte", endDateD))
                        .append("$and", Arrays.asList(
                                new Document("$or", countryOr),
                                new Document("$or", Arrays.asList(
                                        new Document("actualPeriod.endDate", null),
                                        new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                                ))
                        ));

                List<Document> becaRaw = mongoTemplate.getCollection("Awards")
                        .find(becaFilter)
                        .sort(new Document("actualPeriod.startDate", -1))
                        .into(new ArrayList<>());

                Set<String> bfUuids = new HashSet<>();
                for (Document bw : becaRaw) {
                    List<Document> bwf = castList(bw.get("fundings"));
                    if (bwf != null) for (Document f : bwf) { Document fd = (Document) f.get("funder"); if (fd != null && fd.getString("uuid") != null) bfUuids.add(fd.getString("uuid")); }
                }
                if (!bfUuids.isEmpty()) {
                    mongoTemplate.getCollection("ExternalOrganizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(bfUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES") : nd.containsKey("es_ES") ? nd.getString("es_ES") : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                funderNamesMap.put(org.getString("uuid"), n);
                            });
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph secTitle = doc.createParagraph();
                secTitle.setStyle("Ttol2");
                secTitle.createRun().setText("Beques");
                doc.createParagraph();

                int idx = 1;
                for (Document bw : becaRaw) {
                    writeAwardBlock(doc, bw, idx++, funderNamesMap);
                }
                XWPFParagraph secFoot = doc.createParagraph();
                XWPFRun fr2 = secFoot.createRun();
                fr2.setFontFamily("Calibri"); fr2.setFontSize(9); fr2.setItalic(true);
                fr2.setText("Total beques: " + becaRaw.size());
            }

            // ---- Section: Xarxes ----
            if (xarxes && !countryOr.isEmpty()) {
                Document xarxesFilter = new Document("workflow.step", "validated")
                        .append("type.term.ca_ES", "Grups i Xarxes de Recerca")
                        .append("actualPeriod.startDate", new Document("$lte", endDateD))
                        .append("$and", Arrays.asList(
                                new Document("$or", countryOr),
                                new Document("$or", Arrays.asList(
                                        new Document("actualPeriod.endDate", null),
                                        new Document("actualPeriod.endDate", new Document("$gte", startDateD))
                                ))
                        ));

                List<Document> xarxesRaw = mongoTemplate.getCollection("Awards")
                        .find(xarxesFilter)
                        .sort(new Document("actualPeriod.startDate", -1))
                        .into(new ArrayList<>());

                Set<String> xfUuids = new HashSet<>();
                for (Document xw : xarxesRaw) {
                    List<Document> xwf = castList(xw.get("fundings"));
                    if (xwf != null) for (Document f : xwf) { Document fd = (Document) f.get("funder"); if (fd != null && fd.getString("uuid") != null) xfUuids.add(fd.getString("uuid")); }
                }
                if (!xfUuids.isEmpty()) {
                    mongoTemplate.getCollection("ExternalOrganizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(xfUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" : nd.containsKey("ca_ES") ? nd.getString("ca_ES") : nd.containsKey("es_ES") ? nd.getString("es_ES") : nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                funderNamesMap.put(org.getString("uuid"), n);
                            });
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph secTitle = doc.createParagraph();
                secTitle.setStyle("Ttol2");
                secTitle.createRun().setText("Grups i Xarxes de Recerca");
                doc.createParagraph();

                int idx = 1;
                for (Document xw : xarxesRaw) {
                    writeAwardBlock(doc, xw, idx++, funderNamesMap);
                }
                XWPFParagraph secFoot = doc.createParagraph();
                XWPFRun fr3 = secFoot.createRun();
                fr3.setFontFamily("Calibri"); fr3.setFontSize(9); fr3.setItalic(true);
                fr3.setText("Total xarxes: " + xarxesRaw.size());
            }

            // ---- Section: Tesis doctorals ----
            if (tesis) {
                int startYear = LocalDate.parse(startDate).getYear();
                int endYear   = LocalDate.parse(effectiveEnd).getYear();

                List<Document> thesisOr = new ArrayList<>();
                if (!funderUuids.isEmpty()) {
                    thesisOr.add(new Document("awardingInstitutions.externalOrganizationRef.uuid",
                            new Document("$in", new ArrayList<>(funderUuids))));
                    // direct relation thesis → external orgs (equiv. relationExternalorganisationsStudentthesises)
                    thesisOr.add(new Document("supervisorOrganizations.uuid",
                            new Document("$in", new ArrayList<>(funderUuids))));
                    // supervisor affiliated with an org from that country
                    thesisOr.add(new Document("supervisors.organizations.uuid",
                            new Document("$in", new ArrayList<>(funderUuids))));
                }
                if (!personUuids.isEmpty()) {
                    thesisOr.add(new Document("supervisors.person.uuid",
                            new Document("$in", new ArrayList<>(personUuids))));
                }

                Document thesisTypeFilter = new Document("$or", Arrays.asList(
                        new Document("type.term.es_ES", new Document("$regex", "tesis doctoral").append("$options", "i")),
                        new Document("type.term.ca_ES", new Document("$regex", "tesi doctoral").append("$options", "i")),
                        new Document("type.term.en_GB", new Document("$regex", "doctoral thesis|phd thesis").append("$options", "i"))
                ));
                List<Document> andClauses = new ArrayList<>();
                andClauses.add(thesisTypeFilter);
                if (!thesisOr.isEmpty())
                    andClauses.add(new Document("$or", thesisOr));
                Document thesisFilter = new Document("workflow.step", "approved")
                        .append("awardDate.year", new Document("$gte", startYear).append("$lte", endYear))
                        .append("$and", andClauses);

                List<Document> thesesRaw = mongoTemplate.getCollection("StudentTheses")
                        .find(thesisFilter)
                        .sort(new Document("awardDate.year", -1).append("awardDate.month", -1).append("awardDate.day", -1))
                        .into(new ArrayList<>());

                // Batch-fetch managing organization names
                Map<String, String> orgNamesMap = new HashMap<>();
                Set<String> managingUuids = new HashSet<>();
                for (Document th : thesesRaw) {
                    Document mo = (Document) th.get("managingOrganization");
                    if (mo != null && mo.getString("uuid") != null) managingUuids.add(mo.getString("uuid"));
                }
                if (!managingUuids.isEmpty()) {
                    mongoTemplate.getCollection("Organizations")
                            .find(new Document("uuid", new Document("$in", new ArrayList<>(managingUuids))))
                            .projection(new Document("uuid", 1).append("name", 1))
                            .forEach(org -> {
                                Document nd = (Document) org.get("name");
                                String n = nd == null ? "" :
                                        nd.containsKey("ca_ES") ? nd.getString("ca_ES") :
                                        nd.containsKey("es_ES") ? nd.getString("es_ES") :
                                        nd.containsKey("en_GB") ? nd.getString("en_GB") : "";
                                orgNamesMap.put(org.getString("uuid"), n);
                            });
                }

                // Group by year (already sorted desc)
                LinkedHashMap<Integer, List<Document>> thByYear = new LinkedHashMap<>();
                for (Document th : thesesRaw) {
                    Document ad = (Document) th.get("awardDate");
                    int yr = (ad != null && ad.get("year") != null) ? ((Number) ad.get("year")).intValue() : 0;
                    thByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(th);
                }

                if (!actRealizWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grp = doc.createParagraph();
                    grp.setStyle("Ttol1"); grp.createRun().setText("Activitats realitzades");
                    doc.createParagraph();
                    actRealizWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph tSecTitle = doc.createParagraph();
                tSecTitle.setStyle("Ttol2");
                tSecTitle.createRun().setText("Tesis doctorals");
                doc.createParagraph();

                int tIdx = 1;
                for (Map.Entry<Integer, List<Document>> yearEntry : thByYear.entrySet()) {
                    // Year heading
                    XWPFParagraph yearPara = doc.createParagraph();
                    yearPara.setAlignment(ParagraphAlignment.BOTH);
                    XWPFRun yearRun = yearPara.createRun();
                    yearRun.setBold(true); yearRun.setFontFamily("Calibri"); yearRun.setFontSize(9);
                    yearRun.setText(yearEntry.getKey() > 0 ? String.valueOf(yearEntry.getKey()) : "(any desconegut)");

                    for (Document th : yearEntry.getValue()) {
                        Document thTitleDoc = (Document) th.get("title");
                        String thTitle = thTitleDoc != null ? thTitleDoc.getString("value") : "";
                        if (thTitle == null || thTitle.isBlank()) thTitle = "(sense títol)";

                        // Author: first contributor
                        String autor = "";
                        List<Document> thContribs = castList(th.get("contributors"));
                        if (thContribs != null) {
                            autor = thContribs.stream()
                                    .map(c -> { Document n = (Document) c.get("name"); if (n == null) return ""; String ln = n.getString("lastName"); String fn = n.getString("firstName"); return (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : ""); })
                                    .filter(s -> !s.isBlank()).findFirst().orElse("");
                        }

                        // Date DD-MM-YYYY
                        String dataLectura = "";
                        Document thAwardDate = (Document) th.get("awardDate");
                        if (thAwardDate != null) {
                            int dy = thAwardDate.get("day") != null ? ((Number) thAwardDate.get("day")).intValue() : 1;
                            int mo = thAwardDate.get("month") != null ? ((Number) thAwardDate.get("month")).intValue() : 1;
                            int yr2 = thAwardDate.get("year") != null ? ((Number) thAwardDate.get("year")).intValue() : 0;
                            if (yr2 > 0) dataLectura = String.format("%02d-%02d-%04d", dy, mo, yr2);
                        }

                        // Supervisors joined with " & "
                        List<String> directors = new ArrayList<>();
                        List<Document> thSupervisors = castList(th.get("supervisors"));
                        if (thSupervisors != null) {
                            for (Document sv : thSupervisors) {
                                Document n = (Document) sv.get("name");
                                if (n == null) continue;
                                String ln = n.getString("lastName"); String fn = n.getString("firstName");
                                String full = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                                if (!full.isBlank()) directors.add(full);
                            }
                        }

                        // Managing organization name
                        String centre = "";
                        Document mo = (Document) th.get("managingOrganization");
                        if (mo != null && mo.getString("uuid") != null) {
                            centre = orgNamesMap.getOrDefault(mo.getString("uuid"), "");
                        }

                        XWPFParagraph tTitlePara = doc.createParagraph();
                        tTitlePara.setAlignment(ParagraphAlignment.BOTH);
                        XWPFRun tTitleRun = tTitlePara.createRun();
                        tTitleRun.setBold(true); tTitleRun.setFontFamily("Calibri"); tTitleRun.setFontSize(9);
                        tTitleRun.setText(tIdx + ".- " + thTitle);

                        wordLabelValue(doc, "Autor", autor);
                        wordLabelValue(doc, "Data de lectura", dataLectura);
                        wordLabelValue(doc, "Supervisors", String.join(" & ", directors));
                        if (!centre.isBlank()) wordLabelValue(doc, "Centre de lectura", centre);
                        doc.createParagraph();
                        tIdx++;
                    }
                }

                XWPFParagraph tSecFoot = doc.createParagraph();
                XWPFRun fr4 = tSecFoot.createRun();
                fr4.setFontFamily("Calibri"); fr4.setFontSize(9); fr4.setItalic(true);
                fr4.setText("Total tesis doctorals: " + thesesRaw.size());
            }

            boolean[] prodCientWritten = {false};

            // ---- Section: Articles ----
            if (articles && !personUuids.isEmpty()) {
                int startYear = LocalDate.parse(startDate).getYear();
                int endYear   = LocalDate.parse(effectiveEnd).getYear();

                Document artFilter = new Document("workflow.step", "approved")
                        .append("$or", Arrays.asList(
                                new Document("publicationDate.year",
                                        new Document("$gte", startYear).append("$lte", endYear)),
                                new Document("$and", Arrays.asList(
                                        new Document("publicationDate.year", new Document("$exists", false)),
                                        new Document("submissionYear",
                                                new Document("$gte", startYear).append("$lte", endYear))
                                ))
                        ))
                        .append("$or", Arrays.asList(
                                new Document("contributors.person.uuid",
                                        new Document("$in", new ArrayList<>(personUuids))),
                                new Document("contributors.externalPerson.uuid",
                                        new Document("$in", new ArrayList<>(personUuids)))
                        ));

                // Build filter correctly: year AND (contributor IN personUuids)
                Document yearFilter = new Document("$or", Arrays.asList(
                        new Document("publicationDate.year",
                                new Document("$gte", startYear).append("$lte", endYear)),
                        new Document("$and", Arrays.asList(
                                new Document("publicationDate.year", new Document("$exists", false)),
                                new Document("submissionYear",
                                        new Document("$gte", startYear).append("$lte", endYear))
                        ))
                ));
                Document contribFilter = new Document("$or", Arrays.asList(
                        new Document("contributors.person.uuid",
                                new Document("$in", new ArrayList<>(personUuids))),
                        new Document("contributors.externalPerson.uuid",
                                new Document("$in", new ArrayList<>(personUuids)))
                ));

                Document articleFilter = new Document("workflow.step", "approved")
                        .append("$and", Arrays.asList(yearFilter, contribFilter));

                List<Document> articlesRaw = mongoTemplate.getCollection("Researchoutputs")
                        .find(articleFilter)
                        .into(new ArrayList<>());

                // Sort by year desc, month desc, day desc
                articlesRaw.sort((artX, artY) -> {
                    Document pdX = artX.get("publicationDate") instanceof Document ? (Document) artX.get("publicationDate") : new Document();
                    Document pdY = artY.get("publicationDate") instanceof Document ? (Document) artY.get("publicationDate") : new Document();
                    int yX = pdX.containsKey("year") ? pdX.getInteger("year", 0) : artX.getInteger("submissionYear", 0);
                    int yY = pdY.containsKey("year") ? pdY.getInteger("year", 0) : artY.getInteger("submissionYear", 0);
                    if (yY != yX) return Integer.compare(yY, yX);
                    int mX = pdX.getInteger("month", 0);
                    int mY = pdY.getInteger("month", 0);
                    if (mY != mX) return Integer.compare(mY, mX);
                    return Integer.compare(pdY.getInteger("day", 0), pdX.getInteger("day", 0));
                });

                // Group by year preserving sort order (year desc)
                LinkedHashMap<Integer, List<Document>> artByYear = new LinkedHashMap<>();
                for (Document pub : articlesRaw) {
                    Document pd = pub.get("publicationDate") instanceof Document ? (Document) pub.get("publicationDate") : new Document();
                    int yr = pd.containsKey("year") ? pd.getInteger("year", 0) : pub.getInteger("submissionYear", 0);
                    artByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(pub);
                }

                if (!prodCientWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph artGrpTitle = doc.createParagraph();
                    artGrpTitle.setStyle("Ttol1");
                    artGrpTitle.createRun().setText("Producció científica");
                    doc.createParagraph();
                    prodCientWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph artSecTitle = doc.createParagraph();
                artSecTitle.setStyle("Ttol2");
                artSecTitle.createRun().setText("Articles");
                doc.createParagraph();

                for (Map.Entry<Integer, List<Document>> artEntry : artByYear.entrySet()) {
                    // Year heading
                    XWPFParagraph yearPara = doc.createParagraph();
                    yearPara.setAlignment(ParagraphAlignment.BOTH);
                    XWPFRun yearRun = yearPara.createRun();
                    yearRun.setBold(true); yearRun.setFontFamily("Calibri"); yearRun.setFontSize(9);
                    yearRun.setText(artEntry.getKey() > 0 ? String.valueOf(artEntry.getKey()) : "(any desconegut)");

                    for (Document pub : artEntry.getValue()) {
                        String apa = researchOutputService.formatApaForDocument(pub);
                        XWPFParagraph artPara = doc.createParagraph();
                        artPara.setAlignment(ParagraphAlignment.BOTH);
                        XWPFRun artRun = artPara.createRun();
                        artRun.setFontFamily("Calibri"); artRun.setFontSize(9);
                        artRun.setText(apa != null ? apa : "(sense dades)");
                        doc.createParagraph();
                    }
                    doc.createParagraph();
                }

                XWPFParagraph artSecFoot = doc.createParagraph();
                XWPFRun fr5 = artSecFoot.createRun();
                fr5.setFontFamily("Calibri"); fr5.setFontSize(9); fr5.setItalic(true);
                fr5.setText("Total articles: " + articlesRaw.size());
            }

            // ---- Section: Llibres ----
            if (llibres && !personUuids.isEmpty()) {
                int llStartYear = LocalDate.parse(startDate).getYear();
                int llEndYear   = LocalDate.parse(effectiveEnd).getYear();

                Document llYearFilter = new Document("$or", Arrays.asList(
                        new Document("publicationDate.year",
                                new Document("$gte", llStartYear).append("$lte", llEndYear)),
                        new Document("$and", Arrays.asList(
                                new Document("publicationDate.year", new Document("$exists", false)),
                                new Document("submissionYear",
                                        new Document("$gte", llStartYear).append("$lte", llEndYear))
                        ))
                ));
                Document llContribFilter = new Document("$or", Arrays.asList(
                        new Document("contributors.person.uuid",
                                new Document("$in", new ArrayList<>(personUuids))),
                        new Document("contributors.externalPerson.uuid",
                                new Document("$in", new ArrayList<>(personUuids)))
                ));
                Document llTypeFilter = new Document("$or", Arrays.asList(
                        new Document("type.uri", new Document("$regex", "researchoutputtypes/book/").append("$options", "i")),
                        new Document("type.term.en_GB", new Document("$regex", "^authored book$|^edited book$|^book$|^monograph$").append("$options", "i")),
                        new Document("type.term.ca_ES", new Document("$regex", "^llibre").append("$options", "i")),
                        new Document("type.term.es_ES", new Document("$regex", "^libro").append("$options", "i"))
                ));

                Document llibresFilter = new Document("workflow.step", "approved")
                        .append("$and", Arrays.asList(llYearFilter, llContribFilter, llTypeFilter));

                List<Document> llibresRaw = mongoTemplate.getCollection("Researchoutputs")
                        .find(llibresFilter)
                        .into(new ArrayList<>());

                llibresRaw.sort((docA, docB) -> {
                    Document pdA = docA.get("publicationDate") instanceof Document ? (Document) docA.get("publicationDate") : new Document();
                    Document pdB = docB.get("publicationDate") instanceof Document ? (Document) docB.get("publicationDate") : new Document();
                    int yA = pdA.containsKey("year") ? pdA.getInteger("year", 0) : docA.getInteger("submissionYear", 0);
                    int yB = pdB.containsKey("year") ? pdB.getInteger("year", 0) : docB.getInteger("submissionYear", 0);
                    if (yB != yA) return Integer.compare(yB, yA);
                    int mA = pdA.getInteger("month", 0); int mB = pdB.getInteger("month", 0);
                    if (mB != mA) return Integer.compare(mB, mA);
                    return Integer.compare(pdB.getInteger("day", 0), pdA.getInteger("day", 0));
                });

                LinkedHashMap<Integer, List<Document>> llibresByYear = new LinkedHashMap<>();
                for (Document pub : llibresRaw) {
                    Document pd = pub.get("publicationDate") instanceof Document ? (Document) pub.get("publicationDate") : new Document();
                    int yr = pd.containsKey("year") ? pd.getInteger("year", 0) : pub.getInteger("submissionYear", 0);
                    llibresByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(pub);
                }

                if (!prodCientWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grpTitleLL = doc.createParagraph();
                    grpTitleLL.setStyle("Ttol1"); grpTitleLL.createRun().setText("Producció científica");
                    doc.createParagraph();
                    prodCientWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph llSecTitle = doc.createParagraph();
                llSecTitle.setStyle("Ttol2");
                llSecTitle.createRun().setText("Llibres");
                doc.createParagraph();

                for (Map.Entry<Integer, List<Document>> llEntry : llibresByYear.entrySet()) {
                    XWPFParagraph llYearPara = doc.createParagraph();
                    llYearPara.setAlignment(ParagraphAlignment.BOTH);
                    XWPFRun llYearRun = llYearPara.createRun();
                    llYearRun.setBold(true); llYearRun.setFontFamily("Calibri"); llYearRun.setFontSize(9);
                    llYearRun.setText(llEntry.getKey() > 0 ? String.valueOf(llEntry.getKey()) : "(any desconegut)");

                    for (Document pub : llEntry.getValue()) {
                        String apa = researchOutputService.formatApaForDocument(pub);
                        XWPFParagraph llPara = doc.createParagraph();
                        llPara.setAlignment(ParagraphAlignment.BOTH);
                        XWPFRun llRun = llPara.createRun();
                        llRun.setFontFamily("Calibri"); llRun.setFontSize(9);
                        llRun.setText(apa != null ? apa : "(sense dades)");
                        doc.createParagraph();
                    }
                    doc.createParagraph();
                }

                XWPFParagraph llSecFoot = doc.createParagraph();
                XWPFRun frLL = llSecFoot.createRun();
                frLL.setFontFamily("Calibri"); frLL.setFontSize(9); frLL.setItalic(true);
                frLL.setText("Total llibres: " + llibresRaw.size());
            }

            // ---- Section: Capítols de llibre ----
            if (capitols && !personUuids.isEmpty()) {
                int capStartYear = LocalDate.parse(startDate).getYear();
                int capEndYear   = LocalDate.parse(effectiveEnd).getYear();

                Document capYearFilter = new Document("$or", Arrays.asList(
                        new Document("publicationDate.year",
                                new Document("$gte", capStartYear).append("$lte", capEndYear)),
                        new Document("$and", Arrays.asList(
                                new Document("publicationDate.year", new Document("$exists", false)),
                                new Document("submissionYear",
                                        new Document("$gte", capStartYear).append("$lte", capEndYear))
                        ))
                ));
                Document capContribFilter = new Document("$or", Arrays.asList(
                        new Document("contributors.person.uuid",
                                new Document("$in", new ArrayList<>(personUuids))),
                        new Document("contributors.externalPerson.uuid",
                                new Document("$in", new ArrayList<>(personUuids)))
                ));
                Document capTypeFilter = new Document("$or", Arrays.asList(
                        new Document("type.uri", new Document("$regex", "contributiontobookanthology").append("$options", "i")),
                        new Document("type.term.en_GB", new Document("$regex", "chapter|contribution to book").append("$options", "i")),
                        new Document("type.term.ca_ES", new Document("$regex", "cap.tol.*llibre|contribuci.*llibre").append("$options", "i")),
                        new Document("type.term.es_ES", new Document("$regex", "cap.tulo.*libro|contribuci.*libro").append("$options", "i"))
                ));

                Document capitolsFilter = new Document("workflow.step", "approved")
                        .append("$and", Arrays.asList(capYearFilter, capContribFilter, capTypeFilter));

                List<Document> capitolsRaw = mongoTemplate.getCollection("Researchoutputs")
                        .find(capitolsFilter)
                        .into(new ArrayList<>());

                capitolsRaw.sort((docA, docB) -> {
                    Document pdA = docA.get("publicationDate") instanceof Document ? (Document) docA.get("publicationDate") : new Document();
                    Document pdB = docB.get("publicationDate") instanceof Document ? (Document) docB.get("publicationDate") : new Document();
                    int yA = pdA.containsKey("year") ? pdA.getInteger("year", 0) : docA.getInteger("submissionYear", 0);
                    int yB = pdB.containsKey("year") ? pdB.getInteger("year", 0) : docB.getInteger("submissionYear", 0);
                    if (yB != yA) return Integer.compare(yB, yA);
                    int mA = pdA.getInteger("month", 0); int mB = pdB.getInteger("month", 0);
                    if (mB != mA) return Integer.compare(mB, mA);
                    return Integer.compare(pdB.getInteger("day", 0), pdA.getInteger("day", 0));
                });

                LinkedHashMap<Integer, List<Document>> capitolsByYear = new LinkedHashMap<>();
                for (Document pub : capitolsRaw) {
                    Document pd = pub.get("publicationDate") instanceof Document ? (Document) pub.get("publicationDate") : new Document();
                    int yr = pd.containsKey("year") ? pd.getInteger("year", 0) : pub.getInteger("submissionYear", 0);
                    capitolsByYear.computeIfAbsent(yr, k -> new ArrayList<>()).add(pub);
                }

                if (!prodCientWritten[0]) {
                    doc.createParagraph();
                    XWPFParagraph grpTitleCap = doc.createParagraph();
                    grpTitleCap.setStyle("Ttol1"); grpTitleCap.createRun().setText("Producció científica");
                    doc.createParagraph();
                    prodCientWritten[0] = true;
                }
                doc.createParagraph();
                XWPFParagraph capSecTitle = doc.createParagraph();
                capSecTitle.setStyle("Ttol2");
                capSecTitle.createRun().setText("Capítols de llibre");
                doc.createParagraph();

                for (Map.Entry<Integer, List<Document>> capEntry : capitolsByYear.entrySet()) {
                    XWPFParagraph capYearPara = doc.createParagraph();
                    capYearPara.setAlignment(ParagraphAlignment.BOTH);
                    XWPFRun capYearRun = capYearPara.createRun();
                    capYearRun.setBold(true); capYearRun.setFontFamily("Calibri"); capYearRun.setFontSize(9);
                    capYearRun.setText(capEntry.getKey() > 0 ? String.valueOf(capEntry.getKey()) : "(any desconegut)");

                    for (Document pub : capEntry.getValue()) {
                        String apa = researchOutputService.formatApaForDocument(pub);
                        XWPFParagraph capPara = doc.createParagraph();
                        capPara.setAlignment(ParagraphAlignment.BOTH);
                        XWPFRun capRun = capPara.createRun();
                        capRun.setFontFamily("Calibri"); capRun.setFontSize(9);
                        capRun.setText(apa != null ? apa : "(sense dades)");
                        doc.createParagraph();
                    }
                    doc.createParagraph();
                }

                XWPFParagraph capSecFoot = doc.createParagraph();
                XWPFRun frCap = capSecFoot.createRun();
                frCap.setFontFamily("Calibri"); frCap.setFontSize(9); frCap.setItalic(true);
                frCap.setText("Total capítols de llibre: " + capitolsRaw.size());
            }

            doc.write(out);
        }
    }

    private void writeAwardBlock(XWPFDocument doc, Document aw, int idx, Map<String, String> funderNamesMap) {
        String awTitle = nestedStr(aw, "title", "ca_ES");
        if (awTitle.isEmpty()) awTitle = nestedStr(aw, "title", "es_ES");
        if (awTitle.isEmpty()) awTitle = nestedStr(aw, "title", "en_GB");

        List<String> ipOnlyList = new ArrayList<>();
        List<String> coipList = new ArrayList<>();
        List<String> equip = new ArrayList<>();
        List<Document> holders = castList(aw.get("awardHolders"));
        if (holders != null) {
            for (Document h : holders) {
                Document hn = (Document) h.get("name");
                Document rd = (Document) h.get("role");
                String fn = hn != null ? hn.getString("firstName") : "";
                String ln = hn != null ? hn.getString("lastName") : "";
                String full = (ln != null ? ln : "") + (fn != null && !fn.isEmpty() ? ", " + fn : "");
                boolean isIp = false;
                boolean isPureIp = false;
                if (rd != null) {
                    Document td = (Document) rd.get("term");
                    if (td != null) {
                        String roleCa = td.getString("ca_ES") != null ? td.getString("ca_ES") : "";
                        String roleEn = td.getString("en_GB") != null ? td.getString("en_GB") : "";
                        isIp = roleCa.contains("Principal") || roleEn.contains("Principal");
                        isPureIp = isIp && !roleCa.toLowerCase().startsWith("co");
                    }
                }
                if (isIp && !full.isBlank()) {
                    if (isPureIp) ipOnlyList.add(full); else coipList.add(full);
                } else if (!full.isBlank()) {
                    equip.add(full);
                }
            }
        }
        List<String> ipAllList = new ArrayList<>(ipOnlyList);
        ipAllList.addAll(coipList);
        String ip = String.join(" & ", ipAllList);

        String funderName = "";
        double totalImport = 0.0;
        List<Document> fundings = castList(aw.get("fundings"));
        if (fundings != null && !fundings.isEmpty()) {
            Document fd = (Document) fundings.get(0).get("funder");
            if (fd != null) funderName = funderNamesMap.getOrDefault(fd.getString("uuid"), "");
            for (Document f : fundings) {
                List<Document> cols = castList(f.get("fundingCollaborators"));
                if (cols != null) for (Document col : cols) {
                    Document part = (Document) col.get("institutionalPart");
                    if (part != null) { Object val = part.get("value"); if (val instanceof Number n) totalImport += n.doubleValue(); }
                }
            }
        }

        Document period = (Document) aw.get("actualPeriod");
        String awStart = period != null ? wordFormatDate(period.get("startDate")) : "";
        String awEnd   = period != null ? wordFormatDate(period.get("endDate")) : "";

        String codiOficial = "";
        List<Document> identifiers = castList(aw.get("identifiers"));
        if (identifiers != null) {
            codiOficial = identifiers.stream()
                    .filter(id -> { Document t = (Document) id.get("type"); return t != null && "/dk/atira/pure/upm/classifiedsource/referencecode".equals(t.getString("uri")); })
                    .map(id -> id.getString("id") != null ? id.getString("id") : id.getString("value"))
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst().orElse("");
        }

        XWPFParagraph titlePara = doc.createParagraph();
        titlePara.setAlignment(ParagraphAlignment.BOTH);
        XWPFRun tr = titlePara.createRun();
        tr.setBold(true); tr.setFontFamily("Calibri"); tr.setFontSize(9);
        tr.setText(idx + ".- " + awTitle);

        wordLabelValue(doc, "Investigador principal", ip);
        wordLabelValue(doc, "Equip investigador", String.join("; ", equip));
        wordLabelValue(doc, "Entitat finançadora", funderName);
        if (totalImport > 0) wordLabelValue(doc, "Import", String.format(Locale.GERMAN, "%,.2f €", totalImport));
        wordLabelValue(doc, "Data d'inici/fi", awStart + " → " + awEnd);
        if (!codiOficial.isBlank()) wordLabelValue(doc, "Codi oficial", codiOficial);
        doc.createParagraph();
    }

    private void wordLabelValue(XWPFDocument doc, String label, String value) {
        if (value == null || value.isBlank()) return;
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.BOTH);
        XWPFRun lbl = p.createRun();
        lbl.setItalic(true); lbl.setFontFamily("Calibri"); lbl.setFontSize(9);
        lbl.setText(label + ": ");
        XWPFRun val = p.createRun();
        val.setFontFamily("Calibri"); val.setFontSize(9);
        val.setText(value);
    }

    private String wordFormatDate(Object raw) {
        if (raw == null) return "";
        if (raw instanceof Date d) {
            LocalDate ld = d.toInstant().atZone(ZoneId.of("UTC")).toLocalDate();
            return String.format("%02d-%02d-%04d", ld.getDayOfMonth(), ld.getMonthValue(), ld.getYear());
        }
        String s = raw.toString();
        if (s.isBlank()) return "";
        try {
            String datePart = s.contains("T") ? s.substring(0, s.indexOf('T')) : s.trim();
            String[] parts = datePart.split("-");
            if (parts.length == 3) return parts[2] + "-" + parts[1] + "-" + parts[0];
        } catch (Exception ignored) {}
        return s;
    }

    private String nestedStr(Document doc, String... keys) {
        Object cur = doc;
        for (String k : keys) {
            if (!(cur instanceof Document d)) return "";
            cur = d.get(k);
            if (cur == null) return "";
        }
        return cur.toString();
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> castList(Object o) {
        return o instanceof List ? (List<T>) o : null;
    }

    // ---- helpers ----

    private String countryCodeFromUri(String uri) {
        if (uri == null) return null;
        String cleaned = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        int idx = cleaned.lastIndexOf('/');
        if (idx < 0) return null;
        String seg = cleaned.substring(idx + 1);
        return seg.length() == 2 ? seg.toUpperCase() : null;
    }

    private String extractPersonCountryCode(Document p) {
        String code = countryCodeFromUri(getDocPath(p, "nationality", "uri"));
        if (code != null) return code;
        code = countryCodeFromUri(getDocPath(p, "nationalityType", "uri"));
        if (code != null) return code;
        Object nt = p.get("nationalityTypes");
        if (nt instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Document d) {
            code = countryCodeFromUri(d.getString("uri"));
        }
        return code;
    }

    private String getDocPath(Document doc, String... keys) {
        Object cur = doc;
        for (String key : keys) {
            if (!(cur instanceof Document d)) return null;
            cur = d.get(key);
        }
        return cur instanceof String s ? s : null;
    }


}
