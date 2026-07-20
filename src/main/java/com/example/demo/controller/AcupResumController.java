package com.example.demo.controller;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api/acup-resum")
@CrossOrigin(origins = "*")
public class AcupResumController {

    private static final Map<String, String> UNIS = new LinkedHashMap<>();
    static {
        UNIS.put("UB", "UNIVERSIDAD DE BARCELONA");
        UNIS.put("UAB", "UNIVERSIDAD AUTONOMA DE BARCELONA");
        UNIS.put("UPC", "UNIVERSITAT POLITECNICA DE CATALUNYA");
        UNIS.put("UPF", "UNIVERSITAT POMPEU FABRA CCT");
        UNIS.put("UdL", "UNIVERSIDAD DE LLEIDA");
        UNIS.put("UdG", "UNIVERSITAT DE GIRONA");
        UNIS.put("URV", "UNIVERSITAT ROVIRA I VIRGILI");
    }

    private static final Map<String, Double> ACUP_PCT = new LinkedHashMap<>();
    static {
        ACUP_PCT.put("UB", 0.33);
        ACUP_PCT.put("UAB", 0.19);
        ACUP_PCT.put("UPC", 0.20);
        ACUP_PCT.put("UPF", 0.06);
        ACUP_PCT.put("UdL", 0.07);
        ACUP_PCT.put("UdG", 0.07);
        ACUP_PCT.put("URV", 0.08);
    }

    public static class AcupResumRow {
        private String code;
        private String name;
        private double pctAcup;
        private int sol;
        private double pctSol;
        private int con;
        private double pctCon;
        private double pctExit;
        private double totalMEur;
        private double pctAcupMEur;
        private double mitjaEurProj;

        // Getters & Setters
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public double getPctAcup() { return pctAcup; }
        public void setPctAcup(double pctAcup) { this.pctAcup = pctAcup; }

        public int getSol() { return sol; }
        public void setSol(int sol) { this.sol = sol; }

        public double getPctSol() { return pctSol; }
        public void setPctSol(double pctSol) { this.pctSol = pctSol; }

        public int getCon() { return con; }
        public void setCon(int con) { this.con = con; }

        public double getPctCon() { return pctCon; }
        public void setPctCon(double pctCon) { this.pctCon = pctCon; }

        public double getPctExit() { return pctExit; }
        public void setPctExit(double pctExit) { this.pctExit = pctExit; }

        public double getTotalMEur() { return totalMEur; }
        public void setTotalMEur(double totalMEur) { this.totalMEur = totalMEur; }

        public double getPctAcupMEur() { return pctAcupMEur; }
        public void setPctAcupMEur(double pctAcupMEur) { this.pctAcupMEur = pctAcupMEur; }

        public double getMitjaEurProj() { return mitjaEurProj; }
        public void setMitjaEurProj(double mitjaEurProj) { this.mitjaEurProj = mitjaEurProj; }
    }

    public static class AcupResumResponse {
        private List<AcupResumRow> rows;
        private AcupResumRow totalRow;
        private List<String> notes;

        public List<AcupResumRow> getRows() { return rows; }
        public void setRows(List<AcupResumRow> rows) { this.rows = rows; }

        public AcupResumRow getTotalRow() { return totalRow; }
        public void setTotalRow(AcupResumRow totalRow) { this.totalRow = totalRow; }

        public List<String> getNotes() { return notes; }
        public void setNotes(List<String> notes) { this.notes = notes; }
    }

    public static class UabProjectRow {
        private String referencia;
        private String subArea;
        private String centro;
        private int duracion;
        private int predocs;
        private double totalEur;
        private double cdEur;
        private double ciEur;
        private double eur2026;
        private double eur2027;
        private double eur2028;
        private double eur2029;

