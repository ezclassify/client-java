package com.ezclassify.rest.client;

import static org.junit.jupiter.api.Assertions.*;

import com.ezclassify.rest.contract.Assertion;
import com.ezclassify.rest.contract.AssertionConfidence;
import com.ezclassify.rest.contract.ProcessingContext;
import com.ezclassify.rest.contract.ProcessingRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationOptionsTest {
  private static Assertion fact(Map<String, Object> metadata) {
    return new Assertion(
        "level", "H", AssertionConfidence.CERTAIN, null, metadata, null, null, "user", null, null);
  }

  @Test
  void builtListsRemainStableAfterCallerAndBuilderMutation() {
    List<String> outputs = new ArrayList<>(List.of("marking/short"));
    var builder =
        ProcessOptions.builder().outputs(outputs).parts(List.of()).suggestFor(List.of("level"));
    var options = builder.build();
    outputs.add("marking/long");
    builder.outputs(List.of("marking/long"));
    assertEquals(List.of("marking/short"), options.outputs());
    assertEquals(List.of(), options.parts());
    assertThrows(UnsupportedOperationException.class, () -> options.outputs().add("other"));
  }

  @Test
  void assertionsAndWholeContextAreDefensiveWireSnapshots() {
    List<String> values = new ArrayList<>(List.of("original"));
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("labels", values);
    metadata.put("count", 7);
    int[] flags = {1, 2};
    metadata.put("flags", flags);
    var extra = new com.google.gson.JsonObject();
    extra.addProperty("label", "original");
    metadata.put("extra", extra);
    var expected = WireJson.toTree(fact(metadata)).deepCopy();
    List<Assertion> assertions = new ArrayList<>(List.of(fact(metadata)));
    var context =
        new ProcessingContext(ProcessingRole.PART, new ProcessingContext.PartOf("H", assertions));
    var options = ProcessOptions.builder().assertions(assertions).context(context).build();
    values.add("changed");
    flags[0] = 4;
    extra.addProperty("label", "edited");
    assertions.clear();
    assertEquals(List.of("original"), options.assertions().get(0).metadata().get("labels"));
    assertEquals(1, options.context().partOf().assertions().size());
    assertEquals(7, options.assertions().get(0).metadata().get("count"));
    assertEquals(expected, WireJson.toTree(options.assertions().get(0)));
    assertThrows(
        UnsupportedOperationException.class,
        () -> options.assertions().get(0).metadata().put("other", "changed"));
    assertThrows(
        UnsupportedOperationException.class, () -> options.context().partOf().assertions().clear());
    assertFalse(options.assertions().get(0).metadata().containsKey("other"));
    assertEquals(1, options.context().partOf().assertions().size());
  }

  @Test
  void omittedEmptyAndFalseChoicesRemainDistinct() {
    assertNull(ProcessOptions.defaults().outputs());
    assertEquals(List.of(), ProcessOptions.builder().outputs(List.of()).build().outputs());
    assertNull(SuggestOptions.defaults().includeGated());
    assertEquals(
        Boolean.FALSE, SuggestOptions.builder().includeGated(false).build().includeGated());
  }

  @Test
  void optionListsRejectNullMembersAtTheBoundary() {
    List<String> outputs = new ArrayList<>();
    outputs.add(null);
    assertThrows(
        NullPointerException.class, () -> ProcessOptions.builder().outputs(outputs).build());
  }

  private static final class MutableNumber extends Number {
    private int count;

    private MutableNumber(int count) {
      this.count = count;
    }

    @Override
    public int intValue() {
      return count;
    }

    @Override
    public long longValue() {
      return count;
    }

    @Override
    public float floatValue() {
      return count;
    }

    @Override
    public double doubleValue() {
      return count;
    }

    @Override
    public String toString() {
      return Integer.toString(count);
    }
  }

  @Test
  void mutableNumbersAreDetachedAtBuildAndEveryAccessorIncludingContext() {
    var number = new MutableNumber(7);
    var primitiveNumber = new java.util.concurrent.atomic.AtomicInteger(13);
    var tree = new com.google.gson.JsonObject();
    tree.add("value", new com.google.gson.JsonPrimitive(primitiveNumber));
    var metadata = new LinkedHashMap<String, Object>();
    metadata.put("custom", number);
    metadata.put("nested", List.of(Map.of("primitive", tree)));
    var original = fact(metadata);
    var expected = WireJson.toTree(original).toString();
    var context =
        new ProcessingContext(
            ProcessingRole.PART, new ProcessingContext.PartOf("H", List.of(original)));
    var process = ProcessOptions.builder().assertions(List.of(original)).context(context).build();
    var explain = ExplainOptions.builder().assertions(List.of(original)).build();
    var validate = ValidateOptions.builder().context(context).build();
    number.count = 99;
    primitiveNumber.set(42);
    assertEquals(expected, WireJson.toTree(process.assertions().get(0)).toString());
    assertEquals(expected, WireJson.toTree(explain.assertions().get(0)).toString());
    assertEquals(
        expected, WireJson.toTree(process.context().partOf().assertions().get(0)).toString());
    assertEquals(
        expected, WireJson.toTree(validate.context().partOf().assertions().get(0)).toString());
    var returned =
        (com.google.gson.JsonObject) process.assertions().get(0).metadata().get("custom");
    returned.addProperty("count", 100);
    var returnedContext =
        (com.google.gson.JsonObject)
            validate.context().partOf().assertions().get(0).metadata().get("custom");
    returnedContext.addProperty("count", 101);
    assertEquals(expected, WireJson.toTree(process.assertions().get(0)).toString());
    assertEquals(
        expected, WireJson.toTree(validate.context().partOf().assertions().get(0)).toString());
  }

  @Test
  void mutableMapKeysAreDetachedAtBuildAndEveryAccessorIncludingContext() {
    var number = new MutableNumber(7);
    var text = new StringBuilder("original");
    var nested = new LinkedHashMap<Object, Object>();
    nested.put(number, "number-key");
    nested.put(text, "text-key");
    nested.put(new java.math.BigDecimal("1.2300"), "decimal-key");
    nested.put(new java.math.BigInteger("123456789012345678901234567890"), "integer-key");
    nested.put(null, "null-key");
    nested.put(false, "boolean-key");
    var original = fact(Map.of("nested", nested));
    String expected = WireJson.toTree(original).toString();
    var context =
        new ProcessingContext(
            ProcessingRole.PART, new ProcessingContext.PartOf("H", List.of(original)));
    var process = ProcessOptions.builder().assertions(List.of(original)).context(context).build();
    var explain = ExplainOptions.builder().assertions(List.of(original)).build();
    var validate = ValidateOptions.builder().context(context).build();
    number.count = 99;
    text.replace(0, text.length(), "changed");
    assertEquals(expected, WireJson.toTree(process.assertions().get(0)).toString());
    assertEquals(expected, WireJson.toTree(explain.assertions().get(0)).toString());
    assertEquals(
        expected, WireJson.toTree(process.context().partOf().assertions().get(0)).toString());
    assertEquals(
        expected, WireJson.toTree(validate.context().partOf().assertions().get(0)).toString());
    var returned = (Map<?, ?>) process.assertions().get(0).metadata().get("nested");
    assertEquals(
        java.util.Set.of(
            "7", "original", "1.2300", "123456789012345678901234567890", "null", "false"),
        returned.keySet());
    assertThrows(UnsupportedOperationException.class, () -> returned.keySet().clear());
    var returnedWhole =
        (Map<?, ?>) validate.context().partOf().assertions().get(0).metadata().get("nested");
    assertThrows(UnsupportedOperationException.class, () -> returnedWhole.clear());
    assertEquals(expected, WireJson.toTree(process.assertions().get(0)).toString());
    assertEquals(
        expected, WireJson.toTree(validate.context().partOf().assertions().get(0)).toString());
  }

  @Test
  void collidingMapKeySpellingsKeepTheExactSerializedWireValue() {
    var key = new MutableNumber(7);
    var nested = new LinkedHashMap<Object, Object>();
    nested.put(key, new java.math.BigDecimal("1.2300"));
    nested.put("7", null);
    var original = fact(Map.of("nested", nested));
    String expected = WireJson.toTree(original).toString();
    var options = ProcessOptions.builder().assertions(List.of(original)).build();
    assertTrue(expected.contains("1.2300"));
    assertEquals(expected, WireJson.toTree(options.assertions().get(0)).toString());
    key.count = 99;
    assertEquals(expected, WireJson.toTree(options.assertions().get(0)).toString());
    var returned =
        (com.google.gson.JsonObject) options.assertions().get(0).metadata().get("nested");
    returned.addProperty("7", 0);
    assertEquals(expected, WireJson.toTree(options.assertions().get(0)).toString());
  }

  @Test
  void exactBigDecimalAndBigIntegerValuesSurviveNestedJsonSnapshots() {
    var decimal = new java.math.BigDecimal("123456789012345678901234567890.123400");
    var integer = new java.math.BigInteger("1234567890123456789012345678901234567890");
    var json = new com.google.gson.JsonObject();
    json.addProperty("decimal", decimal);
    json.addProperty("integer", integer);
    var original = fact(Map.of("decimal", decimal, "integer", integer, "nested", List.of(json)));
    var expected = WireJson.toTree(original).toString();
    var options = ProcessOptions.builder().assertions(List.of(original)).build();
    json.addProperty("decimal", 0);
    assertEquals(decimal, options.assertions().get(0).metadata().get("decimal"));
    assertEquals(integer, options.assertions().get(0).metadata().get("integer"));
    var returned =
        (com.google.gson.JsonObject)
            ((List<?>) options.assertions().get(0).metadata().get("nested")).get(0);
    returned.addProperty("integer", 0);
    assertEquals(expected, WireJson.toTree(options.assertions().get(0)).toString());
    assertTrue(expected.contains("123456789012345678901234567890.123400"));
    assertTrue(expected.contains("1234567890123456789012345678901234567890"));
  }
}
