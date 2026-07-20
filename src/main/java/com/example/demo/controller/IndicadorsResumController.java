package com.example.demo.controller;

import org.apache.poi.ss.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import java.io.InputStream;
import java.util.*;

@RestController
@RequestMapping("/api/indicadors-resum")
@CrossOrigin(origins = "*")
public class IndicadorsResumController {

    public static class IndicadorItem {
        private String concept;
        private String category;
        private boolean isGroupHeader;
        private Map<String, Double> values;

        public IndicadorItem(String concept, String category, boolean isGroupHeader, Map<String, Double> values) {
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
    public List<IndicadorItem> getIndicadorsData() {
        List<IndicadorItem> result = new ArrayList<>();
        try {
            ClassPathResource resource = new ClassPathResource("Indicadors resum_2025.xlsx");
            if (!resource.exists()) {
                return result;
            }
            try (InputStream is = resource.getInputStream();
                 Workbook workbook = WorkbookFactory.create(is)) {
                
                Sheet sheet = workbook.getSheet("Hoja1");
                if (sheet == null) {
                    sheet = workbook.getSheetAt(0);
                }

                String[] years = {"2017", "2018", "2019", "2020", "2021", "2022", "2023", "2024", "2025"};
                int startColIdx = 2; // Column C is index 2

                // The data rows are from index 2 to 14 (3rd to 15th row in sheet)
                for (int r = 2; r <= 14; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    Cell conceptCell = row.getCell(1); // Column B is index 1
                    if (conceptCell == null) continue;
                    String concept = getCellStringValue(conceptCell);
                    if (concept == null || concept.trim().isEmpty()) continue;

                    String category;
                    boolean isGroupHeader = false; // We can set this to false, or true for category headers, but here we group them in the UI.

                    if (r >= 2 && r <= 6) {
                        category = "Patents i Invencions";
                    } else if (r >= 7 && r <= 9) {
                        category = "Valorització i R&D";
                    } else if (r >= 10 && r <= 12) {
                        category = "Transferència i Convenis";
                    } else if (r >= 13 && r <= 14) {
                        category = "Empreses i EBTs";
                    } else {
                        category = "Altres";
                    }

                    Map<String, Double> values = new LinkedHashMap<>();
                    for (int i = 0; i < years.length; i++) {
                        String year = years[i];
                        int colIdx = startColIdx + i;
                        Cell cell = row.getCell(colIdx);
                        Double val = null;
                        if (cell != null) {
                            CellType cellType = cell.getCellType();
                            if (cellType == CellType.FORMULA) {
                                cellType = cell.getCachedFormulaResultType();
                            }
                            if (cellType == CellType.NUMERIC) {
                                val = cell.getNumericCellValue();
                            } else if (cellType == CellType.STRING) {
                                String cellStr = cell.getStringCellValue().trim();
                                if (!cellStr.equalsIgnoreCase("ND") && !cellStr.isEmpty()) {
                                    try {
                                        val = Double.parseDouble(cellStr);
                                    } catch (NumberFormatException e) {
                                        // Ignore and leave as null/ND
                                    }
                                }
                            }
                        }
                        values.put(year, val);
                    }

                    result.add(new IndicadorItem(concept, category, isGroupHeader, values));
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
