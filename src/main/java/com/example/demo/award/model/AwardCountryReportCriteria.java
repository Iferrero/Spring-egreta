package com.example.demo.award.model;

/**
 * Parameter criteria for generating a country-level Word report.
 */
public record AwardCountryReportCriteria(
        String countryCode,
        String startDate,
        String endDate,
        boolean projectes,
        boolean convenis,
        boolean beques,
        boolean xarxes,
        boolean tesis,
        boolean articles,
        boolean llibres,
        boolean capitols
) {
    public AwardCountryReportCriteria {
        if (countryCode == null || countryCode.isBlank()) {
            throw new IllegalArgumentException("countryCode cannot be null or empty");
        }
    }

    public static AwardCountryReportCriteria ofDefault(String countryCode) {
        return new AwardCountryReportCriteria(
                countryCode,
                "1968-01-01",
                null,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }
}
