package com.example.demo.util;

import org.bson.Document;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Date;
import java.util.List;

public class MongoPipelineBuilder {

    private final List<Document> pipeline;

    public MongoPipelineBuilder(List<Document> pipeline) {
        this.pipeline = pipeline;
    }

    public MongoPipelineBuilder replaceMatch(String field, Object value) {

        if (value == null) {
            return this;
        }

        for (Document stage : pipeline) {

            Document match = stage.get("$match", Document.class);

            if (match != null && match.containsKey(field)) {
                match.put(field, value);
            }
        }

        return this;
    }

    public MongoPipelineBuilder match(String field, Object value) {

        if (value == null) {
            return this;
        }

        pipeline.add(new Document("$match",
                new Document(field, value)));

        return this;
    }

    public MongoPipelineBuilder awardDateBetween(Integer desde, Integer hasta) {

        if (desde == null && hasta == null) {
            return this;
        }

        Document range = new Document();

        if (desde != null) {

            range.put("$gte",
                    Date.from(LocalDate.of(desde, 1, 1)
                            .atStartOfDay()
                            .toInstant(ZoneOffset.UTC)));
        }

        if (hasta != null) {

            range.put("$lte",
                    Date.from(LocalDate.of(hasta, 12, 31)
                            .atTime(23,59,59)
                            .toInstant(ZoneOffset.UTC)));
        }

        pipeline.add(1,
                new Document("$match",
                        new Document("awardDate", range)));

        return this;
    }

    public MongoPipelineBuilder vigenciaYears(Integer desde, Integer hasta) {

        int minYear = desde != null ? desde : 1900;
        int maxYear = hasta != null ? hasta : 9999;

        pipeline.add(1,
                new Document("$addFields",
                        new Document("startYear",
                                new Document("$year", "$actualPeriod.startDate"))));

        pipeline.add(2,
                new Document("$addFields",
                        new Document("endYear",
                                new Document("$year", "$actualPeriod.endDate"))));

        pipeline.add(3,
                new Document("$match",
                        new Document("$expr",
                                new Document("$and", List.of(
                                        new Document("$lte", List.of("$startYear", maxYear)),
                                        new Document("$gte", List.of("$endYear", minYear))
                                )))));

        return this;
    }

    public List<Document> build() {
        return pipeline;
    }

    public MongoPipelineBuilder matchIn(String field, Collection<?> values) {

    if (values == null || values.isEmpty()) {
        return this;
    }

    pipeline.add(new Document("$match",
            new Document(field,
                    new Document("$in", values))));

    return this;
}

public MongoPipelineBuilder matchInBeforeFirstGroup(String field, Collection<?> values) {
    if (values == null || values.isEmpty()) {
        return this;
    }

    Document matchStage = new Document(
            "$match",
            new Document(field, new Document("$in", values))
    );

    int insertIndex = pipeline.size();

    for (int i = 0; i < pipeline.size(); i++) {
        if (pipeline.get(i).containsKey("$group")) {
            insertIndex = i;
            break;
        }
    }

    pipeline.add(insertIndex, matchStage);
    return this;
}

// En MongoPipelineBuilder
public MongoPipelineBuilder matchInBeforeFirstProject(String field, Collection<?> values) {
    if (values == null || values.isEmpty()) return this;

    Document matchStage = new Document(
        "$match",
        new Document(field, new Document("$in", values))
    );

    int insertIndex = pipeline.size();
    for (int i = 0; i < pipeline.size(); i++) {
        if (pipeline.get(i).containsKey("$project")) {
            insertIndex = i;
            break;
        }
    }

    pipeline.add(insertIndex, matchStage);
    return this;
}

/**
 * Filtra un array field para quedarse solo con elementos cuyo subcampo
 * coincida con alguno de los valores dados. Se inserta antes del primer $group.
 */
public MongoPipelineBuilder filterArrayBeforeFirstGroup(String arrayField, String subField, Collection<?> values) {
    if (values == null || values.isEmpty()) {
        return this;
    }

    // $addFields con $filter sobre el array
    Document filterExpr = new Document("$filter", new Document()
            .append("input", "$" + arrayField)
            .append("as", "item")
            .append("cond", new Document("$in",
                    List.of("$$item." + subField.replace(arrayField + ".", ""), values))));

    Document addFieldsStage = new Document("$addFields",
            new Document(arrayField, filterExpr));

    // $match para excluir documentos donde el array quedó vacío
    Document matchStage = new Document("$match",
            new Document(arrayField, new Document("$ne", List.of())));

    int insertIndex = pipeline.size();
    for (int i = 0; i < pipeline.size(); i++) {
        if (pipeline.get(i).containsKey("$group")) {
            insertIndex = i;
            break;
        }
    }

    pipeline.add(insertIndex, matchStage);
    pipeline.add(insertIndex, addFieldsStage);

    return this;
}

public MongoPipelineBuilder matchManagingOrg(String deptUuid) {
    if (deptUuid == null || deptUuid.isBlank()) {
        return this;
    }

    // OR entre managingOrganization y coManagingOrganizations
    Document matchStage = new Document("$match", new Document("$or", List.of(
            new Document("managingOrganization.uuid", deptUuid),
            new Document("coManagingOrganizations.uuid", deptUuid)
    )));

    // Insertar justo después del $match de workflow.step (posición 1)
    pipeline.add(1, matchStage);

    return this;
}


}