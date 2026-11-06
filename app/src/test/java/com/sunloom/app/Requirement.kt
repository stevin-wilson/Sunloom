package com.sunloom.app

/**
 * Links a test class or method to the Constitution section it verifies, e.g. `@Requirement("XII.4")`
 * for Article XII section 4. [ConstitutionTraceabilityTest] fails when a traced section loses all its
 * tests or when an id no longer exists in CONSTITUTION.md.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Requirement(vararg val ids: String)
