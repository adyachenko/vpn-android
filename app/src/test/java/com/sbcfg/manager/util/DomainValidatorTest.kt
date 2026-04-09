package com.sbcfg.manager.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainValidatorTest {

    @Test
    fun testValidateDomain_ValidDomain_True() {
        assertTrue(DomainValidator.isValid("example.com"))
    }

    @Test
    fun testValidateDomain_WithSpaces_False() {
        assertFalse(DomainValidator.isValid("example .com"))
    }

    @Test
    fun testValidateDomain_WithScheme_False() {
        assertFalse(DomainValidator.isValid("https://example.com"))
    }

    @Test
    fun testValidateDomain_Empty_False() {
        assertFalse(DomainValidator.isValid(""))
    }

    @Test
    fun testValidateDomain_IpAddress_True() {
        assertTrue(DomainValidator.isValid("192.168.1.1"))
    }

    @Test
    fun testValidateDomain_Wildcard_True() {
        assertTrue(DomainValidator.isValid("*.example.com"))
    }
}
