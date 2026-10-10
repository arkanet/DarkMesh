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

/** Pure public-key validation shared by subsequent PKI identity and routing changes. */
object PkiUtils {
    const val PUBLIC_KEY_SIZE_BYTES = 32

    /** A reserved all-zero key represents a detected identity mismatch. */
    val MISMATCH_PUBLIC_KEY: ByteString = ByteString.copyFrom(ByteArray(PUBLIC_KEY_SIZE_BYTES))

    fun isMismatchPublicKey(publicKey: ByteString?): Boolean =
        publicKey != null &&
            publicKey.size() == PUBLIC_KEY_SIZE_BYTES &&
            (0 until publicKey.size()).all { publicKey.byteAt(it).toInt() == 0 }

    fun hasUsablePublicKey(publicKey: ByteString?): Boolean =
        publicKey != null &&
            publicKey.size() == PUBLIC_KEY_SIZE_BYTES &&
            !isMismatchPublicKey(publicKey)

    fun publicKeysEqual(left: ByteString?, right: ByteString?): Boolean =
        left != null && right != null && left == right

    fun publicKeyBytes(publicKey: ByteString?): ByteArray? =
        publicKey?.takeIf(::hasUsablePublicKey)?.toByteArray()
}
