package com.example.demo;

import com.example.demo.service.PublicacionAbstractsService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class AbstractHtmlBlankTest {

    public static final String HTML_EMPTY_REGEX = "^([\\s\u00a0\ufeff\u200b]|<[^>]*>|&(?:nbsp|#160|#xA0|#xa0|ensp|emsp|thinsp|zwnj|zwj|amp;nbsp);?)*$";
    public static final Pattern HTML_EMPTY_PATTERN = Pattern.compile(HTML_EMPTY_REGEX, Pattern.CASE_INSENSITIVE);

    @Autowired
    private PublicacionAbstractsService abstractsService;

    private boolean isHtmlEmptyRegex(String text) {
        if (text == null || text.isBlank()) return true;
        return HTML_EMPTY_PATTERN.matcher(text).matches();
    }

    private boolean isHtmlEmptyJava(String text) {
        if (text == null || text.isBlank()) return true;
        String stripped = text.replaceAll("<[^>]*>", " ");
        stripped = stripped.replaceAll("&(?:nbsp|#160|#xA0|#xa0|ensp|emsp|thinsp|zwnj|zwj|amp;nbsp);?", " ")
                           .replace('\u00A0', ' ')
                           .replace('\uFEFF', ' ')
                           .replace('\u200B', ' ');
        return stripped.trim().isEmpty();
    }

    @Test
    public void testEmptyHtmlCases() {
        String[] emptyCases = {
            null,
            "",
            "   ",
            "<p></p>",
            "<p><br></p>",
            "<p><br/></p>",
            "<p><br /></p>",
            "<p>&nbsp;</p>",
            "<p>&nbsp</p>",
            "<p>&nbsp; &nbsp;</p>",
            "<p>&#160;</p>",
            "<p>&#xA0;</p>",
            "<p>&#xa0;</p>",
            "<p>\n<br>\n</p>",
            "<p><span style=\"font-size: 10pt;\"></span></p>",
            "<div><p><br></p></div>",
            "<br>",
            "<br/>",
            "<br />",
            "<p>&emsp;&ensp;&thinsp;</p>",
            "<p>&zwnj;&zwj;</p>",
            "<p>\u00A0</p>",
            "<p>\uFEFF</p>",
            "<p>&amp;nbsp;</p>",
            "<span> </span>",
            "<p>   \n\t   </p>"
        };

        for (String c : emptyCases) {
            assertTrue(isHtmlEmptyRegex(c), "Regex should consider empty: [" + c + "]");
            assertTrue(isHtmlEmptyJava(c), "Java should consider empty: [" + c + "]");
        }
    }

    @Test
    public void testNonEmptyHtmlCases() {
        String[] nonEmptyCases = {
            "Hello",
            "<p>Text</p>",
            "<p> Text </p>",
            "<p>&nbsp;Text&nbsp;</p>",
            "Text without tags",
            "<p>123</p>",
            "<p>a</p>",
            "<p>.</p>",
            "<p>Hello <br> World</p>",
            "<p>A &amp; B</p>",
            "<p>&amp;</p>",
            "<b>Abstract</b>"
        };

        for (String c : nonEmptyCases) {
            assertFalse(isHtmlEmptyRegex(c), "Regex should NOT consider empty: [" + c + "]");
            assertFalse(isHtmlEmptyJava(c), "Java should NOT consider empty: [" + c + "]");
        }
    }

    @Autowired
    private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;

    @Test
    public void testInspectAiAbstractsCollection() {
        for (String colName : mongoTemplate.getCollectionNames()) {
            if (colName.toLowerCase().contains("aiabstract") || colName.toLowerCase().contains("researchoutput")) {
                long count = mongoTemplate.getCollection(colName).countDocuments();
                System.out.println("COLLECTION: " + colName + " -> COUNT: " + count);
            }
        }

        // Try exact name or case variations
        String targetCol = null;
        for (String name : mongoTemplate.getCollectionNames()) {
            if (name.equalsIgnoreCase("researchoutputsaiabstracts")) {
                targetCol = name;
                break;
            }
        }
    }

    @Test
    public void testMongoAggregationExecutionWithService() {
        assertDoesNotThrow(() -> {
            var res = abstractsService.getAbstractsByYear(null, null, "submissionYear");
            assertNotNull(res);
        });

        assertDoesNotThrow(() -> {
            var stats = abstractsService.getAbstractStats(null, null, null);
            assertNotNull(stats);
        });
    }
}



