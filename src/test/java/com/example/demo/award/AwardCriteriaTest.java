package com.example.demo.award;

import com.example.demo.award.model.CostCodePrefix;
import com.example.demo.award.model.PowerTableQueryCriteria;
import com.example.demo.award.model.ResignedAwardsQueryCriteria;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AwardCriteriaTest {

    @Test
    @DisplayName("CostCodePrefix matches prefixes case-insensitively and handles ALL")
    void testCostCodePrefixMatching() {
        assertThat(CostCodePrefix.PK.matches("PK1234")).isTrue();
        assertThat(CostCodePrefix.PK.matches("pk9999")).isTrue();
        assertThat(CostCodePrefix.PK.matches("EA1234")).isFalse();
        assertThat(CostCodePrefix.PK.matches(null)).isFalse();

        assertThat(CostCodePrefix.EA.matches("EA5678")).isTrue();
        assertThat(CostCodePrefix.PO.matches("PO9012")).isTrue();

        assertThat(CostCodePrefix.ALL.matches("PK1")).isTrue();
        assertThat(CostCodePrefix.ALL.matches("EA2")).isTrue();
        assertThat(CostCodePrefix.ALL.matches("PO3")).isTrue();
        assertThat(CostCodePrefix.ALL.matches("OTHER")).isFalse();
    }

    @Test
    @DisplayName("PowerTableQueryCriteria holds defaults and filter fields")
    void testPowerTableQueryCriteria() {
        PowerTableQueryCriteria criteria = new PowerTableQueryCriteria(2020, 2024, "awardDate", "org-uuid-123");
        assertThat(criteria.desde()).isEqualTo(2020);
        assertThat(criteria.hasta()).isEqualTo(2024);
        assertThat(criteria.modoAnio()).isEqualTo("awardDate");
        assertThat(criteria.collaboratorUuid()).isEqualTo("org-uuid-123");
    }

    @Test
    @DisplayName("ResignedAwardsQueryCriteria handles pagination and default values")
    void testResignedAwardsQueryCriteria() {
        ResignedAwardsQueryCriteria criteria = ResignedAwardsQueryCriteria.builder()
                .page(0)
                .size(20)
                .statusType("terminated")
                .reasonFilter("renuncia")
                .sortBy("date")
                .sortOrder("desc")
                .build();

        assertThat(criteria.page()).isEqualTo(0);
        assertThat(criteria.size()).isEqualTo(20);
        assertThat(criteria.statusType()).isEqualTo("terminated");
        assertThat(criteria.reasonFilter()).isEqualTo("renuncia");
        assertThat(criteria.sortBy()).isEqualTo("date");
        assertThat(criteria.sortOrder()).isEqualTo("desc");
        assertThat(criteria.exportAll()).isFalse();
    }
}
