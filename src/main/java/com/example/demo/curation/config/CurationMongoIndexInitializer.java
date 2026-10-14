package com.example.demo.curation.config;

import jakarta.annotation.PostConstruct;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Inicializador y verificador de indices en MongoDB para la coleccion PersonPublicationCandidates.
 * Asegura que el indice unico sobre (personUuid, doi) incluya un filtro parcial para cadenas no nulas,
 * evitando colisiones duplicate key (E11000) cuando multiples publicaciones de OpenAlex carecen de DOI.
 */
@Component
public class CurationMongoIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(CurationMongoIndexInitializer.class);
    private static final String COLLECTION_NAME = "PersonPublicationCandidates";

    private final MongoTemplate mongoTemplate;

    public CurationMongoIndexInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @PostConstruct
    public void initIndexes() {
        try {
            IndexOperations indexOps = mongoTemplate.indexOps(COLLECTION_NAME);
            List<IndexInfo> existingIndexes = indexOps.getIndexInfo();

            // Si existe person_doi_unique_idx sin filtro parcial, eliminarlo para recrearlo con partialFilter
            for (IndexInfo info : existingIndexes) {
                if ("person_doi_unique_idx".equals(info.getName())) {
                    if (info.getPartialFilterExpression() == null || info.getPartialFilterExpression().isBlank()) {
                        log.info("Dropping obsolete non-partial index 'person_doi_unique_idx' on collection {}", COLLECTION_NAME);
                        indexOps.dropIndex("person_doi_unique_idx");
                    }
                }
            }

            // Indice unico parcial (personUuid, doi) solo cuando doi es de tipo string
            Document doiKeys = new Document("personUuid", 1).append("doi", 1);
            Document doiPartial = new Document("doi", new Document("$type", "string"));
            org.springframework.data.mongodb.core.index.Index doiIdx = new CompoundIndexDefinition(doiKeys)
                    .named("person_doi_unique_idx")
                    .unique()
                    .partial(() -> doiPartial);
            indexOps.ensureIndex(doiIdx);

            // Indice unico parcial (personUuid, openAlexWorkId) solo cuando openAlexWorkId es de tipo string
            Document oalexKeys = new Document("personUuid", 1).append("openAlexWorkId", 1);
            Document oalexPartial = new Document("openAlexWorkId", new Document("$type", "string"));
            org.springframework.data.mongodb.core.index.Index oalexIdx = new CompoundIndexDefinition(oalexKeys)
                    .named("person_openalex_unique_idx")
                    .unique()
                    .partial(() -> oalexPartial);
            indexOps.ensureIndex(oalexIdx);

            // Indices de busqueda y filtrado
            Document statusVerdictKeys = new Document("personUuid", 1).append("status", 1).append("verdict", 1);
            indexOps.ensureIndex(new CompoundIndexDefinition(statusVerdictKeys).named("person_status_verdict_idx"));

            Document deptStatusKeys = new Document("departmentId", 1).append("status", 1);
            indexOps.ensureIndex(new CompoundIndexDefinition(deptStatusKeys).named("department_status_idx"));

            log.info("Curation indexes successfully verified on {}", COLLECTION_NAME);
        } catch (Exception e) {
            log.warn("Could not ensure indexes on {}: {}", COLLECTION_NAME, e.getMessage());
        }
    }
}
