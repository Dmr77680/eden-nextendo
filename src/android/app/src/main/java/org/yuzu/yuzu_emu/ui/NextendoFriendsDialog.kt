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
        message("Chargement…")

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Amis Nextendo")
            .setView(scroll)
            .setPositiveButton("Actualiser", null)
            .setNegativeButton("Fermer", null)
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
                    f.status <= 0 -> "Hors ligne"
                    playing -> "En jeu : " + f.appName.ifEmpty { f.appId }
                    else -> "En ligne"
                }
                textSize = 13f
                setTextColor(if (f.status > 0) GREEN else GRAY)
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        fun refresh() {
            message("Chargement…")
            Thread {
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
                            "Impossible de charger les amis" +
                                (if (error.isNotEmpty()) " ($error)" else "") +
                                ". Vérifie que ton compte Nextendo est lié."
                        )
                        friends.isEmpty() -> message("Aucun ami pour le moment.")
                        else -> {
                            list.removeAllViews()
                            val online = friends.count { it.status > 0 }
                            list.addView(TextView(activity).apply {
                                text = "$online en ligne sur ${friends.size}"
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
