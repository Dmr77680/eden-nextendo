// SPDX-FileCopyrightText: 2026 DEN
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import org.yuzu.yuzu_emu.NativeLibrary

object NextendoFriendsDialog {
    private const val GREEN = 0xFF4CAF50.toInt()
    private const val GRAY = 0xFF9E9E9E.toInt()

    // DEN_TITLES : jeux de la liste Nextendo (identifiant de titre en 16 chiffres hexa minuscules)
    private val KNOWN_TITLES = mapOf(
        "0100152000022000" to "Mario Kart 8 Deluxe",
        "01006a800016e000" to "Super Smash Bros. Ultimate",
        "0100f8f0000a2000" to "Splatoon 2",
        "01003bc0000a0000" to "Splatoon 2",
        "01003c700009c800" to "Splatoon 2",
        "01006f8002326000" to "Animal Crossing: New Horizons",
        "0100dca0064a6000" to "Luigi's Mansion 3",
        "01009b500007c000" to "ARMS",
        "0100bde00862a000" to "Mario Tennis Aces",
        "0100c2500fc20000" to "Splatoon 3",
        "01009b90006dc000" to "Super Mario Maker 2",
        "010015100b514000" to "Super Mario Bros. Wonder",
        "0100277011f1a000" to "Super Mario Bros. 35",
        "0100770008dd8000" to "Monster Hunter Generations Ultimate",
        "010047700d540000" to "Clubhouse Games: 51 Worldwide Classics",
        "0100c6f01c4f8000" to "METAL GEAR SOLID: Peace Walker",
        "01006fe013472000" to "Mario Party Superstars",
        "010019401051c000" to "Mario Strikers: Battle League",
        "01006bd001e06000" to "Minecraft: Nintendo Switch Edition",
        "0100ad9012510000" to "PAC-MAN 99",
        "0100f9f00c696000" to "Crash Team Racing Nitro-Fueled",
        "01001b300b9be000" to "Diablo III: Eternal Collection",
        "01006fd0080b2000" to "Overcooked! 2",
        "0100c9a00ece6000" to "Nintendo 64 - Nintendo Classics",
        "0100000000010000" to "Super Mario Odyssey",
        "0100a3d008c5c000" to "Pokemon Scarlet",
        "01008f6008c5e000" to "Pokemon Violet",
        "0100f43008c44000" to "Pokemon Legends: Z-A",
        "0100c9c00e25c000" to "Mario Golf: Super Rush"
    )

    // DEN_TITLES_ONLINE : le serveur Nextendo envoie lui-meme les noms de tous ses jeux
    // (champ "noms" de /api/online-counts). La liste ci-dessus ne sert que de secours.
    @Volatile private var remoteNames: Map<String, String> = emptyMap()

    private fun fetchTitleNames() {
        try {
            val conn = java.net.URL("https://nextendo.network/api/online-counts")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val noms = JSONObject(body).optJSONObject("noms")
                    if (noms != null) {
                        val map = HashMap<String, String>()
                        val keys = noms.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            map[k.lowercase()] = noms.getString(k)
                        }
                        if (map.isNotEmpty()) remoteNames = map
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
    }

    private fun gameName(appId: String, appName: String): String {
        if (appName.isNotEmpty()) return appName
        val id = appId.trim().lowercase().removePrefix("0x")
        return remoteNames[id] ?: KNOWN_TITLES[id] ?: appId
    }

    private class Friend(
        val name: String,
        val status: Int,
        val appId: String,
        val appName: String,
        val image: String
    )

    fun show(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(activity).apply { addView(list) }

        fun message(text: String) {
            list.removeAllViews()
            list.addView(TextView(activity).apply {
                this.text = text
                setPadding(0, dp(16), 0, dp(16))
            })
        }
        message(activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_loading))

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_friends))
            .setView(scroll)
            .setPositiveButton(activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_refresh), null)
            .setNegativeButton(activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_close), null)
            .create()

        fun row(f: Friend): View {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val avatar = try {
                if (f.image.isEmpty()) null else {
                    val bytes = Base64.decode(f.image, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (_: Exception) {
                null
            }
            val img = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x33808080)
                }
                if (avatar != null) setImageBitmap(avatar)
            }
            row.addView(img, LinearLayout.LayoutParams(dp(44), dp(44)))

            val dot = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (f.status > 0) GREEN else GRAY)
                }
            }
            val dotLp = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginStart = dp(12)
                marginEnd = dp(10)
            }
            row.addView(dot, dotLp)

            val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(activity).apply {
                text = f.name
                textSize = 16f
            })
            val playing = f.status > 0 && (f.appName.isNotEmpty() || f.appId.isNotEmpty())
            col.addView(TextView(activity).apply {
                text = when {
                    f.status <= 0 -> activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_offline)
                    playing -> activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_playing, gameName(f.appId, f.appName))
                    else -> activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_online)
                }
                textSize = 13f
                setTextColor(if (f.status > 0) GREEN else GRAY)
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        fun refresh() {
            message(activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_loading))
            Thread {
                fetchTitleNames()
                var ok = false
                var error = ""
                val friends = mutableListOf<Friend>()
                try {
                    val root = JSONObject(NativeLibrary.nextendoFriendsListJson())
                    ok = root.optBoolean("ok")
                    error = root.optString("error")
                    val arr = root.getJSONArray("friends")
                    for (i in 0 until arr.length()) {
                        val e = arr.getJSONObject(i)
                        friends.add(
                            Friend(
                                e.optString("name"), e.optInt("status"),
                                e.optString("app_id"), e.optString("app_name"),
                                e.optString("image")
                            )
                        )
                    }
                } catch (_: Exception) {
                }
                friends.sortWith(compareByDescending<Friend> { it.status }.thenBy { it.name.lowercase() })
                activity.runOnUiThread {
                    if (!dialog.isShowing) return@runOnUiThread
                    when {
                        !ok -> message(
                            activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_friends_load_failed) +
                                (if (error.isNotEmpty()) " ($error)" else "") +
                                ". " + activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_check_linked)
                        )
                        friends.isEmpty() -> message(activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_no_friends))
                        else -> {
                            list.removeAllViews()
                            val online = friends.count { it.status > 0 }
                            list.addView(TextView(activity).apply {
                                text = activity.getString(org.yuzu.yuzu_emu.R.string.nextendo_friends_online_count, online, friends.size)
                                textSize = 13f
                                setTextColor(GREEN)
                                setPadding(0, 0, 0, dp(4))
                            })
                            friends.forEach { list.addView(row(it)) }
                        }
                    }
                }
            }.start()
        }

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { refresh() }
            refresh()
        }
        dialog.show()
    }
}
