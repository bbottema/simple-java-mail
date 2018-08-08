package org.simplejavamail.internal.util;

import org.junit.Test;
import org.simplejavamail.internal.util.ReflectiveValueConverter.IncompatibleTypeException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Calendar;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Junit test for {@link ReflectiveValueConverter}.
 */
@SuppressWarnings({"WrapperTypeMayBePrimitive", "ConstantConditions", "RedundantCast"})
public class ReflectiveValueConverterTest {

	@SuppressWarnings("unused")
	enum TestEnum {
		ONE, TWO, THREE
	}

	/**
	 * Test for {@link ReflectiveValueConverter#isCommonType(Class)}.
	 */
	@Test
	public void testIsCommonType() {
		// basic commons
		assertTrue(ReflectiveValueConverter.isCommonType(String.class));
		assertTrue(ReflectiveValueConverter.isCommonType(Integer.class));
		assertTrue(ReflectiveValueConverter.isCommonType(int.class));
		assertTrue(ReflectiveValueConverter.isCommonType(Float.class));
		assertTrue(ReflectiveValueConverter.isCommonType(float.class));
		assertTrue(ReflectiveValueConverter.isCommonType(Double.class));
		assertTrue(ReflectiveValueConverter.isCommonType(double.class));
		assertTrue(ReflectiveValueConverter.isCommonType(Long.class));
		assertTrue(ReflectiveValueConverter.isCommonType(long.class));
		assertTrue(ReflectiveValueConverter.isCommonType(Byte.class));
		assertTrue(ReflectiveValueConverter.isCommonType(byte.class));
		assertTrue(ReflectiveValueConverter.isCommonType(Short.class));
		assertTrue(ReflectiveValueConverter.isCommonType(short.class));
		// limited commons
		assertTrue(ReflectiveValueConverter.isCommonType(Boolean.class));
		assertTrue(ReflectiveValueConverter.isCommonType(boolean.class));
		assertTrue(ReflectiveValueConverter.isCommonType(Character.class));
		assertTrue(ReflectiveValueConverter.isCommonType(char.class));
		// no commons
		assertFalse(ReflectiveValueConverter.isCommonType(Math.class));
		assertFalse(ReflectiveValueConverter.isCommonType(ReflectiveValueConverter.class));
		assertFalse(ReflectiveValueConverter.isCommonType(ReflectiveValueConverter.class));
		assertFalse(ReflectiveValueConverter.isCommonType(Calendar.class));
	}

	/**
	 * Test for {@link ReflectiveValueConverter#collectCompatibleTypes(Class)}.
	 */
	@Test
	public void testCollectCompatibleTypes() {
		// test that all commons types are convertible to all common types
		for (Class<?> basicCommonType : ReflectiveValueConverter.COMMON_TYPES) {
			assertContainsAll(ReflectiveValueConverter.collectCompatibleTypes(basicCommonType), ReflectiveValueConverter.COMMON_TYPES);
		}

		List<Class<?>> types = ReflectiveValueConverter.collectCompatibleTypes(String.class);
		assertContainsAll(types, ReflectiveValueConverter.COMMON_TYPES);

		types = ReflectiveValueConverter.collectCompatibleTypes(boolean.class);
		assertContainsAll(types, ReflectiveValueConverter.COMMON_TYPES);

		types = ReflectiveValueConverter.collectCompatibleTypes(Character.class);
		assertContainsAll(types, ReflectiveValueConverter.COMMON_TYPES);

		types = ReflectiveValueConverter.collectCompatibleTypes(Calendar.class);
		assertEquals(1, types.size());
		assertTrue(types.contains(String.class));
	}

	@SuppressWarnings("SameParameterValue")
	private void assertContainsAll(List<Class<?>> types, List<Class<?>> basiccommontypes) {
		for (Class<?> basicCommonType : basiccommontypes) {
			assertTrue(types.contains(basicCommonType));
		}
	}

