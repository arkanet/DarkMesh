/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.geeksville.mesh.util

import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PkiUtilsTest {
    private fun key(seed: Int) = ByteString.copyFrom(ByteArray(PkiUtils.PUBLIC_KEY_SIZE_BYTES) { (seed + it).toByte() })

    @Test fun validKeyIsUsable() {
        assertTrue(PkiUtils.hasUsablePublicKey(key(1)))
        assertArrayEquals(key(1).toByteArray(), PkiUtils.publicKeyBytes(key(1)))
    }

    @Test fun missingAndInvalidLengthsAreRejected() {
        assertFalse(PkiUtils.hasUsablePublicKey(null))
        assertFalse(PkiUtils.hasUsablePublicKey(ByteString.EMPTY))
        assertFalse(PkiUtils.hasUsablePublicKey(ByteString.copyFrom(ByteArray(31) { 1 })))
        assertFalse(PkiUtils.hasUsablePublicKey(ByteString.copyFrom(ByteArray(33) { 1 })))
        assertNull(PkiUtils.publicKeyBytes(ByteString.EMPTY))
    }

    @Test fun mismatchSentinelIsNotUsable() {
        assertTrue(PkiUtils.isMismatchPublicKey(PkiUtils.MISMATCH_PUBLIC_KEY))
        assertFalse(PkiUtils.hasUsablePublicKey(PkiUtils.MISMATCH_PUBLIC_KEY))
        assertNull(PkiUtils.publicKeyBytes(PkiUtils.MISMATCH_PUBLIC_KEY))
    }

    @Test fun keyEqualityUsesBytesNotObjectIdentity() {
        assertTrue(PkiUtils.publicKeysEqual(key(2), ByteString.copyFrom(key(2).toByteArray())))
        assertFalse(PkiUtils.publicKeysEqual(key(2), key(3)))
        assertFalse(PkiUtils.publicKeysEqual(null, key(2)))
    }
}
