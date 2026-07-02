package com.example.demo;

import com.example.demo.controller.IngressosController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class IngressosControllerTest {

    @Autowired
    private IngressosController controller;

    @Test
    public void testGetIngressosData() {
        List<IngressosController.IngresItem> data = controller.getIngressosData();
        assertNotNull(data);
        assertFalse(data.isEmpty());
        
        // Debe tener 33 elementos (filas del Excel mapeadas)
        assertEquals(33, data.size());

        // Verificar el primer elemento
        IngressosController.IngresItem first = data.get(0);
        assertEquals("PS Recerca (Cap. 3)  No competitiva", first.getConcept());
        assertEquals("PS Recerca", first.getCategory());
        assertTrue(first.isGroupHeader());
        assertNotNull(first.getValues());
        assertEquals(14482627.22, first.getValues().get("2018"), 0.01);
        assertEquals(12298354.82, first.getValues().get("2025"), 0.01);

        // Verificar el segundo elemento (Personal Investigador - Group Header)
        IngressosController.IngresItem second = data.get(1);
        assertEquals("Personal Investigador", second.getConcept());
        assertEquals("Personal Investigador", second.getCategory());
        assertTrue(second.isGroupHeader());
        assertEquals(8974031.37, second.getValues().get("2018"), 0.01);
        assertEquals(16546967.22, second.getValues().get("2025"), 0.01);

        // Verificar un elemento intermedio (Ramon y Cajal)
        IngressosController.IngresItem ryCAEI = data.get(6); // index 6 es Ramon y Cajal (NF) - AEI
        assertEquals("Ramon y Cajal (NF) - AEI", ryCAEI.getConcept());
        assertEquals("Personal Investigador", ryCAEI.getCategory());
        assertFalse(ryCAEI.isGroupHeader());
        assertEquals(669599.95, ryCAEI.getValues().get("2018"), 0.01);

        // Verificar el último elemento
        IngressosController.IngresItem last = data.get(32);
        assertEquals("Total general", last.getConcept());
        assertEquals("General", last.getCategory());
        assertTrue(last.isGroupHeader());
        assertEquals(49636583.61, last.getValues().get("2018"), 0.01);
        assertEquals(75821924.04, last.getValues().get("2025"), 0.01);
    }
}
