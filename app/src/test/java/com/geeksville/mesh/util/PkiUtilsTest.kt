/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
