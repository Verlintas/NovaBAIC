/*
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of BetterAIChat2.
 *
 * BetterAIChat2 is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * BetterAIChat2 is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * BetterAIChat2. If not, see <https://www.gnu.org/licenses/>.
 */

package com.verlintas.baic2.device.impl.wireless

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * ADB client key and self-signed certificate used for Wireless debugging.
 * Both live in app-private storage; the key never leaves the app.
 */
internal class AdbKeys private constructor(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    companion object {
        fun loadOrCreate(context: Context): AdbKeys {
            val dir = File(context.filesDir, "wireless-adb").apply { mkdirs() }
            val keyFile = File(dir, "adb.key")
            val certFile = File(dir, "adb.crt")
            if (keyFile.isFile && certFile.isFile) {
                runCatching { return load(keyFile, certFile) }
            }
            val created = generate()
            keyFile.writeBytes(created.privateKey.encoded)
            certFile.writeBytes(created.certificate.encoded)
            runCatching {
                keyFile.setReadable(true, true)
                keyFile.setWritable(true, true)
                certFile.setReadable(true, true)
                certFile.setWritable(true, true)
            }
            return created
        }

        private fun load(keyFile: File, certFile: File): AdbKeys {
            val key = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            val cert = certFile.inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
            }
            return AdbKeys(key, cert)
        }

        private fun generate(): AdbKeys {
            val generator = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }
            val pair = generator.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=BAIC2 Wireless ADB")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now),
                Date(now - DAY_MS),
                Date(now + 10L * 365L * DAY_MS),
                subject,
                pair.public,
            ).apply {
                addExtension(Extension.basicConstraints, true, BasicConstraints(true))
                addExtension(
                    Extension.keyUsage,
                    true,
                    KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
                )
            }
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            cert.checkValidity()
            return AdbKeys(pair.private, cert)
        }

        private const val DAY_MS = 86_400_000L
    }
}
