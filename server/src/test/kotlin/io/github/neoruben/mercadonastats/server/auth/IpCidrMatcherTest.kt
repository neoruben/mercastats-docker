package io.github.neoruben.mercadonastats.server.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IpCidrMatcherTest {

    @Test
    fun matchesIpv4ExactAndCidr() {
        val matcher = IpCidrMatcher(listOf("127.0.0.1", "172.16.0.0/12", "10.0.0.0/8"))

        assertTrue(matcher.matches("127.0.0.1"))
        assertTrue(matcher.matches("172.18.0.5"))
        assertTrue(matcher.matches("10.244.1.20"))
        assertTrue(matcher.matches("::ffff:172.18.0.5"))

        assertFalse(matcher.matches("203.0.113.10"))
        assertFalse(matcher.matches("8.8.8.8"))
        assertFalse(matcher.matches(null))
        assertFalse(matcher.matches(""))
    }

    @Test
    fun matchesIpv6ExactAndCidr() {
        val matcher = IpCidrMatcher(listOf("::1", "fd00::/8"))

        assertTrue(matcher.matches("::1"))
        assertTrue(matcher.matches("0:0:0:0:0:0:0:1"))
        assertTrue(matcher.matches("fd12:3456:789a::1"))
        assertFalse(matcher.matches("2001:db8::1"))
    }

    @Test
    fun rejectsInvalidEntriesInsteadOfWidening() {
        listOf("10.0.0.0/-1", "10.0.0.0/0", "10.0.0.0/33", "10.0.0.0/x", "not-an-ip", "localhost", "999.1.1.1", "::1/129")
            .forEach { entry ->
                assertThrows(entry, IllegalArgumentException::class.java) { IpCidrMatcher(listOf(entry)) }
            }
    }
}
