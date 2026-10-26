package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Every contract record that carries an OpenAPI component's name holds exactly the members that
 * component declares. Generated records match by construction; this keeps a handwritten one, or a
 * generator change, from adding a member the contract does not have or dropping one it does.
 */
class ContractModelParityTest {
  private static final OpenApiSchemas SPEC = OpenApiSchemas.load();
  private static final String PACKAGE = "com.ezclassify.rest.contract.";

  /** The wire names a record carries: its JSON field constants, or its component names. */
  private static Set<String> wireMembers(Class<?> record) {
    Set<String> constants = new TreeSet<>();
    for (Field field : record.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getName().startsWith("JSON_FIELD_")) {
        try {
          constants.add((String) field.get(null));
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
      }
    }
    if (!constants.isEmpty()) return constants;
    return new TreeSet<>(
        Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList());
  }

  @Test
  void everyContractRecordHoldsTheMembersOfItsComponent() {
    TreeMap<String, String> mismatches = new TreeMap<>();
    int compared = 0;
    for (String name : SPEC.componentNames()) {
      Class<?> type;
      try {
        type = Class.forName(PACKAGE + name);
      } catch (ClassNotFoundException absent) {
        continue;
      }
      if (!type.isRecord()) continue;
      compared++;
      Set<String> declared = SPEC.componentProperties(name);
      Set<String> held = wireMembers(type);
      if (!declared.equals(held)) mismatches.put(name, "contract " + declared + ", record " + held);
    }
    assertTrue(compared > 100, "compared only " + compared + " records");
    assertEquals(new TreeMap<String, String>(), mismatches);
  }
}