	/**
	 * Test for {@link ReflectiveValueConverter#convert(Object[], Class[], boolean)}.
	 */
	@Test
	public void testConvertObjectArrayClassOfQArray() {
		// empty list
		Object[] emptyArray = ReflectiveValueConverter.convert(new Object[] {}, new Class[] {}, true);
		assertNotNull(emptyArray);
		assertEquals(0, emptyArray.length);
		// asymmetric list
		try {
			ReflectiveValueConverter.convert(new Object[] { 1, 2, 3 }, new Class[] { String.class }, true);
			fail("should not accept array arguments of different lengths!");
		} catch (IllegalStateException e) {
			// OK
		}
		// list with inconvertible items, throwing exception for inconvertible values
		try {
			Calendar calendar = Calendar.getInstance();
			ReflectiveValueConverter.convert(new Object[] { 1, "blah", calendar }, new Class[] { String.class, Integer.class, Float.class }, false);
			fail("should not accept inconvertible values!");
		} catch (IncompatibleTypeException e) {
			// OK
		}
		// list with inconvertible items, keeping original for inconvertible values
		Calendar calendar = Calendar.getInstance();
		Object[] result = ReflectiveValueConverter.convert(new Object[] { 1, "blah", calendar, 0 }, new Class[] { String.class, Integer.class,
				Float.class, Boolean.class }, true);
		assertNotNull(result);
		assertEquals(4, result.length);
		assertEquals("1", result[0]);
		assertEquals("blah", result[1]);
		assertSame(calendar, result[2]);
		assertTrue(result[3] instanceof Boolean);
		assertFalse((Boolean) result[3]);
	}

