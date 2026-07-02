package com.example.demo;

import com.example.demo.controller.EgretaEditorController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class EgretaEditorControllerTest {

    private EgretaEditorController controller;
    private Method edicioDadesMethod;

    @BeforeEach
    public void setUp() throws Exception {
        controller = new EgretaEditorController(); // use default no-args constructor
        edicioDadesMethod = EgretaEditorController.class.getDeclaredMethod(
                "edicioDades", Map.class, String.class, String.class, String.class, String.class, String.class);
        edicioDadesMethod.setAccessible(true);
    }

    private boolean invokeEdicioDades(Map<String, Object> data, String clave, String valor,
                                      String dictType, String dictName, String dictValue) {
        try {
            return (boolean) edicioDadesMethod.invoke(controller, data, clave, valor, dictType, dictName, dictValue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testSimpleCamp() {
        Map<String, Object> data = new LinkedHashMap<>();
        
        // 1. Not exists
        boolean changed1 = invokeEdicioDades(data, "uri", "new_val", null, null, null);
        assertTrue(changed1);
        assertEquals("new_val", data.get("uri"));

        // 2. Exists but different
        boolean changed2 = invokeEdicioDades(data, "uri", "another_val", "", null, null);
        assertTrue(changed2);
        assertEquals("another_val", data.get("uri"));

        // 3. Exists and same
        boolean changed3 = invokeEdicioDades(data, "uri", "another_val", null, null, null);
        assertFalse(changed3);
    }

    @Test
    public void testDictType() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 1. dictValue is null, different value
        boolean changed1 = invokeEdicioDades(data, "nature", "gac", "dict", "natureTypes", null);
        assertTrue(changed1);
        Map<String, Object> natureTypes = (Map<String, Object>) data.get("natureTypes");
        assertNotNull(natureTypes);
        assertEquals("gac", natureTypes.get("nature"));

        // 2. dictValue is null, same value
        boolean changed2 = invokeEdicioDades(data, "nature", "gac", "dict", "natureTypes", "");
        assertFalse(changed2);

        // 3. dictValue is not null, actual is not equal to dictValue (should edit)
        boolean changed3 = invokeEdicioDades(data, "nature", "bec", "dict", "natureTypes", "different_value");
        assertTrue(changed3);
        assertEquals("bec", natureTypes.get("nature"));

        // 4. dictValue is not null, actual is equal to dictValue (should NOT edit)
        boolean changed4 = invokeEdicioDades(data, "nature", "gac", "dict", "natureTypes", "bec");
        assertFalse(changed4);
        assertEquals("bec", natureTypes.get("nature"));
    }

    @Test
    public void testListTypeNewContainer() {
        Map<String, Object> data = new LinkedHashMap<>();

        // Container does not exist, clave has "."
        boolean changed = invokeEdicioDades(data, "role.uri", "supervisor_role", "list", "supervisors", null);
        assertTrue(changed);
        List<Map<String, Object>> supervisors = (List<Map<String, Object>>) data.get("supervisors");
        assertNotNull(supervisors);
        assertEquals(1, supervisors.size());
        Map<String, Object> subMap = (Map<String, Object>) supervisors.get(0).get("role");
        assertEquals("supervisor_role", subMap.get("uri"));
    }

    @Test
    public void testListTypeDotNotation() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> supervisors = new ArrayList<>();
        data.put("supervisors", supervisors);

        // 1. Element doesn't have key[0]
        Map<String, Object> elem1 = new LinkedHashMap<>();
        supervisors.add(elem1);
        boolean changed1 = invokeEdicioDades(data, "role.uri", "role1", "list", "supervisors", null);
        assertTrue(changed1);
        Map<String, Object> roleMap1 = (Map<String, Object>) elem1.get("role");
        assertEquals("role1", roleMap1.get("uri"));

        // 2. Element has key[0] but key[1] is null
        Map<String, Object> elem2 = new LinkedHashMap<>();
        elem2.put("role", new LinkedHashMap<String, Object>());
        supervisors.clear();
        supervisors.add(elem2);
        boolean changed2 = invokeEdicioDades(data, "role.uri", "role2", "list", "supervisors", null);
        assertTrue(changed2);
        Map<String, Object> roleMap2 = (Map<String, Object>) elem2.get("role");
        assertEquals("role2", roleMap2.get("uri"));

        // 3. Element has key[0].key[1] and dictValue matches
        Map<String, Object> elem3 = new LinkedHashMap<>();
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("uri", "old_role");
        elem3.put("role", inner);
        supervisors.clear();
        supervisors.add(elem3);
        boolean changed3 = invokeEdicioDades(data, "role.uri", "new_role", "list", "supervisors", "old_role");
        assertTrue(changed3);
        assertEquals("new_role", inner.get("uri"));

        // 4. Element has key[0].key[1] and dictValue does NOT match (should continue/not edit)
        boolean changed4 = invokeEdicioDades(data, "role.uri", "another_role", "list", "supervisors", "different_role");
        assertFalse(changed4);
        assertEquals("new_role", inner.get("uri"));
    }

    @Test
    public void testListTypeSimpleNotation() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        data.put("registros", list);

        // 1. dictValue != null and matches a value in the element
        Map<String, Object> elem1 = new LinkedHashMap<>();
        elem1.put("id", "target");
        elem1.put("uri", "old_uri");
        list.add(elem1);
        boolean changed1 = invokeEdicioDades(data, "uri", "new_uri", "list", "registros", "target");
        assertTrue(changed1);
        assertEquals("new_uri", elem1.get("uri"));

        // 2. elemento[clave] == valor (already has target value)
        boolean changed2 = invokeEdicioDades(data, "uri", "new_uri", "list", "registros", null);
        assertFalse(changed2);

        // 3. list size is 1, change the value
        elem1.put("uri", "another_old_uri");
        boolean changed3 = invokeEdicioDades(data, "uri", "new_uri", "list", "registros", null);
        assertTrue(changed3);
        assertEquals("new_uri", elem1.get("uri"));

        // 4. list size > 1, no match for dictValue, should append new element
        Map<String, Object> elem2 = new LinkedHashMap<>();
        elem2.put("uri", "some_other");
        list.add(elem2); // list size is now 2
        
        boolean changed4 = invokeEdicioDades(data, "uri", "appended_uri", "list", "registros", "non_existent_dict_value");
        assertTrue(changed4);
        assertEquals(3, list.size());
        assertEquals("appended_uri", list.get(2).get("uri"));
    }
}