        public String getReferencia() { return referencia; }
        public void setReferencia(String referencia) { this.referencia = referencia; }
        public String getSubArea() { return subArea; }
        public void setSubArea(String subArea) { this.subArea = subArea; }
        public String getCentro() { return centro; }
        public void setCentro(String centro) { this.centro = centro; }
        public int getDuracion() { return duracion; }
        public void setDuracion(int duracion) { this.duracion = duracion; }
        public int getPredocs() { return predocs; }
        public void setPredocs(int predocs) { this.predocs = predocs; }
        public double getTotalEur() { return totalEur; }
        public void setTotalEur(double totalEur) { this.totalEur = totalEur; }
        public double getCdEur() { return cdEur; }
        public void setCdEur(double cdEur) { this.cdEur = cdEur; }
        public double getCiEur() { return ciEur; }
        public void setCiEur(double ciEur) { this.ciEur = ciEur; }
        public double getEur2026() { return eur2026; }
        public void setEur2026(double eur2026) { this.eur2026 = eur2026; }
        public double getEur2027() { return eur2027; }
        public void setEur2027(double eur2027) { this.eur2027 = eur2027; }
        public double getEur2028() { return eur2028; }
        public void setEur2028(double eur2028) { this.eur2028 = eur2028; }
        public double getEur2029() { return eur2029; }
        public void setEur2029(double eur2029) { this.eur2029 = eur2029; }
    }

