package com.example.demo.controller;

import org.apache.poi.ss.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api/ingressos")
@CrossOrigin(origins = "*")
public class IngressosController {

    public static class IngresItem {
        private String concept;
        private String category;
        private boolean isGroupHeader;
        private Map<String, Double> values;

        public IngresItem(String concept, String category, boolean isGroupHeader, Map<String, Double> values) {
            this.concept = concept;
            this.category = category;
            this.isGroupHeader = isGroupHeader;
            this.values = values;
        }

        // Getters
        public String getConcept() { return concept; }
        public String getCategory() { return category; }
        @com.fasterxml.jackson.annotation.JsonProperty("isGroupHeader")
        public boolean isGroupHeader() { return isGroupHeader; }
        public Map<String, Double> getValues() { return values; }
    }

    @GetMapping("/data")
    public List<IngresItem> getIngressosData() {
        List<IngresItem> result = new ArrayList<>();
        try {
            ClassPathResource resource = new ClassPathResource("Ingressos Recerca.xlsx");
            if (!resource.exists()) {
                return result;
            }
            try (InputStream is = resource.getInputStream();
                 Workbook workbook = WorkbookFactory.create(is)) {
                Sheet sheet = workbook.getSheet("1r trimestre - Total");
                if (sheet == null) {
                    sheet = workbook.getSheetAt(0);
                }

                int[] colIndices = {2, 4, 6, 9, 12, 15, 18, 21};
                String[] years = {"2018", "2019", "2020", "2021", "2022", "2023", "2024", "2025"};

                // Las filas de datos son de la 5 a la 37 (index 4 a 36)
                for (int r = 4; r <= 36; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    Cell conceptCell = row.getCell(0);
                    if (conceptCell == null) continue;
                    String concept = getCellStringValue(conceptCell);
                    if (concept == null || concept.trim().isEmpty()) continue;

                    String category;
                    boolean isGroupHeader;

                    if (r == 4) {
                        category = "PS Recerca";
                        isGroupHeader = true;
                    } else if (r == 5) {
                        category = "Personal Investigador";
                        isGroupHeader = true;
                    } else if (r >= 6 && r <= 20) {
                        category = "Personal Investigador";
                        isGroupHeader = false;
                    } else if (r == 21) {
                        category = "Projectes";
                        isGroupHeader = true;
                    } else if (r >= 22 && r <= 35) {
                        category = "Projectes";
                        isGroupHeader = false;
                    } else if (r == 36) {
                        category = "General";
                        isGroupHeader = true;
                    } else {
                        category = "Unknown";
                        isGroupHeader = false;
                    }

                    Map<String, Double> values = new LinkedHashMap<>();
                    for (int i = 0; i < colIndices.length; i++) {
                        int colIdx = colIndices[i];
                        String year = years[i];
                        Cell cell = row.getCell(colIdx);
                        double val = 0.0;
                        if (cell != null) {
                            CellType cellType = cell.getCellType();
                            if (cellType == CellType.FORMULA) {
                                cellType = cell.getCachedFormulaResultType();
                            }
                            if (cellType == CellType.NUMERIC) {
                                val = cell.getNumericCellValue();
                            } else if (cellType == CellType.STRING) {
                                try {
                                    val = Double.parseDouble(cell.getStringCellValue().trim());
                                } catch (NumberFormatException e) {
                                    // ignore
                                }
                            }
                        }
                        values.put(year, val);
                    }

                    result.add(new IngresItem(concept, category, isGroupHeader, values));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) {
            return cell.getStringCellValue();
        } else if (cell.getCellType() == CellType.NUMERIC) {
            return String.valueOf(cell.getNumericCellValue());
        } else if (cell.getCellType() == CellType.BOOLEAN) {
            return String.valueOf(cell.getBooleanCellValue());
        }
        return "";
    }
}
