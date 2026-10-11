package com.geeksville.mesh.util

import com.google.protobuf.ByteString
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

object Crypto {

    data class PkiKeyPair(
        val publicKey: ByteString,
        val privateKey: ByteString,
    )

    fun sha256Digest(text: String): ByteArray {
        val bytes = text.toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes)
    }

    fun generatePkiKeyPair(): PkiKeyPair {
        val privateKey = X25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey()

        val publicKeyBytes = ByteArray(32)
        val privateKeyBytes = ByteArray(32)
        publicKey.encode(publicKeyBytes, 0)
        privateKey.encode(privateKeyBytes, 0)

        return PkiKeyPair(
            publicKey = ByteString.copyFrom(publicKeyBytes),
            privateKey = ByteString.copyFrom(privateKeyBytes),
        )
    }
}