package com.example.demo.territori;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resum de contractes de Prestació de Serveis (Excel RC0025R, font complementària).
 * Llegeix el full amb StAX (el full té ~66 MB d'XML) i deduplica per identificador i any
 * conservant l'import màxim, igual que {@code summarize_rc0025r_prestacions}.
 */
final class Rc0025rReader {

    static final String RESOURCE = "/TABLON-Balanç de recursos - detallat (RC0025R).xlsx";

    private Rc0025rReader() {}

    /** Retorna files [any, contractes, import] per any descendent, més la fila TOTAL; buida si no hi ha l'Excel. */
    static List<List<Object>> read() {
        try (InputStream raw = Rc0025rReader.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) return List.of();
            return parse(raw);
        } catch (Exception e) {
            throw new IllegalStateException("No s'ha pogut llegir l'Excel RC0025R: " + e.getMessage(), e);
        }
    }

    private static List<List<Object>> parse(InputStream raw) throws Exception {
        // 1a passada d'un sol recorregut: el zip és seqüencial, les shared strings van abans del full.
        List<String> shared = new ArrayList<>();
        Map<Integer, Map<String, Double>> byYear = new TreeMap<>();
        boolean sheetDone = false;
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (name.equals("xl/sharedStrings.xml")) {
                    readSharedStrings(zip, shared);
                } else if (name.startsWith("xl/worksheets/") && name.endsWith(".xml") && !sheetDone) {
                    readSheet(zip, shared, byYear);
                    sheetDone = true;
                }
            }
        }
        if (!sheetDone) return List.of();

        List<List<Object>> rows = new ArrayList<>();
        int totalCnt = 0;
        double totalImp = 0.0;
        List<Integer> years = new ArrayList<>(byYear.keySet());
        for (int i = years.size() - 1; i >= 0; i--) {
            int yr = years.get(i);
            Map<String, Double> ids = byYear.get(yr);
            double tot = 0;
            for (double v : ids.values()) tot += v;
            tot = TerritoriText.round(tot, 2);
            rows.add(List.of(yr, ids.size(), tot));
            totalCnt += ids.size();
            totalImp = TerritoriText.round(totalImp + tot, 2);
        }
        rows.add(List.of("TOTAL RC0025R", totalCnt, totalImp));
        return rows;
    }

    private static XMLStreamReader reader(InputStream in) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f.createXMLStreamReader(new NoCloseInputStream(in));
    }

    private static void readSharedStrings(InputStream in, List<String> out) throws Exception {
        XMLStreamReader r = reader(in);
        StringBuilder cur = null;
        boolean inT = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("si")) cur = new StringBuilder();
                else if (n.equals("t") && cur != null) inT = true;
            } else if (ev == XMLStreamConstants.CHARACTERS && inT) {
                cur.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("t")) inT = false;
                else if (n.equals("si")) {
                    out.add(cur.toString());
                    cur = null;
                }
            }
        }
        r.close();
    }

    private static void readSheet(InputStream in, List<String> shared, Map<Integer, Map<String, Double>> byYear) throws Exception {
        XMLStreamReader r = reader(in);
        Map<String, String> cells = new HashMap<>();
        int rowNum = 0;
        String col = null;
        String type = null;
        StringBuilder text = new StringBuilder();
        boolean inV = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("row")) {
                    cells.clear();
                    String rn = r.getAttributeValue(null, "r");
                    rowNum = rn == null ? rowNum + 1 : Integer.parseInt(rn);
                } else if (n.equals("c")) {
                    String ref = r.getAttributeValue(null, "r");
                    col = ref == null ? null : ref.replaceAll("[0-9]", "");
                    type = r.getAttributeValue(null, "t");
                    text.setLength(0);
                } else if (n.equals("v") || n.equals("t")) {
                    inV = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inV) {
                text.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String n = r.getLocalName();
                if (n.equals("v") || n.equals("t")) {
                    inV = false;
                } else if (n.equals("c") && col != null) {
                    String v = text.toString();
                    if ("s".equals(type)) {
                        int idx = Integer.parseInt(v.strip());
                        v = idx < shared.size() ? shared.get(idx) : "";
                        cells.put(col, "S:" + v);
                    } else if ("inlineStr".equals(type) || "str".equals(type)) {
                        cells.put(col, "S:" + v);
                    } else if (!v.isBlank()) {
                        cells.put(col, "N:" + v.strip());
                    }
                    col = null;
                } else if (n.equals("row")) {
                    handleRow(rowNum, cells, byYear);
                }
            }
        }
        r.close();
    }

    private static void handleRow(int rowNum, Map<String, String> cells, Map<Integer, Map<String, Double>> byYear) {
        // Python: idx = rowNum - 1; es descarten idx < 6 (capçaleres)
        if (rowNum - 1 < 6) return;
        String tipus = cells.get("F");
        if (tipus == null || !tipus.equals("S:Prestació de Serveis")) return;
        String yr = cells.get("B");
        if (yr == null || !yr.startsWith("N:")) return;
        int year;
        try {
            year = (int) Double.parseDouble(yr.substring(2));
        } catch (NumberFormatException e) {
            return;
        }
        String id = idOf(cells.get("S"), rowNum - 1);
        String impCell = cells.get("V");
        double imp = 0.0;
        if (impCell != null) imp = TerritoriText.parseFloat(impCell.substring(2));
        Map<String, Double> ids = byYear.computeIfAbsent(year, k -> new HashMap<>());
        double prev = ids.getOrDefault(id, 0.0);
        if (imp > prev) ids.put(id, imp);
    }

    private static String idOf(String cell, int idx) {
        if (cell == null || cell.length() <= 2 || cell.substring(2).isBlank()) return "row_" + idx;
        String v = cell.substring(2).strip();
        if (cell.startsWith("N:")) {
            try {
                double d = Double.parseDouble(v);
                if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            } catch (NumberFormatException ignored) {
                // es deixa tal qual
            }
        }
        return v.isEmpty() ? "row_" + idx : v;
    }

    /** ZipInputStream no s'ha de tancar quan l'StAX acaba de llegir una entrada. */
    private static final class NoCloseInputStream extends java.io.FilterInputStream {
        NoCloseInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // intencionadament buit
        }
    }
}
