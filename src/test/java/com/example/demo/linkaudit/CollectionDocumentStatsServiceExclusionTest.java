package com.example.demo.linkaudit;

import com.example.demo.service.CollectionDocumentStatsService;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class CollectionDocumentStatsServiceExclusionTest {

    @Test
    @SuppressWarnings("unchecked")
    void getDocumentStatsExcludesInternalAuditAndSystemCollections() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        MongoDatabase db = mock(MongoDatabase.class);
        ListCollectionNamesIterable namesIterable = mock(ListCollectionNamesIterable.class);
        MongoCollection<Document> awardsCollection = mock(MongoCollection.class);
        AggregateIterable<Document> aggregateIterable = mock(AggregateIterable.class);

        when(mongoTemplate.getDb()).thenReturn(db);
        when(db.listCollectionNames()).thenReturn(namesIterable);
        when(namesIterable.into(any())).thenAnswer(inv -> {
            Collection<String> target = inv.getArgument(0);
            target.addAll(List.of(
                    "Awards",
                    LinkAuditConstants.SUMMARY_COLLECTION,
                    LinkAuditConstants.ISSUES_COLLECTION,
                    "system.views"
            ));
            return target;
        });

        when(mongoTemplate.getCollection("Awards")).thenReturn(awardsCollection);
        when(awardsCollection.estimatedDocumentCount()).thenReturn(1L);
        when(awardsCollection.aggregate(anyList())).thenReturn(aggregateIterable);
        when(aggregateIterable.first()).thenReturn(null);

        CollectionDocumentStatsService service = new CollectionDocumentStatsService(mongoTemplate);
        Map<String, Object> result = service.getDocumentStats(true);

        verify(mongoTemplate, times(1)).getCollection("Awards");
        verify(mongoTemplate, never()).getCollection(LinkAuditConstants.SUMMARY_COLLECTION);
        verify(mongoTemplate, never()).getCollection(LinkAuditConstants.ISSUES_COLLECTION);
        verify(mongoTemplate, never()).getCollection("system.views");

        List<Map<String, Object>> collections = (List<Map<String, Object>>) result.get("collections");
        assertEquals(1, collections.size());
        assertEquals("Awards", collections.get(0).get("collection"));
    }
}