	/**
	 * Test for {@link ReflectiveValueConverter#convert(Object, Class)}.
	 */
	@Test
	public void testConvertObjectClassOfQ() {
		// test null value
		assertNull(ReflectiveValueConverter.convert((Object) null, Number.class));
		// test integer -> number (allowed)
		Integer integer = 50;
		assertSame(integer, ReflectiveValueConverter.convert((Object) integer, Number.class));
		// test with exact same type (allowed, should return the original value)
		Calendar calendar = Calendar.getInstance();
		assertSame(calendar, ReflectiveValueConverter.convert((Object) calendar, Calendar.class));
		// test number -> integer (not allowed)
		Number number = 100.5f;
		Object o = ReflectiveValueConverter.convert((Object) number, Integer.class);
		assertNotSame(number, o);
		assertEquals(100, o);
		// test to string conversion
		assertEquals("a value", ReflectiveValueConverter.convert((Object) "a value", String.class));
		assertEquals("100.5", ReflectiveValueConverter.convert((Object) number, String.class));
		// test from string to anything else conversion
		assertEquals("a value", ReflectiveValueConverter.convert((Object) "a value", String.class));
		assertFalse((Boolean) ReflectiveValueConverter.convert((Object) "false", boolean.class));
		assertEquals(33f, ReflectiveValueConverter.convert((Object) "33", float.class));
		// test from character
		Character chara = '5';
		char charb = '8';
		assertEquals(5, ReflectiveValueConverter.convert((Object) chara, Number.class));
		assertEquals(8f, ReflectiveValueConverter.convert((Object) charb, float.class));
		// test from boolean
		Boolean boola = false;
		boolean boolb = true;
		assertEquals(0, ReflectiveValueConverter.convert((Object) boola, Number.class));
		assertEquals(1f, ReflectiveValueConverter.convert((Object) boolb, float.class));
		assertEquals("false", ReflectiveValueConverter.convert((Object) boola, String.class));
		assertEquals("true", ReflectiveValueConverter.convert((Object) boolb, String.class));
		// test for incompatibility error
		try {
			ReflectiveValueConverter.convert((Object) false, Calendar.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
		try {
			ReflectiveValueConverter.convert((Object) Calendar.getInstance(), Number.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
	}

	/**
	 * Test for {@link ReflectiveValueConverter#convert(Number, Class)}.
	 */
	@Test
	public void testConvertNumberClassOfQ() {
		assertNull(ReflectiveValueConverter.convert((Number) null, boolean.class));
		assertFalse((Boolean) ReflectiveValueConverter.convert(0, boolean.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert(1, boolean.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert(50, boolean.class));
		assertEquals(50f, ReflectiveValueConverter.convert(50, float.class));
		assertEquals(50d, ReflectiveValueConverter.convert(50, double.class));
		assertEquals(50L, ReflectiveValueConverter.convert(50, long.class));
		assertEquals(50, ReflectiveValueConverter.convert(50, Integer.class));
		assertEquals((byte) 50, ReflectiveValueConverter.convert(50, byte.class));
		assertEquals((short) 50, ReflectiveValueConverter.convert(50, short.class));
		assertEquals('5', ReflectiveValueConverter.convert(5, char.class));
		assertEquals("50", ReflectiveValueConverter.convert(50, String.class));

		try {
			ReflectiveValueConverter.convert(50, Calendar.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
	}

	/**
	 * Test for {@link ReflectiveValueConverter#convert(Boolean, Class)}.
	 */
	@Test
	public void testConvertBooleanClassOfQ() {
		assertNull(ReflectiveValueConverter.convert((Boolean) null, Calendar.class));
		assertFalse((Boolean) ReflectiveValueConverter.convert(false, boolean.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert(true, boolean.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert(true, boolean.class));
		assertEquals("true", ReflectiveValueConverter.convert(true, String.class));
		assertEquals("false", ReflectiveValueConverter.convert(false, String.class));
		assertEquals(1, ReflectiveValueConverter.convert(true, Integer.class));
		assertEquals(1f, ReflectiveValueConverter.convert(true, Float.class));
		assertEquals(1, ReflectiveValueConverter.convert(true, Number.class));
		assertEquals(0d, ReflectiveValueConverter.convert(false, double.class));
		assertEquals('0', ReflectiveValueConverter.convert(false, Character.class));
		assertEquals('1', ReflectiveValueConverter.convert(true, Character.class));

		try {
			ReflectiveValueConverter.convert(false, Calendar.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
	}

	/**
	 * Test for {@link ReflectiveValueConverter#convert(Character, Class)}.
	 */
	@Test
	public void testConvertCharacterClassOfQ() {
		assertNull(ReflectiveValueConverter.convert((Character) null, Object.class));
		assertEquals('5', ReflectiveValueConverter.convert('5', char.class));
		assertEquals("h", ReflectiveValueConverter.convert('h', String.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert('1', Boolean.class));
		assertFalse((Boolean) ReflectiveValueConverter.convert('0', Boolean.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert('h', Boolean.class));
		assertEquals(9, ReflectiveValueConverter.convert('9', Integer.class));
		assertEquals(9, ReflectiveValueConverter.convert('9', Number.class));
		assertEquals(9d, ReflectiveValueConverter.convert('9', Double.class));

		try {
			ReflectiveValueConverter.convert('5', Calendar.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
	}

	/**
	 * Test for {@link ReflectiveValueConverter#convert(String, Class)}.
	 */
	@Test
	public void testConvertStringClassOfQ() {
		assertEquals(0, ReflectiveValueConverter.convert("0", Integer.class));
		assertNull(ReflectiveValueConverter.convert((String) null, Integer.class));
		assertEquals(10, ReflectiveValueConverter.convert("10", Integer.class));
		assertEquals(0f, ReflectiveValueConverter.convert("0", Float.class));
		assertEquals(10f, ReflectiveValueConverter.convert("10", Float.class));
		assertEquals(0d, ReflectiveValueConverter.convert("0", double.class));
		assertEquals(10d, ReflectiveValueConverter.convert("10", double.class));
		assertEquals(0, ReflectiveValueConverter.convert("0", Number.class));
		assertEquals(10, ReflectiveValueConverter.convert("10", Number.class));
		assertFalse((Boolean) ReflectiveValueConverter.convert("0", Boolean.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert("1", Boolean.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert("true", Boolean.class));
		assertFalse((Boolean) ReflectiveValueConverter.convert("false", Boolean.class));
		assertEquals('h', ReflectiveValueConverter.convert("h", char.class));
		assertEquals("h", ReflectiveValueConverter.convert("h", String.class));
		assertSame(TestEnum.ONE, ReflectiveValueConverter.convert("ONE", TestEnum.class));
		assertTrue((Boolean) ReflectiveValueConverter.convert("h", Boolean.class));
		try {
			ReflectiveValueConverter.convert("falsef", Boolean.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
		try {
			ReflectiveValueConverter.convert("h", Calendar.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
		try {
			ReflectiveValueConverter.convert("hello", Calendar.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
		try {
			ReflectiveValueConverter.convert("", int.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
	}

	/**
	 * Test for {@link ReflectiveValueConverter#convertEnum(String, Class)}.
	 */
	@Test
	public void testConvertEnum() {
		assertNull(ReflectiveValueConverter.convertEnum(null, TestEnum.class));
		assertSame(TestEnum.ONE, ReflectiveValueConverter.convertEnum("ONE", TestEnum.class));
		try {
			ReflectiveValueConverter.convertEnum("5", TestEnum.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
	}

	/**
	 * Test for {@link ReflectiveValueConverter#convertNumber(String, Class)}.
	 */
	@SuppressWarnings("unchecked")
	@Test
	public void testConvertNumber() {
		assertNull(ReflectiveValueConverter.convertNumber(null, Integer.class));
		assertEquals(1, ReflectiveValueConverter.convertNumber("1", Integer.class));
		assertEquals(1f, ReflectiveValueConverter.convertNumber("1", Float.class));
		assertEquals(1d, ReflectiveValueConverter.convertNumber("1", Double.class));
		assertEquals((byte) 1, ReflectiveValueConverter.convertNumber("1", Byte.class));
		assertEquals(1, ReflectiveValueConverter.convertNumber("1", Number.class));
		assertEquals((short) 1, ReflectiveValueConverter.convertNumber("1", short.class));
		assertEquals(1L, ReflectiveValueConverter.convertNumber("1", long.class));
		assertEquals(BigDecimal.valueOf(1), ReflectiveValueConverter.convertNumber("1", BigDecimal.class));
		assertEquals(BigInteger.valueOf(1), ReflectiveValueConverter.convertNumber("1", BigInteger.class));
		try {
			ReflectiveValueConverter.convertNumber("", Integer.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
		try {
			ReflectiveValueConverter.convertNumber("d", Integer.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
		try {
			ReflectiveValueConverter.convertNumber("1", (Class<Integer>) (Object) Calendar.class);
			fail("should not be able to convert value");
		} catch (IllegalArgumentException e) {
			// OK
		}
		try {
			ReflectiveValueConverter.convertNumber("1", CustomNumber.class);
			fail("should not be able to convert value");
		} catch (IncompatibleTypeException e) {
			// OK
		}
	}

	@SuppressWarnings("serial")
	private static class CustomNumber extends Number {

		@Override
		public int intValue() {
			throw new AssertionError("not implemented");
		}

		@Override
		public long longValue() {
			throw new AssertionError("not implemented");
		}

		@Override
		public float floatValue() {
			throw new AssertionError("not implemented");
		}

		@Override
		public double doubleValue() {
			throw new AssertionError("not implemented");
		}
	}

	/**
	 * Test for {@link ReflectiveValueConverter#isPrimitiveNumber(Class)}.
	 */
	@Test
	public void testIsPrimitiveNumber() {
		assertFalse(ReflectiveValueConverter.isPrimitiveNumber(char.class));
		assertTrue(ReflectiveValueConverter.isPrimitiveNumber(int.class));
		assertTrue(ReflectiveValueConverter.isPrimitiveNumber(float.class));
		assertTrue(ReflectiveValueConverter.isPrimitiveNumber(double.class));
		assertTrue(ReflectiveValueConverter.isPrimitiveNumber(long.class));
		assertTrue(ReflectiveValueConverter.isPrimitiveNumber(byte.class));
		assertTrue(ReflectiveValueConverter.isPrimitiveNumber(short.class));
		assertFalse(ReflectiveValueConverter.isPrimitiveNumber(boolean.class));
		assertFalse(ReflectiveValueConverter.isPrimitiveNumber(Calendar.class));
		assertFalse(ReflectiveValueConverter.isPrimitiveNumber(Boolean.class));
		assertFalse(ReflectiveValueConverter.isPrimitiveNumber(Character.class));
		assertFalse(ReflectiveValueConverter.isPrimitiveNumber(Integer.class));
		assertFalse(ReflectiveValueConverter.isPrimitiveNumber(Number.class));
	}
}