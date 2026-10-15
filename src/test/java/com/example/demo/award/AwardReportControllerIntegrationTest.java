package com.example.demo.award;

import com.example.demo.award.engine.AwardFinancialEngine;
import com.example.demo.award.model.AwardCountryReportCriteria;
import com.example.demo.award.port.AwardReportPort;
import com.example.demo.controller.AwardController;
import com.example.demo.repository.AwardRepository;
import com.example.demo.service.AwardService;
import com.example.demo.service.ResearchOutputJournalLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AwardReportControllerIntegrationTest {

    @Mock
    private AwardRepository repository;

    @Mock
    private AwardService service;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private ResearchOutputJournalLinkService researchOutputService;

    @Mock
    private AwardFinancialEngine financialEngine;

    @Mock
    private AwardReportPort awardReportPort;

    @Mock
    private com.example.demo.award.engine.AwardClassificationEngine classificationEngine;

    private AwardController controller;

    @BeforeEach
    void setUp() {
        controller = new AwardController(
                repository,
                service,
                mongoTemplate,
                researchOutputService,
                financialEngine,
                awardReportPort,
                classificationEngine
        );
    }

    @Test
    @DisplayName("generarInformeWordPais delegates to AwardReportPort and sets appropriate response headers")
    void testGenerarInformeWordPaisDelegation() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.generarInformeWordPais(
                "es",
                "1968-01-01",
                null,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                response
        );

        assertThat(response.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"informe-pais-ES.docx\"");

        ArgumentCaptor<AwardCountryReportCriteria> captor = ArgumentCaptor.forClass(AwardCountryReportCriteria.class);
        verify(awardReportPort).generateCountryWordReport(captor.capture(), any(OutputStream.class));

        AwardCountryReportCriteria criteria = captor.getValue();
        assertThat(criteria.countryCode()).isEqualTo("es");
        assertThat(criteria.projectes()).isTrue();
        assertThat(criteria.convenis()).isFalse();
    }
}
