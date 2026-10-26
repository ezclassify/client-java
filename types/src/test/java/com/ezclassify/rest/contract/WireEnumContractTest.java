package com.ezclassify.rest.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Codec contract shared by every contract enum in this package.
 *
 * <p>The API is open-response and closed-request, and the server enforces the closed side. Every
 * contract enum is an {@link ExpandableStringEnum}: {@code fromString} decodes any token and keeps
 * it, {@code toWire} gives it back unchanged, {@code fromWire} stays strict for a token a caller
 * supplies, and {@code value()} maps it onto a nested {@code Value} enum that ends in {@code
 * UNKNOWN}. The types are found from the compiled package, so one the generator adds is held to the
 * contract without a new test.
 */
class WireEnumContractTest {

  private static final String UNSEEN = "value-added-by-a-newer-server";

  private static List<Class<?>> compiledTypes() throws IOException {
    Path pkg = Path.of("target/classes/com/ezclassify/rest/contract");
    List<Class<?>> types = new ArrayList<>();
    try (Stream<Path> files = Files.list(pkg)) {
      for (Path file : files.sorted().toList()) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".class")) continue;
        try {
          types.add(
              Class.forName(Assertion.class.getPackageName() + "." + name.replace(".class", "")));
        } catch (ClassNotFoundException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return types;
  }

  @SuppressWarnings("unchecked")
  private static List<Class<? extends ExpandableStringEnum<?>>> contractEnums() throws IOException {
    List<Class<? extends ExpandableStringEnum<?>>> enums = new ArrayList<>();
    for (Class<?> type : compiledTypes()) {
      if (type != ExpandableStringEnum.class && ExpandableStringEnum.class.isAssignableFrom(type)) {
        enums.add((Class<? extends ExpandableStringEnum<?>>) type);
      }
    }
    return enums;
  }

  private static Object call(Method method, Object target, Object... args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  @Test
  void thePackageHasNoPlainJavaEnumsBesidesTheNestedValueEnums() throws IOException {
    // A plain enum would drop the raw token again, and WireJson refuses to serve one.
    for (Class<?> type : compiledTypes()) {
      if (!type.isEnum()) continue;
      Class<?> owner = type.getDeclaringClass();
      assertTrue(
          owner != null
              && ExpandableStringEnum.class.isAssignableFrom(owner)
              && type.getSimpleName().equals("Value"),
          type.getName() + " is a plain Java enum");
    }
  }

  @Test
  void everyContractEnumKeepsAnUnseenTokenAndRefusesItOnlyOnTheStrictPath() throws Throwable {
    List<Class<? extends ExpandableStringEnum<?>>> enums = contractEnums();
    assertTrue(
        enums.size() >= 37
            && enums.contains(ScanStatus.class)
            && enums.contains(CrosswalkOutcome.Disposition.class)
            && enums.contains(UnrepresentableValue.Reason.class),
        "the scan no longer sees the top-level and nested enums: " + enums);

    for (Class<? extends ExpandableStringEnum<?>> type : enums) {
      String name = type.getName();
      assertTrue(Modifier.isFinal(type.getModifiers()), name + " must be final");
      Method fromString = type.getMethod("fromString", String.class);
      Method fromWire = type.getMethod("fromWire", String.class);
      Class<?> valueEnum = Class.forName(name + "$Value");
      Object[] valueMembers = valueEnum.getEnumConstants();
      Object unknownMember = valueMembers[valueMembers.length - 1];
      assertEquals("UNKNOWN", ((Enum<?>) unknownMember).name(), name);

      ExpandableStringEnum<?> unseen = (ExpandableStringEnum<?>) call(fromString, null, UNSEEN);
      assertEquals(UNSEEN, unseen.toWire(), name);
      assertEquals(UNSEEN, unseen.toString(), name);
      assertFalse(unseen.isKnown(), name);
      assertSame(unknownMember, unseen.value(), name);
      Object again = call(fromString, null, UNSEEN);
      assertEquals(unseen, again, name);
      assertEquals(unseen.hashCode(), again.hashCode(), name);
      assertThrows(IllegalArgumentException.class, () -> call(fromWire, null, UNSEEN), name);
      assertNull(call(fromString, null, (Object) null), name);
      assertNull(call(fromWire, null, (Object) null), name);
    }
  }

  @Test
  void everyDeclaredConstantRoundTripsAndMatchesItsValueMember() throws Throwable {
    for (Class<? extends ExpandableStringEnum<?>> type : contractEnums()) {
      String name = type.getName();
      Method fromString = type.getMethod("fromString", String.class);
      Method fromWire = type.getMethod("fromWire", String.class);
      @SuppressWarnings("unchecked")
      List<ExpandableStringEnum<?>> declared =
          (List<ExpandableStringEnum<?>>) call(type.getMethod("values"), null);
      Object[] valueMembers = Class.forName(name + "$Value").getEnumConstants();

      List<String> constantNames = new ArrayList<>();
      for (Field field : type.getFields()) {
        if (field.getType() == type && Modifier.isStatic(field.getModifiers())) {
          ExpandableStringEnum<?> constant = (ExpandableStringEnum<?>) field.get(null);
          constantNames.add(field.getName());
          assertTrue(declared.contains(constant), name + "." + field.getName());
          assertEquals(field.getName(), constant.value().name(), name);
        }
      }
      assertEquals(declared.size(), constantNames.size(), name);
      assertEquals(declared.size() + 1, valueMembers.length, name);

      for (int i = 0; i < declared.size(); i++) {
        ExpandableStringEnum<?> constant = declared.get(i);
        String token = constant.toWire();
        assertTrue(constant.isKnown(), name + " " + token);
        assertSame(valueMembers[i], constant.value(), name + " " + token);
        assertSame(constant, call(fromString, null, token), name + " " + token);
        assertSame(constant, call(fromWire, null, token), name + " " + token);
        assertEquals(token, constant.toString(), name + " " + token);
      }
      assertThrows(UnsupportedOperationException.class, () -> declared.add(declared.get(0)), name);
    }
  }

  @Test
  void equalityIsByTypeAndToken() {
    assertEquals(
        AssertionConfidence.fromString("probable"), AssertionConfidence.fromString("probable"));
    assertNotEquals(
        AssertionConfidence.fromString("probable"), RepairConfidence.fromString("probable"));
    assertNotEquals(AssertionConfidence.fromString("probable"), AssertionConfidence.INFERRED);
    // A caller may type the declared token in any way; it still resolves to the canonical constant.
    assertSame(
        AssertionConfidence.INFERRED, AssertionConfidence.fromString(new String("inferred")));
  }

  @Test
  void aSwitchOverValueCoversAnUnseenToken() {
    AssertionConfidence confidence = AssertionConfidence.fromString("probable");
    String handled =
        switch (confidence.value()) {
          case CERTAIN, INFERRED, REPAIRED, UNCERTAIN -> "declared";
          case UNKNOWN -> "unknown " + confidence.toWire();
        };
    assertEquals("unknown probable", handled);
  }
}
