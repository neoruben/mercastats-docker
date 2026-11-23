package io.github.neoruben.mercadonastats.server.auth

import java.net.InetAddress

/**
 * Comprueba si una dirección IP (IPv4 o IPv6) coincide con una lista de IPs exactas
 * o rangos CIDR (ej. `127.0.0.1`, `::1`, `172.16.0.0/12`, `10.0.0.0/8`).
 */
class IpCidrMatcher(entries: List<String>) {

    private data class CidrRule(val networkBytes: ByteArray, val prefixLength: Int)

    private val rules: List<CidrRule> = entries.mapNotNull { parseRule(it) }

    fun matches(rawIp: String?): Boolean {
        if (rawIp.isNullOrBlank() || rules.isEmpty()) return false
        val cleaned = normalizeIpString(rawIp)
        val addrBytes = runCatching { InetAddress.getByName(cleaned).address }.getOrNull() ?: return false
        val candidateVariants = buildCandidateByteVariants(addrBytes)
        return rules.any { rule ->
            candidateVariants.any { candidate -> matchesRule(candidate, rule) }
        }
    }

    private fun parseRule(raw: String): CidrRule? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split("/", limit = 2)
        val ipPart = normalizeIpString(parts[0])
        // Una entrada inválida aborta el arranque: nunca se "corrige" a un rango más amplio.
        require(isIpLiteral(ipPart)) { "TRUSTED_PROXIES: '$raw' no es una IP o CIDR válido." }
        val bytes = runCatching { InetAddress.getByName(ipPart).address }.getOrNull()
            ?: throw IllegalArgumentException("TRUSTED_PROXIES: '$raw' no es una IP o CIDR válido.")
        val maxBits = bytes.size * 8
        val prefix = if (parts.size == 2) {
            val p = parts[1].trim().toIntOrNull()
            require(p != null && p in 1..maxBits) {
                "TRUSTED_PROXIES: prefijo inválido en '$raw' (debe estar entre 1 y $maxBits)."
            }
            p
        } else {
            maxBits
        }
        return CidrRule(bytes, prefix)
    }

    /** Solo IPs literales: evita que InetAddress.getByName resuelva nombres por DNS. */
    private fun isIpLiteral(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.contains(':')) {
            return s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        }
        val octets = s.split('.')
        return octets.size == 4 &&
            octets.all { o -> o.isNotEmpty() && o.length <= 3 && o.all(Char::isDigit) && o.toInt() <= 255 }
    }

    private fun normalizeIpString(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("[") && s.contains("]")) {
            s = s.substringAfter("[").substringBefore("]")
        }
        val zoneIdx = s.indexOf('%')
        if (zoneIdx != -1) {
            s = s.substring(0, zoneIdx)
        }
        return s
    }

    /**
     * Si llega una IP IPv4-mapped IPv6 (`::ffff:127.0.0.1`), permite hacer match
     * tanto contra reglas IPv6 como contra la IPv4 equivalente (`127.0.0.1`).
     */
    private fun buildCandidateByteVariants(bytes: ByteArray): List<ByteArray> {
        if (bytes.size == 16 && isIpv4MappedIpv6(bytes)) {
            return listOf(bytes, bytes.copyOfRange(12, 16))
        }
        return listOf(bytes)
    }

    private fun isIpv4MappedIpv6(bytes: ByteArray): Boolean {
        for (i in 0 until 10) {
            if (bytes[i] != 0.toByte()) return false
        }
        return bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    }

    private fun matchesRule(candidate: ByteArray, rule: CidrRule): Boolean {
        if (candidate.size != rule.networkBytes.size) return false
        val fullBytes = rule.prefixLength / 8
        val remainingBits = rule.prefixLength % 8

        for (i in 0 until fullBytes) {
            if (candidate[i] != rule.networkBytes[i]) return false
        }
        if (remainingBits > 0) {
            val mask = (0xFF shl (8 - remainingBits)) and 0xFF
            val cByte = candidate[fullBytes].toInt() and mask
            val nByte = rule.networkBytes[fullBytes].toInt() and mask
            if (cByte != nByte) return false
        }
        return true
    }
}
