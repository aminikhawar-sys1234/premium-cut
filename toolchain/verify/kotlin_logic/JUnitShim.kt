@file:Suppress("unused")

package org.junit

import kotlin.reflect.KClass

/**
 * Minimal stand-in for the two JUnit 4 types the project's pure-Kotlin unit tests use.
 *
 * It exists so `toolchain/verify/run_logic_tests.sh` can compile AND EXECUTE the real
 * `app/src/test/...` files on a plain JVM (kotlinc + JDK), without a Gradle/Android build.
 * It is never compiled into the app: it lives outside the `app` source sets.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test

object Assert {

  private fun fail(message: String): Nothing = throw AssertionError(message)

  fun assertTrue(message: String, condition: Boolean) {
    if (!condition) fail(message)
  }

  fun assertTrue(condition: Boolean) = assertTrue("expected true, was false", condition)

  fun assertFalse(message: String, condition: Boolean) {
    if (condition) fail(message)
  }

  fun assertFalse(condition: Boolean) = assertFalse("expected false, was true", condition)

  fun assertNull(value: Any?) = assertNull(null, value)

  fun assertNull(message: String?, value: Any?) {
    if (value != null) fail("${message?.plus(": ") ?: ""}expected null, was <$value>")
  }

  fun assertNotNull(value: Any?) = assertNotNull(null, value)

  fun assertNotNull(message: String?, value: Any?) {
    if (value == null) fail("${message?.plus(": ") ?: ""}expected a value, was null")
  }

  fun assertEquals(expected: Any?, actual: Any?) = assertEquals(null, expected, actual)

  fun assertEquals(message: String?, expected: Any?, actual: Any?) {
    if (expected != actual) {
      fail("${message?.plus(": ") ?: ""}expected <$expected> but was <$actual>")
    }
  }

  fun assertEquals(expected: Int, actual: Int) = assertEquals(null, expected, actual)

  fun assertEquals(message: String?, expected: Int, actual: Int) {
    if (expected != actual) {
      fail("${message?.plus(": ") ?: ""}expected <$expected> but was <$actual>")
    }
  }

  fun assertEquals(expected: Long, actual: Long) = assertEquals(null, expected, actual)

  fun assertEquals(message: String?, expected: Long, actual: Long) {
    if (expected != actual) {
      fail("${message?.plus(": ") ?: ""}expected <$expected> but was <$actual>")
    }
  }

  fun assertEquals(expected: Float, actual: Float, delta: Float) =
    assertEquals(null, expected, actual, delta)

  fun assertEquals(message: String?, expected: Float, actual: Float, delta: Float) {
    if (expected.isNaN() || actual.isNaN() || kotlin.math.abs(expected - actual) > delta) {
      fail("${message?.plus(": ") ?: ""}expected <$expected> but was <$actual> (delta $delta)")
    }
  }
}
