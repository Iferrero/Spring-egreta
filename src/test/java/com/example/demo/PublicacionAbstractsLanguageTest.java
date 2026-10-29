package com.example.demo;

import com.example.demo.service.PublicacionAbstractsService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PublicacionAbstractsLanguageTest {

    @Test
    public void testCatalanAbstractDetection() {
        // Text from the user's screenshot
        String catalanText = "Caracteritzem les equacions diferencials de la forma dy/dx=a(x)y4+b(x)y3+c(x)y2+d(x)y+e(x), "
                + "on a, b, c, d, e són funcions meromòrfiques en la variable x que admeten una primera integral de Weierstrass "
                + "generalitzada o un factor integrador invers de Weierstrass generalitzat. © 2013 Elsevier Ltd. All rights reserved.";

        String detected = PublicacionAbstractsService.detectLanguage(catalanText);
        assertEquals("ca_ES", detected, "Should be detected as Catalan (ca_ES)");
    }

    @Test
    public void testSpanishAbstractDetection() {
        // Spanish counterpart from the screenshot
        String spanishText = "Caracterizamos las ecuaciones diferenciales de la forma dy/dx=a(x)y4+b(x)y3+c(x)y2+d(x)y+e(x), "
                + "donde a, b, c, d, e son funciones meromórficas en la variable x que admite una primera integral generalizada "
                + "de Weierstrass o un factor inverso integrador generalizado de Weierstrass. © 2013 Elsevier Ltd. All rights reserved.";

        String detected = PublicacionAbstractsService.detectLanguage(spanishText);
        assertEquals("es_ES", detected, "Should be detected as Spanish (es_ES)");
    }

    @Test
    public void testEnglishAbstractDetection() {
        // English counterpart from the screenshot
        String englishText = "We characterize the differential equations of the form dy/dx=a(x)y4+b(x)y3+c(x)y2+d(x)y+e(x), "
                + "where a,b,c,d,e are meromorphic functions in the variable x, that admits either a generalized Weierstrass "
                + "first integral or a generalized Weierstrass inverse integrating factor. © 2013 Elsevier Ltd. All rights reserved.";

        String detected = PublicacionAbstractsService.detectLanguage(englishText);
        assertEquals("en_GB", detected, "Should be detected as English (en_GB)");
    }

    @Test
    public void testShortCatalanAcademicAbstract() {
        String ca = "L'objectiu d'aquest article és analitzar la qualitat i la diversitat dels resultats obtinguts en la recerca universitària.";
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage(ca));
    }

    @Test
    public void testShortSpanishAcademicAbstract() {
        String es = "El objetivo de este artículo es analizar la calidad y la diversidad de los resultados obtenidos en la investigación universitaria.";
        assertEquals("es_ES", PublicacionAbstractsService.detectLanguage(es));
    }

    @Test
    public void testShortEnglishAcademicAbstract() {
        String en = "The aim of this paper is to analyze the quality and diversity of the findings obtained in university research.";
        assertEquals("en_GB", PublicacionAbstractsService.detectLanguage(en));
    }

    @Test
    public void testEnglishBibliographicAbstract() {
        String en = "Music and melody as an element of identity in medieval Europe Antoni Rossell European Identity and Cultural Exchanges on the Camino de Santiago: (XI-xvth centuries) / coord. by Santiago López Martínez-Morás, Marina Meléndez Cabo, Gerardo Pérez Barcala, 2013, ISBN 978-84-15876-63-2, pages";
        String detected = PublicacionAbstractsService.detectLanguage(en);
        assertEquals("en_GB", detected, "English abstract with Spanish coordinators should be detected as English (en_GB)");
    }

    @Test
    public void testCatalanBibliographicAbstract() {
        String ca = "La música i la melodia com a element identitari en l'Europa medieval Antoni Rossell Identitat europea i intercanvis culturals en el Camí de Santiago: (segles XI-XV) / coord. per Santiago López Martínez-Morás, Marina Meléndez Cabo, Gerardo Pérez Barcala, 2013, ISBN 978-84-15876-63-2, pàg.";
        String detected = PublicacionAbstractsService.detectLanguage(ca);
        assertEquals("ca_ES", detected, "Catalan abstract with Spanish coordinators should be detected as Catalan (ca_ES)");
    }

    @Test
    public void testCatalanCongressAbstract() {
        String ca = "El campus de la UAB ha acollit el Congrés d’Ètica i Bioètica per segon any consecutiu, una iniciativa d’innovació docent consolidada gràcies a la col·laboració entre les Facultats de Biociències i de Traducció i Interpretació. L&apos;esdeveniment, celebrat el passat 18 de maig, s&apos;ha convertit en un espai de referència per al debat interdisciplinari.";
        String detected = PublicacionAbstractsService.detectLanguage(ca);
        assertEquals("ca_ES", detected, "Congress abstract in Catalan must be detected as ca_ES");
    }

    @Test
    public void testSpanishCongressAbstract() {
        String es = "El campus de la UAB ha acogido el Congreso de Ética y Bioética por segundo año consecutivo, una iniciativa de innovación docente consolidada gracias a la colaboración entre las Facultades de Biociencias y de Traducción e Interpretación. El acontecimiento, celebrado el pasado 18 de mayo, se ha convertido en un espacio de referencia para el debate interdisciplinario.";
        String detected = PublicacionAbstractsService.detectLanguage(es);
        assertEquals("es_ES", detected, "Congress abstract in Spanish must be detected as es_ES");
    }

    @Test
    public void testShortAbstractDetection() {
        // Specific case reported by user (UUID cbb4827d-9a6d-4197-a1e4-8983c90bb9a8)
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage("Guió per a vídeo"));
        assertEquals("es_ES", PublicacionAbstractsService.detectLanguage("Guía para video"));
        assertEquals("en_GB", PublicacionAbstractsService.detectLanguage("Video script"));

        // Database short abstracts
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage("Distribució normal"));
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage("Intervenció al Fòrum"));
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage("Article d'investigació"));
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage("Revista de divulgació"));
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage("Generalitat de Catalunya."));
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage("Resum d'acció"));
        assertEquals("ca_ES", PublicacionAbstractsService.detectLanguage("Presentació a congrés"));

        // Spanish short abstracts
        assertEquals("es_ES", PublicacionAbstractsService.detectLanguage("Distribución normal"));
        assertEquals("es_ES", PublicacionAbstractsService.detectLanguage("Intervención en el Foro"));
        assertEquals("es_ES", PublicacionAbstractsService.detectLanguage("Artículo de investigación"));
    }

    @Test
    public void testFindRealDiscrepancies() {
        try (var client = com.mongodb.client.MongoClients.create("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/kraken?authSource=admin")) {
            var db = client.getDatabase("kraken");
            long start = System.currentTimeMillis();
            java.util.List<org.bson.Document> pipeline = java.util.List.of(
                new org.bson.Document("$match", new org.bson.Document("$and", java.util.List.of(
                    new org.bson.Document("abstract.ca_ES", new org.bson.Document("$exists", true).append("$type", "string")),
                    new org.bson.Document("abstract.es_ES", new org.bson.Document("$exists", true).append("$type", "string")),
                    new org.bson.Document("abstract.en_GB", new org.bson.Document("$exists", true).append("$type", "string"))
                ))),
                new org.bson.Document("$project", new org.bson.Document()
                    .append("uuid", 1)
                    .append("title", 1)
                    .append("len_ca", new org.bson.Document("$strLenCP", "$abstract.ca_ES"))
                    .append("len_es", new org.bson.Document("$strLenCP", "$abstract.es_ES"))
                    .append("len_en", new org.bson.Document("$strLenCP", "$abstract.en_GB"))
                ),
                new org.bson.Document("$project", new org.bson.Document()
                    .append("uuid", 1)
                    .append("title", 1)
                    .append("len_ca", 1)
                    .append("len_es", 1)
                    .append("len_en", 1)
                    .append("diff_len", new org.bson.Document("$subtract", java.util.List.of(
                        new org.bson.Document("$max", java.util.List.of("$len_ca", "$len_es", "$len_en")),
                        new org.bson.Document("$min", java.util.List.of("$len_ca", "$len_es", "$len_en"))
                    )))
                ),
                new org.bson.Document("$match", new org.bson.Document("diff_len", new org.bson.Document("$gte", 300))),
                new org.bson.Document("$sort", new org.bson.Document("diff_len", -1)),
                new org.bson.Document("$limit", 15)
            );

            var results = db.getCollection("Researchoutputs").aggregate(pipeline).into(new java.util.ArrayList<>());
            long duration = System.currentTimeMillis() - start;
            System.out.printf("FOUND %d TOP DISCREPANCIES IN %d ms%n", results.size(), duration);
            for (var d : results) {
                System.out.printf("UUID: %s | diff_len=%d | CA=%d, ES=%d, EN=%d%n",
                    d.getString("uuid"), d.getInteger("diff_len"), d.getInteger("len_ca"), d.getInteger("len_es"), d.getInteger("len_en"));
            }
        }
    }
}
