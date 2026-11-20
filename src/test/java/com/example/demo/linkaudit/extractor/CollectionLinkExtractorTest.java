package com.example.demo.linkaudit.extractor;

import com.example.demo.linkaudit.LinkAuditConstants;
import com.example.demo.linkaudit.model.ExtractedLinkRef;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CollectionLinkExtractorTest {

    private final CollectionLinkExtractor extractor = new CollectionLinkExtractor(mock(MongoTemplate.class));

    @Test
    void extractFromDocumentExtractsDocumentsLinksAndDeduplicatesSameElementUrls() {
        Document doc = new Document("_id", "rec-1")
                .append("uuid", "uuid-123")
                .append("pureId", 42)
                .append("title", new Document("value", "Projecte Egreta"))
                .append("type", new Document("term", new Document("ca_ES", "Projecte")))
                .append("documents", List.of(
                        new Document("file", new Document("url", "https://egreta.uab.cat/file/1.pdf")
                                .append("fileURL", "https://egreta.uab.cat/file/1.pdf")
                                .append("fileName", "mem.pdf"))
                                .append("type", new Document("term", new Document("ca_ES", "Memòria"))),
                        new Document("url", "https://example.org/a.pdf")
                                .append("link", "https://example.org/b.pdf")
                                .append("title", "Annex")
                ))
                .append("links", List.of(
                        new Document("url", "https://uab.cat/info")
                                .append("alias", "Web oficial")
                                .append("linkType", new Document("term", new Document("es_ES", "Enlace web")))
                ))
                .append("electronicVersions", List.of(
                        new Document("link", "https://should-be-ignored-in-awards.example/ev")
                ));

        List<ExtractedLinkRef> refs = extractor.extractFromDocument("Awards", doc);

        assertEquals(4, refs.size());

        ExtractedLinkRef doc1 = refs.get(0);
        assertEquals("Awards", doc1.collection());
        assertEquals("rec-1", doc1.recordId());
        assertEquals("uuid-123", doc1.recordUuid());
        assertEquals(42L, doc1.recordPureId());
        assertEquals("Projecte Egreta", doc1.recordTitle());
        assertEquals("Projecte", doc1.recordType());
        assertEquals("documents", doc1.sourceField());
        assertEquals("Memòria", doc1.subType());
        assertEquals("mem.pdf", doc1.linkLabel());
        assertEquals("https://egreta.uab.cat/file/1.pdf", doc1.rawUrl());

        ExtractedLinkRef doc2a = refs.get(1);
        assertEquals("https://example.org/a.pdf", doc2a.rawUrl());
        assertEquals("Sense especificar", doc2a.subType());
        assertEquals("Annex", doc2a.linkLabel());

        ExtractedLinkRef doc2b = refs.get(2);
        assertEquals("https://example.org/b.pdf", doc2b.rawUrl());

        ExtractedLinkRef link1 = refs.get(3);
        assertEquals("links", link1.sourceField());
        assertEquals("Enlace web", link1.subType());
        assertEquals("Web oficial", link1.linkLabel());
        assertEquals("https://uab.cat/info", link1.rawUrl());
    }

    @Test
    void extractFromDocumentExtractsElectronicVersionsOnlyInResearchoutputsIgnoringBareDois() {
        Document doc = new Document("_id", "ro-1")
                .append("uuid", "ro-uuid")
                .append("title", new Document("ca_ES", "Article científic"))
                .append("electronicVersions", List.of(
                        new Document("doi", "10.1000/x")
                                .append("link", null),
                        new Document("link", "https://publisher.example/article")
                                .append("file", new Document("url", "https://repo.example/article.pdf")
                                        .append("fileURL", "https://repo.example/article.pdf")
                                        .append("fileName", "article.pdf"))
                                .append("versionType", new Document("term", new Document("en_GB", "Published version")))
                ));

        List<ExtractedLinkRef> refs = extractor.extractFromDocument("Researchoutputs", doc);

        assertEquals(2, refs.size());
        assertEquals("electronicVersions", refs.get(0).sourceField());
        assertEquals("Published version", refs.get(0).subType());
        assertEquals("article.pdf", refs.get(0).linkLabel());
        assertEquals("https://publisher.example/article", refs.get(0).rawUrl());

        assertEquals("electronicVersions", refs.get(1).sourceField());
        assertEquals("https://repo.example/article.pdf", refs.get(1).rawUrl());
        assertTrue(refs.stream().noneMatch(r -> r.rawUrl().contains("10.1000/x")));
    }

    @Test
    void extractFromDocumentResolvesTitleVariantsTypeAndEgretaUrl() {
        Document d1 = new Document("_id", "1").append("title", new Document("value", "Title Value"))
                .append("links", List.of(new Document("url", "https://a.test")));
        Document d2 = new Document("_id", "2").append("title", new Document("ca_ES", "Títol CA"))
                .append("links", List.of(new Document("url", "https://a.test")));
        Document d3 = new Document("_id", "3").append("title", "String Title")
                .append("links", List.of(new Document("url", "https://a.test")));
        Document d4 = new Document("_id", "4").append("name", new Document("ca_ES", "Nom CA"))
                .append("links", List.of(new Document("url", "https://a.test")));
        Document d5 = new Document("_id", "5").append("name", new Document("firstName", "Maria").append("lastName", "Puig"))
                .append("typeDiscriminator", "InternalPerson")
                .append("links", List.of(new Document("url", "https://a.test")));
        Document d6 = new Document("_id", "6").append("name", "Org Name")
                .append("links", List.of(new Document("url", "https://a.test")));
        Document d7 = new Document("_id", "7").append("uuid", "u-7")
                .append("links", List.of(new Document("url", "https://a.test")));
        Document d8 = new Document("_id", "only-id")
                .append("links", List.of(new Document("url", "https://a.test")));

        assertEquals("Title Value", extractor.extractFromDocument("Awards", d1).get(0).recordTitle());
        assertEquals("Títol CA", extractor.extractFromDocument("Awards", d2).get(0).recordTitle());
        assertEquals("String Title", extractor.extractFromDocument("Awards", d3).get(0).recordTitle());
        assertEquals("Nom CA", extractor.extractFromDocument("Persons", d4).get(0).recordTitle());

        ExtractedLinkRef personRef = extractor.extractFromDocument("Persons", d5).get(0);
        assertEquals("Maria Puig", personRef.recordTitle());
        assertEquals("InternalPerson", personRef.recordType());

        assertEquals("Org Name", extractor.extractFromDocument("ExternalOrganizations", d6).get(0).recordTitle());
        assertEquals("u-7", extractor.extractFromDocument("Awards", d7).get(0).recordTitle());
        assertEquals("only-id", extractor.extractFromDocument("Awards", d8).get(0).recordTitle());

        assertEquals("https://egreta.uab.cat/admin/workspace.xhtml?uuid=abc+123",
                LinkAuditConstants.buildEgretaWorkspaceUrl("abc 123"));
        assertNull(LinkAuditConstants.buildEgretaWorkspaceUrl(null));
        assertNull(LinkAuditConstants.buildEgretaWorkspaceUrl("   "));
    }

    @Test
    void extractFromDocumentHandlesNullsAndMalformedNestedStructures() {
        Document doc = new Document("_id", "bad-1")
                .append("title", null)
                .append("name", 12345)
                .append("type", "not-a-document")
                .append("documents", java.util.Arrays.asList(
                        null,
                        "not-a-document",
                        new Document("url", "   ").append("file", "also-not-a-doc")
                ))
                .append("links", "not-a-list");

        List<ExtractedLinkRef> refs = assertDoesNotThrow(() -> extractor.extractFromDocument("Awards", doc));
        assertTrue(refs.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractCollectionStreamsWithMinimalProjectionAndRespectsCancellation() {
        MongoTemplate template = mock(MongoTemplate.class);
        MongoCollection<Document> mongoCollection = mock(MongoCollection.class);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        MongoCursor<Document> cursor = mock(MongoCursor.class);

        when(template.getCollection("Awards")).thenReturn(mongoCollection);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.projection(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.batchSize(500)).thenReturn(findIterable);
        when(findIterable.noCursorTimeout(false)).thenReturn(findIterable);
        when(findIterable.iterator()).thenReturn(cursor);

        Document doc1 = new Document("_id", "1")
                .append("links", List.of(new Document("url", "https://example.org/1")));
        Document doc2 = new Document("_id", "2")
                .append("links", List.of(new Document("url", "https://example.org/2")));

        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn(doc1, doc2);

        AtomicInteger checks = new AtomicInteger(0);
        CollectionLinkExtractor streamingExtractor = new CollectionLinkExtractor(template);
        CollectionLinkExtractor.ExtractionResult result =
                streamingExtractor.extractCollection("Awards", () -> checks.getAndIncrement() >= 1);

        assertEquals(1L, result.recordsWithLinks());
        assertEquals(1, result.refs().size());
        assertEquals("https://example.org/1", result.refs().get(0).rawUrl());

        ArgumentCaptor<Bson> projectionCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(findIterable).projection(projectionCaptor.capture());
        Document projectionDoc = (Document) projectionCaptor.getValue();
        assertTrue(projectionDoc.containsKey("documents"));
        assertTrue(projectionDoc.containsKey("links"));
        assertFalse(projectionDoc.containsKey("electronicVersions"));

        verify(findIterable).batchSize(500);
        verify(findIterable).noCursorTimeout(false);
        verify(cursor).close();
    }
}
