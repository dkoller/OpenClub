package org.openclub.api.notifications

import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Operator command (OpenAPI 1.33.0): prints a new VAPID key pair for push notifications as the two environment
 * lines `OPENCLUB_PUSH_PUBLIC_KEY` and `OPENCLUB_PUSH_PRIVATE_KEY`. Store them in the private env file; replacing
 * them later makes every device subscribe again.
 */
fun main() {
    val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val public = pair.public as ECPublicKey
    val private = pair.private as ECPrivateKey
    fun fixed(value: java.math.BigInteger): ByteArray {
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - bytes.size) + bytes
    }
    val encoder = Base64.getUrlEncoder().withoutPadding()
    val point = byteArrayOf(4) + fixed(public.w.affineX) + fixed(public.w.affineY)
    println("OPENCLUB_PUSH_PUBLIC_KEY=" + encoder.encodeToString(point))
    println("OPENCLUB_PUSH_PRIVATE_KEY=" + encoder.encodeToString(fixed(private.s)))
}
