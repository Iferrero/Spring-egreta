package com.example.demo.award.model;

/**
 * Filter criteria for PowerTable and multi-year financial aggregations.
 */
public record PowerTableQueryCriteria(
        Integer desde,
        Integer hasta,
        String modoAnio,
        String collaboratorUuid
) {
    public PowerTableQueryCriteria {
        if (modoAnio == null || modoAnio.isBlank()) {
            modoAnio = "awardDate";
        }
    }

    public static PowerTableQueryCriteria of(Integer desde, Integer hasta, String modoAnio, String collaboratorUuid) {
        return new PowerTableQueryCriteria(desde, hasta, modoAnio, collaboratorUuid);
    }
}
