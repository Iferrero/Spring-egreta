package com.example.demo.linkaudit.extractor;

import com.example.demo.linkaudit.LinkAuditConstants;
import com.example.demo.linkaudit.model.ExtractedLinkRef;
import com.example.demo.linkaudit.model.LinkSourceField;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Streams MongoDB collection documents using minimal projections and extracts
 * link references from {@code documents[]}, {@code links[]}, and (for
 * {@code Researchoutputs}) {@code electronicVersions[]}.
 */
@Component
public class CollectionLinkExtractor {

    private static final int CURSOR_BATCH_SIZE = 500;
    private static final String RESEARCH_OUTPUTS_COLLECTION = "Researchoutputs";
    private static final List<String> LOCALE_ORDER = List.of("ca_ES", "es_ES", "en_GB");

    private final MongoTemplate mongoTemplate;

    public CollectionLinkExtractor(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public record ExtractionResult(long recordsWithLinks, List<ExtractedLinkRef> refs) {
    }

    /**
     * Streams all documents in {@code collection} that contain non-empty link arrays,
     * stopping early if {@code cancelled} returns {@code true}.
     */
    public ExtractionResult extractCollection(String collection, BooleanSupplier cancelled) {
        boolean includeElectronicVersions = RESEARCH_OUTPUTS_COLLECTION.equalsIgnoreCase(collection);
        Document filter = buildFilter(includeElectronicVersions);
        Document projection = buildProjection(includeElectronicVersions);

        MongoCollection<Document> mongoCollection = mongoTemplate.getCollection(collection);
        FindIterable<Document> iterable = mongoCollection.find(filter)
                .projection(projection)
                .batchSize(CURSOR_BATCH_SIZE)
                .noCursorTimeout(false);

        List<ExtractedLinkRef> refs = new ArrayList<>();
        long recordsWithLinks = 0;

        try (MongoCursor<Document> cursor = iterable.iterator()) {
            while ((cancelled == null || !cancelled.getAsBoolean()) && cursor.hasNext()) {
                Document doc = cursor.next();
                List<ExtractedLinkRef> docRefs = extractFromDocument(collection, doc);
                if (!docRefs.isEmpty()) {
                    recordsWithLinks++;
                    refs.addAll(docRefs);
                }
            }
        }

        return new ExtractionResult(recordsWithLinks, refs);
    }

    /**
     * Extracts all link references from a single MongoDB {@link Document}.
     */
    public List<ExtractedLinkRef> extractFromDocument(String collection, Document doc) {
        if (doc == null) {
            return List.of();
        }

        RecordContext ctx = resolveRecordContext(collection, doc);
        List<ExtractedLinkRef> refs = new ArrayList<>();

        extractFromDocumentsArray(doc.get(LinkSourceField.DOCUMENTS.getFieldName()), ctx, refs);
        extractFromLinksArray(doc.get(LinkSourceField.LINKS.getFieldName()), ctx, refs);

        if (RESEARCH_OUTPUTS_COLLECTION.equalsIgnoreCase(collection)) {
            extractFromElectronicVersionsArray(doc.get(LinkSourceField.ELECTRONIC_VERSIONS.getFieldName()), ctx, refs);
        }

        return refs;
    }

    private Document buildFilter(boolean includeElectronicVersions) {
        List<Document> conditions = new ArrayList<>();
        conditions.add(nonEmptyArrayCondition(LinkSourceField.DOCUMENTS.getFieldName()));
        conditions.add(nonEmptyArrayCondition(LinkSourceField.LINKS.getFieldName()));
        if (includeElectronicVersions) {
            conditions.add(nonEmptyArrayCondition(LinkSourceField.ELECTRONIC_VERSIONS.getFieldName()));
        }
        return new Document("$or", conditions);
    }

    private Document nonEmptyArrayCondition(String fieldName) {
        return new Document(fieldName, new Document("$exists", true).append("$ne", List.of()));
    }

    private Document buildProjection(boolean includeElectronicVersions) {
        Document projection = new Document("_id", 1)
                .append("uuid", 1)
                .append("pureId", 1)
                .append("title", 1)
                .append("name", 1)
                .append("type", 1)
                .append("typeDiscriminator", 1)
                .append(LinkSourceField.DOCUMENTS.getFieldName(), 1)
                .append(LinkSourceField.LINKS.getFieldName(), 1);
        if (includeElectronicVersions) {
            projection.append(LinkSourceField.ELECTRONIC_VERSIONS.getFieldName(), 1);
        }
        return projection;
    }

    private void extractFromDocumentsArray(Object rawArray, RecordContext ctx, List<ExtractedLinkRef> out) {
        for (Document el : asDocumentList(rawArray)) {
            Document fileDoc = asDocument(el.get("file"));
            Set<String> urls = new LinkedHashSet<>();
            addIfNonBlankRaw(urls, el.get("url"));
            addIfNonBlankRaw(urls, el.get("fileURL"));
            addIfNonBlankRaw(urls, el.get("fileUrl"));
            addIfNonBlankRaw(urls, el.get("link"));
            if (fileDoc != null) {
                addIfNonBlankRaw(urls, fileDoc.get("url"));
                addIfNonBlankRaw(urls, fileDoc.get("fileURL"));
            }
            if (urls.isEmpty()) {
                continue;
            }

            String subType = firstNonBlank(
                    resolveTermText(el, "type"),
                    LinkAuditConstants.UNSPECIFIED_LABEL
            );
            String linkLabel = firstNonBlank(
                    resolveText(el.get("title")),
                    asNonBlankTrimmed(el.get("fileName")),
                    fileDoc != null ? asNonBlankTrimmed(fileDoc.get("fileName")) : null,
                    ""
            );

            appendRefs(out, ctx, LinkSourceField.DOCUMENTS.getFieldName(), subType, linkLabel, urls);
        }
    }

    private void extractFromLinksArray(Object rawArray, RecordContext ctx, List<ExtractedLinkRef> out) {
        for (Document el : asDocumentList(rawArray)) {
            String url = asNonBlankRaw(el.get("url"));
            if (url == null) {
                continue;
            }

            String subType = firstNonBlank(
                    resolveTermText(el, "linkType"),
                    LinkAuditConstants.UNSPECIFIED_LABEL
            );
            String linkLabel = firstNonBlank(
                    asNonBlankTrimmed(el.get("alias")),
                    resolveLocalizedText(asDocument(el.get("description"))),
                    ""
            );

            appendRefs(out, ctx, LinkSourceField.LINKS.getFieldName(), subType, linkLabel, List.of(url));
        }
    }

    private void extractFromElectronicVersionsArray(Object rawArray, RecordContext ctx, List<ExtractedLinkRef> out) {
        for (Document el : asDocumentList(rawArray)) {
            Document fileDoc = asDocument(el.get("file"));
            Set<String> urls = new LinkedHashSet<>();
            // Only extract explicit URLs; never synthesize https://doi.org/ from bare doi fields.
            addIfNonBlankRaw(urls, el.get("link"));
            if (fileDoc != null) {
                addIfNonBlankRaw(urls, fileDoc.get("url"));
                addIfNonBlankRaw(urls, fileDoc.get("fileURL"));
            }
            if (urls.isEmpty()) {
                continue;
            }

            String subType = firstNonBlank(
                    resolveTermText(el, "versionType"),
                    asNonBlankTrimmed(el.get("typeDiscriminator")),
                    LinkAuditConstants.UNSPECIFIED_LABEL
            );
            String linkLabel = firstNonBlank(
                    fileDoc != null ? asNonBlankTrimmed(fileDoc.get("fileName")) : null,
                    resolveText(el.get("title")),
                    ""
            );

            appendRefs(out, ctx, LinkSourceField.ELECTRONIC_VERSIONS.getFieldName(), subType, linkLabel, urls);
        }
    }

    private void appendRefs(List<ExtractedLinkRef> out,
                            RecordContext ctx,
                            String sourceField,
                            String subType,
                            String linkLabel,
                            Collection<String> urls) {
        for (String rawUrl : urls) {
            out.add(new ExtractedLinkRef(
                    ctx.collection(),
                    ctx.recordId(),
                    ctx.recordUuid(),
                    ctx.recordPureId(),
                    ctx.recordTitle(),
                    ctx.recordType(),
                    sourceField,
                    subType,
                    linkLabel,
                    rawUrl
            ));
        }
    }

    private RecordContext resolveRecordContext(String collection, Document doc) {
        String recordId = Objects.toString(doc.get("_id"), "");
        String recordUuid = asNonBlankTrimmed(doc.get("uuid"));
        Long recordPureId = doc.get("pureId") instanceof Number num ? num.longValue() : null;
        String recordTitle = resolveRecordTitle(doc, recordUuid, recordId);
        String recordType = firstNonBlank(
                resolveTermText(doc, "type"),
                asNonBlankTrimmed(doc.get("typeDiscriminator")),
                LinkAuditConstants.UNSPECIFIED_LABEL
        );
        return new RecordContext(collection, recordId, recordUuid, recordPureId, recordTitle, recordType);
    }

    private String resolveRecordTitle(Document doc, String recordUuid, String recordId) {
        Object titleObj = doc.get("title");
        Document titleDoc = asDocument(titleObj);
        if (titleDoc != null) {
            String valueTitle = asNonBlankTrimmed(titleDoc.get("value"));
            if (valueTitle != null) {
                return valueTitle;
            }
            String localizedTitle = resolveLocalizedText(titleDoc);
            if (localizedTitle != null) {
                return localizedTitle;
            }
        }
        String stringTitle = asNonBlankTrimmed(titleObj);
        if (stringTitle != null) {
            return stringTitle;
        }

        Object nameObj = doc.get("name");
        Document nameDoc = asDocument(nameObj);
        if (nameDoc != null) {
            String localizedName = resolveLocalizedText(nameDoc);
            if (localizedName != null) {
                return localizedName;
            }
            String fullName = joinNonBlank(
                    asNonBlankTrimmed(nameDoc.get("firstName")),
                    asNonBlankTrimmed(nameDoc.get("lastName"))
            );
            if (fullName != null) {
                return fullName;
            }
        }
        String stringName = asNonBlankTrimmed(nameObj);
        if (stringName != null) {
            return stringName;
        }

        return firstNonBlank(recordUuid, recordId);
    }

    private String resolveTermText(Document container, String wrapperKey) {
        if (container == null) {
            return null;
        }
        Document wrapper = asDocument(container.get(wrapperKey));
        if (wrapper == null) {
            return null;
        }
        return resolveLocalizedText(asDocument(wrapper.get("term")));
    }

    private String resolveText(Object value) {
        String str = asNonBlankTrimmed(value);
        if (str != null) {
            return str;
        }
        return resolveLocalizedText(asDocument(value));
    }

    private String resolveLocalizedText(Document localizedDoc) {
        if (localizedDoc == null) {
            return null;
        }
        for (String locale : LOCALE_ORDER) {
            String value = asNonBlankTrimmed(localizedDoc.get(locale));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private List<Document> asDocumentList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Document> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Document doc) {
                result.add(doc);
            }
        }
        return result;
    }

    private Document asDocument(Object raw) {
        return raw instanceof Document doc ? doc : null;
    }

    private void addIfNonBlankRaw(Set<String> urls, Object candidate) {
        String raw = asNonBlankRaw(candidate);
        if (raw != null) {
            urls.add(raw);
        }
    }

    private String asNonBlankRaw(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private String asNonBlankTrimmed(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private String joinNonBlank(String first, String second) {
        if (first != null && second != null) {
            return first + " " + second;
        }
        return first != null ? first : second;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private record RecordContext(
            String collection,
            String recordId,
            String recordUuid,
            Long recordPureId,
            String recordTitle,
            String recordType
    ) {
    }
}
