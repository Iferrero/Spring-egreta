
package com.example.demo.egreta;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/egreta")
@Hidden
public class EgretaSyncController {

    @Autowired
    private List<AbstractEgretaSyncService> syncServices;

    @Autowired
    private ActivitiesSyncService activitiesSyncService;

    @PostMapping("/sync/activities")
    public ResponseEntity<String> syncActivities() {
        activitiesSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'activities' iniciada.");
    }

    @Autowired
    private AwardsSyncService awardsSyncService;

    @PostMapping("/sync/awards")
    public ResponseEntity<String> syncAwards() {
        awardsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'awards' iniciada.");
    }

    @Autowired
    private OrganizationsSyncService organizationsSyncService;

    @PostMapping("/sync/organizations")
    public ResponseEntity<String> syncOrganizations() {
        organizationsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'organizations' iniciada.");
    }

    @Autowired
    private ApplicationsSyncService applicationsSyncService;

    @PostMapping("/sync/applications")
    public ResponseEntity<String> syncApplications() {
        applicationsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'applications' iniciada.");
    }

    @Autowired
    private SyncProgressRegistry syncProgressRegistry;
    @GetMapping("/sync/progress/{collection}")
    public ResponseEntity<SyncProgressRegistry.SyncProgress> getProgress(@PathVariable String collection) {
        return ResponseEntity.ok(syncProgressRegistry.getProgress(collection));
    }

    @GetMapping("/sync/stats")
    public ResponseEntity<Map<String, Map<String, Long>>> getSyncStats() {
        Map<String, Map<String, Long>> stats = new LinkedHashMap<>();

        for (AbstractEgretaSyncService service : syncServices) {
            Map<String, Long> counts = new LinkedHashMap<>();
            counts.put("mongo", service.getMongoCount());
            counts.put("collection", service.getSourceCollectionCount());
            stats.put(service.collectionName(), counts);
        }

        return ResponseEntity.ok(stats);
    }

        @Autowired private AuthorcollaborationsSyncService authorcollaborationsSyncService;
    @Autowired private ClassificationschemesSyncService classificationschemesSyncService;
    @Autowired private ConceptsSyncService conceptsSyncService;
    @Autowired private CoursesSyncService coursesSyncService;
    @Autowired private DatasetsSyncService datasetsSyncService;
    @Autowired private EquipmentsSyncService equipmentsSyncService;
    @Autowired private EventsSyncService eventsSyncService;
    @Autowired private ExternalorganizationsSyncService externalorganizationsSyncService;
    @Autowired private ExternalpersonsSyncService externalpersonsSyncService;
    @Autowired private FingerprintsSyncService fingerprintsSyncService;
    @Autowired private FundingoppportunitiesSyncService fundingoppportunitiesSyncService;
    @Autowired private JournalsSyncService journalsSyncService;
    @Autowired private PersonsSyncService personsSyncService;
    @Autowired private PressmediaSyncService pressmediaSyncService;
    @Autowired private PrizesSyncService prizesSyncService;
    @Autowired private ProjectsSyncService projectsSyncService;
    @Autowired private PublishersSyncService publishersSyncService;
    @Autowired private ResearchoutputsSyncService researchoutputsSyncService;
    @Autowired private StudentthesesSyncService studentthesesSyncService;
    @Autowired private UsersSyncService usersSyncService;
    @Autowired private EthicalReviewsSyncService ethicalReviewsSyncService;
    @Autowired private ImpactsSyncService impactsSyncService;
    @Autowired private MilestonesSyncService milestonesSyncService;
    @Autowired private PersonExpertisesSyncService personExpertisesSyncService;
    @Autowired private ThesauriSyncService thesauriSyncService;
    @PostMapping("/sync/authorcollaborations")
    public ResponseEntity<String> syncAuthorcollaborations() {
        authorcollaborationsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'authorcollaborations' iniciada.");
    }

    @PostMapping({"/sync/classificationschemes", "/sync/classification-schemes"})
    public ResponseEntity<String> syncClassificationschemes() {
        classificationschemesSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'classificationschemes' iniciada.");
    }

    @PostMapping("/sync/concepts")
    public ResponseEntity<String> syncConcepts() {
        conceptsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'concepts' iniciada.");
    }

    @PostMapping("/sync/courses")
    public ResponseEntity<String> syncCourses() {
        coursesSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'courses' iniciada.");
    }

    @PostMapping("/sync/datasets")
    public ResponseEntity<String> syncDatasets() {
        datasetsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'datasets' iniciada.");
    }

    @PostMapping("/sync/equipments")
    public ResponseEntity<String> syncEquipments() {
        equipmentsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'equipments' iniciada.");
    }