    @GetMapping("/uab-projects")
    public List<UabProjectRow> getUabProjects() {
        List<UabProjectRow> result = new ArrayList<>();
        Map<String, double[]> refToEcon = new HashMap<>(); // [total, cd, ci]
        Map<String, Integer> refToPredocs = new HashMap<>();

        // Read Anexo_II
        ClassPathResource res2 = new ClassPathResource("PIDs/2025/Anexo_II_Datos_Economicos.xlsx");
        if (res2.exists()) {
            try (InputStream is = res2.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
                Sheet sheet = wb.getSheet("Sheet1");
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String ref = getCellStringValue(row.getCell(1)).trim();
                    if (!ref.isEmpty()) {
                        double total  = getCellDoubleValue(row.getCell(2));
                        double cd     = getCellDoubleValue(row.getCell(3));
                        double ci     = getCellDoubleValue(row.getCell(4));
                        double y2026  = getCellDoubleValue(row.getCell(5));
                        double y2027  = getCellDoubleValue(row.getCell(6));
                        double y2028  = getCellDoubleValue(row.getCell(7));
                        double y2029  = getCellDoubleValue(row.getCell(8));
                        int predocs   = (int) getCellDoubleValue(row.getCell(9));
                        refToEcon.put(ref, new double[]{total, cd, ci, y2026, y2027, y2028, y2029});
                        refToPredocs.put(ref, predocs);
                    }
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        // Read Anexo_I filtered to UAB
        ClassPathResource res1 = new ClassPathResource("PIDs/2025/Anexo_I_Datos_Generales.xlsx");
        if (res1.exists()) {
            try (InputStream is = res1.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
                Sheet sheet = wb.getSheet("Sheet1");
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String entidad = getCellStringValue(row.getCell(4)).trim();
                    if (!"UNIVERSIDAD AUTONOMA DE BARCELONA".equalsIgnoreCase(entidad)) continue;
                    String ref     = getCellStringValue(row.getCell(1)).trim();
                    String subArea = getCellStringValue(row.getCell(2)).trim();
                    String centro  = getCellStringValue(row.getCell(5)).trim();
                    int duracion   = (int) getCellDoubleValue(row.getCell(7));
                    double[] econ  = refToEcon.getOrDefault(ref, new double[]{0,0,0,0,0,0,0});
                    int predocs    = refToPredocs.getOrDefault(ref, 0);
                    UabProjectRow p = new UabProjectRow();
                    p.setReferencia(ref);
                    p.setSubArea(subArea);
                    p.setCentro(centro);
                    p.setDuracion(duracion);
                    p.setPredocs(predocs);
                    p.setTotalEur(econ[0]);
                    p.setCdEur(econ[1]);
                    p.setCiEur(econ[2]);
                    p.setEur2026(econ[3]);
                    p.setEur2027(econ[4]);
                    p.setEur2028(econ[5]);
                    p.setEur2029(econ[6]);
                    result.add(p);
                }
            } catch (Exception e) { e.printStackTrace(); }
        }

        return result;
    }

    @GetMapping("/data")
    public AcupResumResponse getAcupResumData() {
        return calculateResumData();
    }

    @GetMapping("/download")
    public void downloadExcel(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=Resum_ACUP_PID2025.xlsx");

        AcupResumResponse data = calculateResumData();
        Map<String, CellStyle> styleCache = new HashMap<>();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet ws = workbook.createSheet("Resum ACUP");

            // Fonts
            Font headerFont = workbook.createFont();
            headerFont.setFontName("Calibri");
            headerFont.setFontHeightInPoints((short) 9);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            Font normalFont = workbook.createFont();
            normalFont.setFontName("Calibri");
            normalFont.setFontHeightInPoints((short) 9);

            Font totalFont = workbook.createFont();
            totalFont.setFontName("Calibri");
            totalFont.setFontHeightInPoints((short) 9);
            totalFont.setBold(true);
            totalFont.setColor(IndexedColors.WHITE.getIndex());

            // Cell Styles
            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(getXSSFColor(workbook, "1F3864"));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setWrapText(true);
            setBorder(headerStyle);

            XSSFCellStyle totalStyle = workbook.createCellStyle();
            totalStyle.setFont(totalFont);
            totalStyle.setFillForegroundColor(getXSSFColor(workbook, "2E75B6"));
            totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalStyle.setAlignment(HorizontalAlignment.CENTER);
            totalStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(totalStyle);

            XSSFCellStyle oddStyle = workbook.createCellStyle();
            oddStyle.setFont(normalFont);
            oddStyle.setFillForegroundColor(getXSSFColor(workbook, "FFFFFF"));
            oddStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            oddStyle.setAlignment(HorizontalAlignment.CENTER);
            oddStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(oddStyle);

            XSSFCellStyle evenStyle = workbook.createCellStyle();
            evenStyle.setFont(normalFont);
            evenStyle.setFillForegroundColor(getXSSFColor(workbook, "D6E4F0"));
            evenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            evenStyle.setAlignment(HorizontalAlignment.CENTER);
            evenStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(evenStyle);

            // Headers
            String[] headers = {
                "Universitat",
                "% ACUP",
                "Projectes\nsol·licitats",
                "% sol·licitats\nrespecte ACUP",
                "Projectes\nconcedits",
                "% concedits\nrespecte ACUP",
                "% Exit",
                "Total (M€)",
                "%ACUP\nM€",
                "Mitja € Proj."
            };

            Row headerRow = ws.createRow(0);
            headerRow.setHeightInPoints(36);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            // Write rows
            int rIdx = 1;
            for (AcupResumRow row : data.getRows()) {
                Row excelRow = ws.createRow(rIdx);
                excelRow.setHeightInPoints(15);

                boolean isEven = (rIdx % 2 == 0);
                XSSFCellStyle baseStyle = isEven ? evenStyle : oddStyle;

                CellStyle s1 = getOrCreateStyle(workbook, baseStyle, "0%", styleCache);
                CellStyle s10 = getOrCreateStyle(workbook, baseStyle, "#,##0 \"€\"", styleCache);
                CellStyle s8 = getOrCreateStyle(workbook, baseStyle, "0.0", styleCache);

                writeCell(excelRow, 0, row.getCode(), baseStyle);
                writeCell(excelRow, 1, row.getPctAcup(), s1);
                writeCell(excelRow, 2, (double) row.getSol(), baseStyle);
                writeCell(excelRow, 3, row.getPctSol(), s1);
                writeCell(excelRow, 4, (double) row.getCon(), baseStyle);
                writeCell(excelRow, 5, row.getPctCon(), s1);
                writeCell(excelRow, 6, row.getPctExit(), s1);
                writeCell(excelRow, 7, row.getTotalMEur(), s8);
                writeCell(excelRow, 8, row.getPctAcupMEur(), s1);
                writeCell(excelRow, 9, row.getMitjaEurProj(), s10);

                rIdx++;
            }

            // Total row
            Row excelRow = ws.createRow(rIdx);
            excelRow.setHeightInPoints(15);
            AcupResumRow totalRow = data.getTotalRow();

            CellStyle t1 = getOrCreateStyle(workbook, totalStyle, "0%", styleCache);
            CellStyle t10 = getOrCreateStyle(workbook, totalStyle, "#,##0 \"€\"", styleCache);
            CellStyle t8 = getOrCreateStyle(workbook, totalStyle, "0.0", styleCache);

            writeCell(excelRow, 0, totalRow.getCode(), totalStyle);
            writeCell(excelRow, 1, totalRow.getPctAcup(), t1);
            writeCell(excelRow, 2, (double) totalRow.getSol(), totalStyle);
            writeCell(excelRow, 3, totalRow.getPctSol(), t1);
            writeCell(excelRow, 4, (double) totalRow.getCon(), totalStyle);
            writeCell(excelRow, 5, totalRow.getPctCon(), t1);
            writeCell(excelRow, 6, totalRow.getPctExit(), t1);
            writeCell(excelRow, 7, totalRow.getTotalMEur(), t8);
            writeCell(excelRow, 8, totalRow.getPctAcupMEur(), t1);
            writeCell(excelRow, 9, totalRow.getMitjaEurProj(), t10);

            // Column Widths
            int[] colWidths = {8, 8, 12, 12, 12, 12, 8, 10, 10, 13};
            for (int i = 0; i < colWidths.length; i++) {
                ws.setColumnWidth(i, colWidths[i] * 256);
            }

            // Notes
            int noteRowIdx = rIdx + 3;
            List<String> notes = data.getNotes();
            for (int i = 0; i < notes.size(); i++) {
                Row nRow = ws.createRow(noteRowIdx + i);
                Cell cell = nRow.createCell(0);
                cell.setCellValue(notes.get(i));

                Font noteFont = workbook.createFont();
                noteFont.setFontName("Calibri");
                noteFont.setFontHeightInPoints((short) 9);
                noteFont.setBold(i == 0);

                CellStyle noteStyle = workbook.createCellStyle();
                noteStyle.setFont(noteFont);
                cell.setCellStyle(noteStyle);

                ws.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(
                    noteRowIdx + i, noteRowIdx + i, 0, headers.length - 1
                ));
            }

            workbook.write(response.getOutputStream());
        }
    }

    @GetMapping("/download-uab")
    public void downloadUabExcel(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer duracio,
            @RequestParam(required = false) Integer predoc,
            HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=Projectes_UAB_PID2025.xlsx");

        List<UabProjectRow> projects = new ArrayList<>(getUabProjects());

        // Apply filters
        if (q != null && !q.trim().isEmpty()) {
            String query = q.toLowerCase().trim();
            projects.removeIf(p -> !p.getReferencia().toLowerCase().contains(query)
                                && !p.getCentro().toLowerCase().contains(query)
                                && !p.getSubArea().toLowerCase().contains(query));
        }
        if (duracio != null) {
            projects.removeIf(p -> p.getDuracion() != duracio);
        }
        if (predoc != null) {
            if (predoc == 1) {
                projects.removeIf(p -> p.getPredocs() < 1);
            } else if (predoc == 0) {
                projects.removeIf(p -> p.getPredocs() > 0);
            }
        }

        Map<String, CellStyle> styleCache = new HashMap<>();

        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet ws = workbook.createSheet("Projectes UAB");

            // Fonts
            Font headerFont = workbook.createFont();
            headerFont.setFontName("Calibri");
            headerFont.setFontHeightInPoints((short) 9);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());

            Font normalFont = workbook.createFont();
            normalFont.setFontName("Calibri");
            normalFont.setFontHeightInPoints((short) 9);

            Font totalFont = workbook.createFont();
            totalFont.setFontName("Calibri");
            totalFont.setFontHeightInPoints((short) 9);
            totalFont.setBold(true);
            totalFont.setColor(IndexedColors.WHITE.getIndex());

            XSSFCellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(getXSSFColor(workbook, "008357")); // UAB Green
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            headerStyle.setWrapText(true);
            setBorder(headerStyle);

            XSSFCellStyle totalStyle = workbook.createCellStyle();
            totalStyle.setFont(totalFont);
            totalStyle.setFillForegroundColor(getXSSFColor(workbook, "10B981")); // Emerald
            totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalStyle.setAlignment(HorizontalAlignment.CENTER);
            totalStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(totalStyle);

            XSSFCellStyle oddStyle = workbook.createCellStyle();
            oddStyle.setFont(normalFont);
            oddStyle.setFillForegroundColor(getXSSFColor(workbook, "FFFFFF"));
            oddStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            oddStyle.setAlignment(HorizontalAlignment.CENTER);
            oddStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(oddStyle);

            XSSFCellStyle evenStyle = workbook.createCellStyle();
            evenStyle.setFont(normalFont);
            evenStyle.setFillForegroundColor(getXSSFColor(workbook, "E6F4EA")); // Tinted Green
            evenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            evenStyle.setAlignment(HorizontalAlignment.CENTER);
            evenStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorder(evenStyle);

            String[] headers = {
                "Referència",
                "Àrea",
                "Departament / Centre",
                "Anys",
                "Predocs",
                "Costos Directes (EUR)",
                "Costos Indirectes (EUR)",
                "Total (€)",
                "2026",
                "2027",
                "2028",
                "2029"
            };

            Row headerRow = ws.createRow(0);
            headerRow.setHeightInPoints(24);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }

            int rIdx = 1;
            double sumCd = 0;
            double sumCi = 0;
            double sumTotal = 0;
            double sum2026 = 0;
            double sum2027 = 0;
            double sum2028 = 0;
            double sum2029 = 0;
            int sumPredocs = 0;

            for (UabProjectRow p : projects) {
                Row excelRow = ws.createRow(rIdx);
                excelRow.setHeightInPoints(15);

                boolean isEven = (rIdx % 2 == 0);
                XSSFCellStyle baseStyle = isEven ? evenStyle : oddStyle;

                CellStyle currencyStyle = getOrCreateStyle(workbook, baseStyle, "#,##0 \"€\"", styleCache);
                CellStyle intStyle = getOrCreateStyle(workbook, baseStyle, "#,##0", styleCache);

                writeCell(excelRow, 0, p.getReferencia(), baseStyle);
                writeCell(excelRow, 1, p.getSubArea(), baseStyle);
                
                CellStyle leftStyle = getOrCreateStyle(workbook, baseStyle, "@", styleCache);
                leftStyle.setAlignment(HorizontalAlignment.LEFT);
                writeCell(excelRow, 2, p.getCentro(), leftStyle);
                
                writeCell(excelRow, 3, (double) p.getDuracion(), intStyle);
                writeCell(excelRow, 4, (double) p.getPredocs(), intStyle);
                writeCell(excelRow, 5, p.getCdEur(), currencyStyle);
                writeCell(excelRow, 6, p.getCiEur(), currencyStyle);
                writeCell(excelRow, 7, p.getTotalEur(), currencyStyle);
                writeCell(excelRow, 8, p.getEur2026(), currencyStyle);
                writeCell(excelRow, 9, p.getEur2027(), currencyStyle);
                writeCell(excelRow, 10, p.getEur2028(), currencyStyle);
                writeCell(excelRow, 11, p.getEur2029(), currencyStyle);

                sumCd += p.getCdEur();
                sumCi += p.getCiEur();
                sumTotal += p.getTotalEur();
                sum2026 += p.getEur2026();
                sum2027 += p.getEur2027();
                sum2028 += p.getEur2028();
                sum2029 += p.getEur2029();
                sumPredocs += p.getPredocs();

                rIdx++;
            }

            Row totalRow = ws.createRow(rIdx);
            totalRow.setHeightInPoints(15);

            CellStyle tCurrency = getOrCreateStyle(workbook, totalStyle, "#,##0 \"€\"", styleCache);
            CellStyle tInt = getOrCreateStyle(workbook, totalStyle, "#,##0", styleCache);

            writeCell(totalRow, 0, "TOTAL", totalStyle);
            writeCell(totalRow, 1, "", totalStyle);
            writeCell(totalRow, 2, "", totalStyle);
            writeCell(totalRow, 3, "", totalStyle);
            writeCell(totalRow, 4, (double) sumPredocs, tInt);
            writeCell(totalRow, 5, sumCd, tCurrency);
            writeCell(totalRow, 6, sumCi, tCurrency);
            writeCell(totalRow, 7, sumTotal, tCurrency);
            writeCell(totalRow, 8, sum2026, tCurrency);
            writeCell(totalRow, 9, sum2027, tCurrency);
            writeCell(totalRow, 10, sum2028, tCurrency);
            writeCell(totalRow, 11, sum2029, tCurrency);

            int[] colWidths = {20, 10, 45, 8, 8, 15, 15, 15, 12, 12, 12, 12};
            for (int i = 0; i < colWidths.length; i++) {
                ws.setColumnWidth(i, colWidths[i] * 256);
            }

            workbook.write(response.getOutputStream());
        }
    }


    private AcupResumResponse calculateResumData() {
        AcupResumResponse response = new AcupResumResponse();
        List<AcupResumRow> rows = new ArrayList<>();

        Map<String, Double> refToTotal = new HashMap<>();
        Map<String, Integer> conCounts = new LinkedHashMap<>();
        Map<String, Double> eurTotals = new LinkedHashMap<>();
        Map<String, Integer> sinAyudaCounts = new LinkedHashMap<>();

        // Initialize maps
        for (String code : UNIS.keySet()) {
            conCounts.put(code, 0);
            eurTotals.put(code, 0.0);
            sinAyudaCounts.put(code, 0);
        }

        // 1. Read Anexo_II (Datos Economicos)
        ClassPathResource res2 = new ClassPathResource("PIDs/2025/Anexo_II_Datos_Economicos.xlsx");
        if (res2.exists()) {
            try (InputStream is = res2.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
                Sheet sheet = wb.getSheet("Sheet1");
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Cell refCell = row.getCell(1); // Column B
                    Cell totalCell = row.getCell(2); // Column C
                    if (refCell != null) {
                        String ref = getCellStringValue(refCell).trim();
                        if (!ref.isEmpty()) {
                            double total = getCellDoubleValue(totalCell);
                            refToTotal.put(ref, total);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 2. Read Anexo_I (Datos Generales)
        ClassPathResource res1 = new ClassPathResource("PIDs/2025/Anexo_I_Datos_Generales.xlsx");
        if (res1.exists()) {
            try (InputStream is = res1.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
                Sheet sheet = wb.getSheet("Sheet1");
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Cell refCell = row.getCell(1); // Column B
                    Cell entidadCell = row.getCell(4); // Column E
                    if (entidadCell != null) {
                        String entidad = getCellStringValue(entidadCell).trim();
                        for (Map.Entry<String, String> entry : UNIS.entrySet()) {
                            if (entry.getValue().equalsIgnoreCase(entidad)) {
                                String code = entry.getKey();
                                conCounts.put(code, conCounts.get(code) + 1);
                                if (refCell != null) {
                                    String ref = getCellStringValue(refCell).trim();
                                    double total = refToTotal.getOrDefault(ref, 0.0);
                                    eurTotals.put(code, eurTotals.get(code) + total);
                                }
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // 3. Read Anexo_III (Solicitudes sin ayuda)
        ClassPathResource res3 = new ClassPathResource("PIDs/2025/Anexo_III_Solicitudes_sin_ayuda.xlsx");
        if (res3.exists()) {
            try (InputStream is = res3.getInputStream(); Workbook wb = WorkbookFactory.create(is)) {
                Sheet sheet = wb.getSheet("Sheet1");
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    Cell entidadCell = row.getCell(4); // Column E
                    if (entidadCell != null) {
                        String entidad = getCellStringValue(entidadCell).trim();
                        for (Map.Entry<String, String> entry : UNIS.entrySet()) {
                            if (entry.getValue().equalsIgnoreCase(entidad)) {
                                String code = entry.getKey();
                                sinAyudaCounts.put(code, sinAyudaCounts.get(code) + 1);
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Compute aggregates
        int totSol = 0;
        int totCon = 0;
        double totEur = 0.0;
        for (String code : UNIS.keySet()) {
            int con = conCounts.get(code);
            int sinAyuda = sinAyudaCounts.get(code);
            int sol = con + sinAyuda;
            double totalEur = eurTotals.get(code);

            totSol += sol;
            totCon += con;
            totEur += totalEur;
        }

        // Build Rows
        for (String code : UNIS.keySet()) {
            int con = conCounts.get(code);
            int sinAyuda = sinAyudaCounts.get(code);
            int sol = con + sinAyuda;
            double totalEur = eurTotals.get(code);

            AcupResumRow row = new AcupResumRow();
            row.setCode(code);
            row.setName(UNIS.get(code));
            row.setPctAcup(ACUP_PCT.get(code));
            row.setSol(sol);
            row.setPctSol(totSol > 0 ? (double) sol / totSol : 0.0);
            row.setCon(con);
            row.setPctCon(totCon > 0 ? (double) con / totCon : 0.0);
            row.setPctExit(sol > 0 ? (double) con / sol : 0.0);
            row.setTotalMEur((double) Math.round((totalEur / 1_000_000.0) * 10.0) / 10.0);
            row.setPctAcupMEur(totEur > 0 ? totalEur / totEur : 0.0);
            row.setMitjaEurProj(con > 0 ? totalEur / con : 0.0);
            rows.add(row);
        }

        AcupResumRow totalRow = new AcupResumRow();
        totalRow.setCode("TOTAL");
        totalRow.setName("TOTAL");
        totalRow.setPctAcup(1.0);
        totalRow.setSol(totSol);
        totalRow.setPctSol(1.0);
        totalRow.setCon(totCon);
        totalRow.setPctCon(1.0);
        totalRow.setPctExit(totSol > 0 ? (double) totCon / totSol : 0.0);
        totalRow.setTotalMEur((double) Math.round((totEur / 1_000_000.0) * 10.0) / 10.0);
        totalRow.setPctAcupMEur(1.0);
        totalRow.setMitjaEurProj(totCon > 0 ? totEur / totCon : 0.0);

        List<String> notes = new ArrayList<>();
        notes.add("Notas sobre el cálculo:");
        notes.add("");
        notes.add("Sol·licitats = suma de proyectos de esa entidad en Anexo I (concedidos) + Anexo III (sin ayuda).");
        notes.add("Concedits = proyectos de esa entidad en Anexo I.");
        notes.add("Total (M€) = suma de \"TOTAL concedido\" del Anexo II para los proyectos concedidos de esa entidad (cruzado por REFERENCIA).");
        notes.add("Solo cuento proyectos donde la universidad es la entidad solicitante, no cuando aparece solo como centro/participante.");

        response.setRows(rows);
        response.setTotalRow(totalRow);
        response.setNotes(notes);

        return response;
    }

    private double eurToFloat(String s) {
        if (s == null || s.trim().isEmpty()) {
            return 0.0;
        }
        s = s.trim().replace(".", "").replace(",", ".");
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double getCellDoubleValue(Cell cell) {
        if (cell == null) {
            return 0.0;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        if (type == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        } else if (type == CellType.STRING) {
            return eurToFloat(cell.getStringCellValue());
        }
        return 0.0;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        if (type == CellType.STRING) {
            return cell.getStringCellValue().trim();
        } else if (type == CellType.NUMERIC) {
            double val = cell.getNumericCellValue();
            if (val == (long) val) {
                return String.valueOf((long) val);
            }
            return String.valueOf(val);
        } else if (type == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "";
    }

    private XSSFColor getXSSFColor(XSSFWorkbook workbook, String hex) {
        byte[] rgb = new byte[]{
            (byte) Integer.parseInt(hex.substring(0, 2), 16),
            (byte) Integer.parseInt(hex.substring(2, 4), 16),
            (byte) Integer.parseInt(hex.substring(4, 6), 16)
        };
        return new XSSFColor(rgb, null);
    }

    private void setBorder(CellStyle style) {
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);

        if (style instanceof XSSFCellStyle) {
            XSSFCellStyle xStyle = (XSSFCellStyle) style;
            byte[] rgb = new byte[]{(byte) 0xAA, (byte) 0xAA, (byte) 0xAA};
            XSSFColor color = new XSSFColor(rgb, null);
            xStyle.setLeftBorderColor(color);
            xStyle.setRightBorderColor(color);
            xStyle.setTopBorderColor(color);
            xStyle.setBottomBorderColor(color);
        } else {
            style.setLeftBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            style.setRightBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            style.setTopBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
            style.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        }
    }

    private void writeCell(Row row, int colIdx, String val, CellStyle style) {
        Cell cell = row.createCell(colIdx);
        cell.setCellValue(val);
        cell.setCellStyle(style);
    }

    private void writeCell(Row row, int colIdx, Double val, CellStyle style) {
        Cell cell = row.createCell(colIdx);
        cell.setCellValue(val);
        cell.setCellStyle(style);
    }

    private CellStyle getOrCreateStyle(Workbook workbook, CellStyle baseStyle, String format, Map<String, CellStyle> styleCache) {
        String key = baseStyle.hashCode() + "_" + format;
        if (styleCache.containsKey(key)) {
            return styleCache.get(key);
        }
        CellStyle newStyle = workbook.createCellStyle();
        newStyle.cloneStyleFrom(baseStyle);
        DataFormat df = workbook.createDataFormat();
        newStyle.setDataFormat(df.getFormat(format));
        styleCache.put(key, newStyle);
        return newStyle;
    }
}
