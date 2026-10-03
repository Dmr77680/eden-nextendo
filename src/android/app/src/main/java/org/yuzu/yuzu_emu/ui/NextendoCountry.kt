// SPDX-FileCopyrightText: 2026 DEN
// SPDX-License-Identifier: GPL-3.0-or-later

// DEN_COUNTRY: lets the player pick the country flag Mario Kart 8 Deluxe shows online.
// The ExeFS patch format and the per-country instruction values come from
// https://github.com/alyeri/nextendo-mk8d-country-flags (MIT License, see docs/THIRD_PARTY_NOTICES.md).
// Only MK8D 4.0.0 (build id below) is supported, which is the version Nextendo requires.

package org.yuzu.yuzu_emu.ui

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.utils.DirectoryInitialization

object NextendoCountry {
    private const val TITLE_ID = "0100152000022000"
    private const val BUILD_ID = "2C336A9BCF79C3040CE506CDD391B578"
    private const val MOD_NAME = "DEN Country Flag"
    private const val MANUAL_MOD_PREFIX = "Nextendo Country "

    private val CODES = listOf(
        "AE", "AL", "AO", "AR", "AT", "AU", "AW", "AZ", "BA", "BB",
        "BE", "BG", "BN", "BO", "BR", "BS", "BW", "BY", "BZ", "CA",
        "CH", "CL", "CO", "CY", "CZ", "DE", "DK", "DO", "EC", "EE",
        "EG", "ES", "FI", "FR", "GB", "GH", "GR", "GT", "HN", "HR",
        "HU", "ID", "IE", "IL", "IN", "IS", "IT", "JM", "JO", "JP",
        "KR", "KW", "KZ", "LB", "LT", "LU", "LV", "MA", "MD", "MG",
        "MK", "ML", "MN", "MR", "MT", "MU", "MX", "MY", "MZ", "NA",
        "NG", "NI", "NL", "NO", "NZ", "OM", "PA", "PE", "PG", "PH",
        "PK", "PL", "PT", "PY", "QA", "RO", "RS", "RU", "RW", "SC",
        "SE", "SG", "SI", "SK", "SR", "SV", "SZ", "TD", "TH", "TN",
        "TR", "TT", "TZ", "UA", "UG", "US", "VE", "VN", "ZM", "ZW"
    )

    private fun loadDir(): File? {
        val base = DirectoryInitialization.userDirectory ?: return null
        return File(base + "/load/" + TITLE_ID)
    }

    // Little-endian hex of one 32-bit ARM64 instruction.
    private fun word(value: Long): String {
        val v = value and 0xFFFFFFFFL
        return String.format(
            "%02X%02X%02X%02X",
            v and 0xFF, (v shr 8) and 0xFF, (v shr 16) and 0xFF, (v shr 24) and 0xFF
        )
    }

    private fun patchText(code: String): String {
        val a = code[0].code.toLong()
        val b = code[1].code.toLong()
        val firstChar = word(0x52800008L or (a shl 5)) // movz w8, #first letter
        val secondChar = word(0x52800008L or (b shl 5)) // movz w8, #second letter
        val both = word(0x52800000L or (((a shl 8) or b) shl 5)) // movz w0, #both letters
        val seq = firstChar + "68020239" + secondChar + "68060239E8030032680E023903000014"
        return "@nsobid-" + BUILD_ID + "\n" +
            "# DEN country flag " + code + "\n" +
            "@enabled\n" +
            "00870F78 " + seq + "\n" +
            "00871008 " + seq + "\n" +
            "004832D0 " + both + "\n" +
            "0084CAD0 " + both + "\n" +
            "00878B84 " + both + "\n" +
            "00878BFC " + both + "\n" +
            "00878E98 " + both + "\n" +
            "@stop\n"
    }

    /** Country currently applied, or null when none. */
    fun current(): String? {
        val dir = loadDir() ?: return null
        val file = File(dir, "$MOD_NAME/exefs/$BUILD_ID.pchtxt")
        if (!file.exists()) return null
        return Regex("country flag ([A-Z]{2})").find(file.readText())?.groupValues?.get(1)
    }

    /** Applies [code] (two-letter country), or removes the flag mod when [code] is null. */
    fun apply(code: String?): Boolean {
        val dir = loadDir() ?: return false
        return try {
            // Two mods patching the same code give an undefined result: drop manually installed ones.
            dir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith(MANUAL_MOD_PREFIX) }
                ?.forEach { it.deleteRecursively() }
            File(dir, MOD_NAME).deleteRecursively()
            if (code != null) {
                val file = File(dir, "$MOD_NAME/exefs/$BUILD_ID.pchtxt")
                file.parentFile?.mkdirs()
                file.writeText(patchText(code))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun show(context: Context) {
        val entries = CODES
            .map { it to Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.getDefault()) }
            .sortedBy { it.second }
        val none = context.getString(R.string.nextendo_country_none)
        val labels = (listOf(none) + entries.map { it.second }).toTypedArray()
        val current = current()
        val checked = if (current == null) 0 else entries.indexOfFirst { it.first == current } + 1

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.nextendo_country)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val code = if (which == 0) null else entries[which - 1].first
                val ok = apply(code)
                val message = when {
                    !ok -> context.getString(R.string.nextendo_country_failed)
                    code == null -> context.getString(R.string.nextendo_country_removed)
                    else -> context.getString(R.string.nextendo_country_set, labels[which])
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