    @PostMapping("/sync/events")
    public ResponseEntity<String> syncEvents() {
        eventsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'events' iniciada.");
    }

    @PostMapping("/sync/externalorganizations")
    public ResponseEntity<String> syncExternalorganizations() {
        externalorganizationsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'externalorganizations' iniciada.");
    }

    @PostMapping("/sync/externalpersons")
    public ResponseEntity<String> syncExternalpersons() {
        externalpersonsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'externalpersons' iniciada.");
    }

    @PostMapping("/sync/fingerprints")
    public ResponseEntity<String> syncFingerprints() {
        fingerprintsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'fingerprints' iniciada.");
    }

    @PostMapping("/sync/fundingoppportunities")
    public ResponseEntity<String> syncFundingoppportunities() {
        fundingoppportunitiesSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'fundingoppportunities' iniciada.");
    }

    @PostMapping("/sync/journals")
    public ResponseEntity<String> syncJournals() {
        journalsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'journals' iniciada.");
    }

    @PostMapping("/sync/persons")
    public ResponseEntity<String> syncPersons() {
        personsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'persons' iniciada.");
    }

    @PostMapping("/sync/pressmedia")
    public ResponseEntity<String> syncPressmedia() {
        pressmediaSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'pressmedia' iniciada.");
    }

    @PostMapping("/sync/prizes")
    public ResponseEntity<String> syncPrizes() {
        prizesSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'prizes' iniciada.");
    }

    @PostMapping("/sync/projects")
    public ResponseEntity<String> syncProjects() {
        projectsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'projects' iniciada.");
    }

    @PostMapping("/sync/publishers")
    public ResponseEntity<String> syncPublishers() {
        publishersSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'publishers' iniciada.");
    }

    @PostMapping("/sync/researchoutputs")
    public ResponseEntity<String> syncResearchoutputs() {
        researchoutputsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'researchoutputs' iniciada.");
    }

    @PostMapping("/sync/researchoutputs/{uuid}")
    public ResponseEntity<Map<String, Object>> syncResearchoutputOne(@PathVariable String uuid) {
        Map<String, Object> updated = researchoutputsSyncService.syncOne(uuid);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/sync/{collection}/{uuid}")
    public ResponseEntity<Map<String, Object>> syncOneGeneric(
            @PathVariable String collection,
            @PathVariable String uuid) {
        for (AbstractEgretaSyncService service : syncServices) {
            if (service.collectionName().equalsIgnoreCase(collection) || service.getEndpoint().equalsIgnoreCase(collection)) {
                Map<String, Object> updated = service.syncOne(uuid);
                return ResponseEntity.ok(updated);
            }
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/sync/studenttheses")
    public ResponseEntity<String> syncStudenttheses() {
        studentthesesSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'studenttheses' iniciada.");
    }

    @PostMapping("/sync/users")
    public ResponseEntity<String> syncUsers() {
        usersSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'users' iniciada.");
    }

    @PostMapping({"/sync/ethicalreviews", "/sync/ethical-reviews"})
    public ResponseEntity<String> syncEthicalReviews() {
        ethicalReviewsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'ethical-reviews' iniciada.");
    }

    @PostMapping("/sync/impacts")
    public ResponseEntity<String> syncImpacts() {
        impactsSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'impacts' iniciada.");
    }

    @PostMapping("/sync/milestones")
    public ResponseEntity<String> syncMilestones() {
        milestonesSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'milestones' iniciada.");
    }

    @PostMapping({"/sync/personexpertises", "/sync/person-expertises"})
    public ResponseEntity<String> syncPersonExpertises() {
        personExpertisesSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'person-expertises' iniciada.");
    }

    @PostMapping("/sync/thesauri")
    public ResponseEntity<String> syncThesauri() {
        thesauriSyncService.sync();
        return ResponseEntity.ok("Sincronització de 'thesauri' iniciada.");
    }

    @PostMapping("/sync/{collection}")
    public ResponseEntity<String> syncCollectionGeneric(@PathVariable String collection) {
        String cleanParam = collection.replace("-", "").toLowerCase();
        for (AbstractEgretaSyncService service : syncServices) {
            String colClean = service.collectionName().replace("-", "").toLowerCase();
            String epClean = service.getEndpoint().replace("-", "").toLowerCase();
            if (colClean.equals(cleanParam) || epClean.equals(cleanParam)) {
                service.sync();
                return ResponseEntity.ok("Sincronització de '" + service.collectionName() + "' iniciada.");
            }
        }
        return ResponseEntity.notFound().build();
    }
}

