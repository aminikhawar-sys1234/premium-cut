package com.example.toolchain

import java.lang.reflect.Method

/**
 * Runs every `@org.junit.Test` method of the classes named on the command line and prints a per-test
 * verdict. Exits with status 1 as soon as one test fails, so a script can gate on it.
 *
 * Part of `toolchain/verify/run_logic_tests.sh`: it lets the real JUnit test files be executed here
 * (kotlinc + plain JVM), which a sandbox without the Android SDK or a Maven mirror cannot do through Gradle.
 */
object TestRunner {

  @JvmStatic
  fun main(args: Array<String>) {
    if (args.isEmpty()) {
      println("usage: TestRunner <fully.qualified.TestClass> [more...]")
      kotlin.system.exitProcess(2)
    }
    var passed = 0
    val failures = mutableListOf<String>()
    for (className in args) {
      val clazz = try {
        Class.forName(className)
      } catch (t: Throwable) {
        failures.add("$className: could not be loaded (${t.message})")
        println("SUITE FAIL $className: ${t.message}")
        continue
      }
      val methods = clazz.declaredMethods
        .filter { m -> m.annotations.any { it.annotationClass.qualifiedName == "org.junit.Test" } }
        .sortedBy { it.name }
      println("== $className (${methods.size} tests)")
      for (m in methods) {
        val verdict = runOne(clazz, m)
        if (verdict == null) {
          passed++
          println("  PASS ${m.name}")
        } else {
          failures.add("${clazz.simpleName}.${m.name}: $verdict")
          println("  FAIL ${m.name}: $verdict")
        }
      }
    }
    println()
    println("tests passed: $passed, failed: ${failures.size}")
    if (failures.isNotEmpty()) {
      println("failed tests:")
      failures.forEach { println("  - $it") }
      kotlin.system.exitProcess(1)
    }
  }

  private fun runOne(clazz: Class<*>, method: Method): String? {
    val instance = try {
      clazz.getDeclaredConstructor().newInstance()
    } catch (t: Throwable) {
      return "could not be instantiated (${t.message})"
    }
    method.isAccessible = true
    return try {
      method.invoke(instance)
      null
    } catch (e: java.lang.reflect.InvocationTargetException) {
      val cause = e.cause ?: e
      "${cause.javaClass.simpleName}: ${cause.message}"
    } catch (t: Throwable) {
      "${t.javaClass.simpleName}: ${t.message}"
    }
  }
}
