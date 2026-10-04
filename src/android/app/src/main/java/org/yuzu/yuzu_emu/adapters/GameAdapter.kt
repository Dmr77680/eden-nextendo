// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.adapters

import android.content.DialogInterface
import android.text.Html
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.preference.PreferenceManager
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.HomeNavigationDirections
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.databinding.CardGameListBinding
import org.yuzu.yuzu_emu.databinding.CardGameGridBinding
import org.yuzu.yuzu_emu.databinding.CardGameCarouselBinding
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GamesViewModel
import org.yuzu.yuzu_emu.utils.GameIconUtils
import org.yuzu.yuzu_emu.utils.ViewUtils.marquee
import org.yuzu.yuzu_emu.viewholder.AbstractViewHolder
import androidx.core.net.toUri
import androidx.core.content.edit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.databinding.CardGameGridCompactBinding
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.Settings

class GameAdapter(private val activity: AppCompatActivity) :
    AbstractDiffAdapter<Game, GameAdapter.GameViewHolder>(exact = false) {

    companion object {
        const val VIEW_TYPE_GRID = 0
        const val VIEW_TYPE_GRID_COMPACT = 1
        const val VIEW_TYPE_LIST = 2
        const val VIEW_TYPE_CAROUSEL = 3
    }

    private var viewType = 0

    // [Nextendo] lowercase 16-digit hex title id -> players currently online
    private var onlineCounts: Map<String, Int> = emptyMap()

    fun setOnlineCounts(counts: Map<String, Int>) {
        if (counts != onlineCounts) {
            onlineCounts = counts
            notifyDataSetChanged()
        }
    }

    // DEN_VERSIONS_REMOTE : versions exigees, telechargees depuis docs/nextendo_versions.json
    @Volatile private var remoteVersions: Map<String, String> = emptyMap()

    fun setNextendoVersions(versions: Map<String, String>) {
        if (versions != remoteVersions) {
            remoteVersions = versions
            notifyDataSetChanged()
        }
    }

    fun setViewType(type: Int) {
        viewType = type
        notifyDataSetChanged()
    }

    public var cardSize: Int = 0
        private set

    fun setCardSize(size: Int) {
        if (cardSize != size && size > 0) {
            cardSize = size
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int = viewType

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        when (getItemViewType(position)) {
            VIEW_TYPE_LIST -> {
                val listBinding = holder.binding as CardGameListBinding
                listBinding.cardGameList.scaleX = 1f
                listBinding.cardGameList.scaleY = 1f
                listBinding.cardGameList.alpha = 1f
                // Reset layout params to XML defaults
                listBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                listBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_GRID -> {
                val gridBinding = holder.binding as CardGameGridBinding
                gridBinding.cardGameGrid.scaleX = 1f
                gridBinding.cardGameGrid.scaleY = 1f
                gridBinding.cardGameGrid.alpha = 1f
                // Reset layout params to XML defaults
                gridBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                gridBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_GRID_COMPACT -> {
                val gridCompactBinding = holder.binding as CardGameGridCompactBinding
                gridCompactBinding.cardGameGridCompact.scaleX = 1f
                gridCompactBinding.cardGameGridCompact.scaleY = 1f
                gridCompactBinding.cardGameGridCompact.alpha = 1f
                // Reset layout params to XML defaults (same as normal grid)
                gridCompactBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
                gridCompactBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }

            VIEW_TYPE_CAROUSEL -> {
                val carouselBinding = holder.binding as CardGameCarouselBinding
                // soothens transient flickering
                carouselBinding.cardGameCarousel.scaleY = 0f
                carouselBinding.cardGameCarousel.alpha = 0f
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val binding = when (viewType) {
            VIEW_TYPE_LIST -> CardGameListBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_GRID -> CardGameGridBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_GRID_COMPACT -> CardGameGridCompactBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            VIEW_TYPE_CAROUSEL -> CardGameCarouselBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            else -> throw IllegalArgumentException("Invalid view type")
        }
        return GameViewHolder(binding, viewType)
    }

    inner class GameViewHolder(
        internal val binding: ViewBinding,
        private val viewType: Int
    ) : AbstractViewHolder<Game>(binding) {

        override fun bind(model: Game) {
            when (viewType) {
                VIEW_TYPE_LIST -> bindListView(model)
                VIEW_TYPE_GRID -> bindGridView(model)
                VIEW_TYPE_CAROUSEL -> bindCarouselView(model)
                VIEW_TYPE_GRID_COMPACT -> bindGridCompactView(model)
            }
        }

        // [Nextendo] only this game version can reach the Nextendo servers (same table as Citron)
        private val nextendoVersions = mapOf(
            "0100152000022000" to "4.0.0",  // Mario Kart 8 Deluxe
            "01006a800016e000" to "13.0.5", // Super Smash Bros. Ultimate
            "0100f8f0000a2000" to "5.5.2",  // Splatoon 2 (EU)
            "01003bc0000a0000" to "5.5.2",  // Splatoon 2 (US)
            "01003c700009c800" to "5.5.2",  // Splatoon 2 (JP)
            "01006f8002326000" to "3.0.3",  // Animal Crossing: New Horizons
            "0100dca0064a6000" to "1.4.0",  // Luigi's Mansion 3
            "01009b500007c000" to "5.5.1",  // ARMS
            "0100bde00862a000" to "3.1.1",  // Mario Tennis Aces
            "0100c2500fc20000" to "11.3.0", // Splatoon 3
            "01009b90006dc000" to "3.0.3",  // Super Mario Maker 2
            "010015100b514000" to "1.2.1",  // Super Mario Bros. Wonder
            "0100277011f1a000" to "1.0.2",  // Super Mario Bros. 35
            "0100770008dd8000" to "1.4.0",  // Monster Hunter Generations Ultimate
            "010047700d540000" to "2.0.1",  // Clubhouse Games: 51 Worldwide Classics
            "0100c6f01c4f8000" to "1.3.0",  // METAL GEAR SOLID: Peace Walker
            "01006fe013472000" to "1.1.1",  // Mario Party Superstars
            "01006bd001e06000" to "1.0.17", // Minecraft: Nintendo Switch Edition
            "0100ad9012510000" to "1.1.0",  // PAC-MAN 99
            "0100f9f00c696000" to "1.0.15", // Crash Team Racing Nitro-Fueled
            "01001b300b9be000" to "2.7.7.92380", // Diablo III: Eternal Collection
            "01006fd0080b2000" to "1.0.19", // Overcooked! 2
            "0100c9a00ece6000" to "4.2.0",  // Nintendo 64 - Nintendo Classics
            "0100000000010000" to "1.4.1",  // Super Mario Odyssey (Balloon World)
            "0100a3d008c5c000" to "4.0.0",  // Pokemon Scarlet
            "01008f6008c5e000" to "4.0.0",  // Pokemon Violet
            "0100f43008c44000" to "2.0.2",  // Pokemon Legends: Z-A
            "0100c9c00e25c000" to "4.0.0"   // Mario Golf: Super Rush
        )

        private fun displayTitle(model: Game): CharSequence {
            val base = model.title.replace("[\\t\\n\\r]+".toRegex(), " ")
            val id = (model.programId.toLongOrNull() ?: 0L).toString(16).padStart(16, '0')
            val count = onlineCounts[id] ?: 0
            val wanted = remoteVersions[id] ?: nextendoVersions[id]
            val needsUpdate = wanted != null && model.version.isNotEmpty() && model.version != wanted

            if (count <= 0 && !needsUpdate) {
                return base
            }
            val text = android.text.SpannableStringBuilder(base)
            if (needsUpdate) {
                val badge = "\u26A0 " + binding.root.context.getString(R.string.nextendo_badge_update, wanted)
                val start = text.length + 2
                text.append("  ").append(badge)
                text.setSpan(
                    android.text.style.ForegroundColorSpan(0xFFFF9800.toInt()),
                    start,
                    start + badge.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            if (count > 0) {
                val badge = "\u2022 " + binding.root.context.getString(R.string.nextendo_badge_online, count)
                val start = text.length + 2
                text.append("  ").append(badge)
                text.setSpan(
                    android.text.style.ForegroundColorSpan(0xFF4CAF50.toInt()),
                    start,
                    start + badge.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return text
        }

        private fun bindListView(model: Game) {
            val listBinding = binding as CardGameListBinding

            listBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, listBinding.imageGameScreen)

            listBinding.textGameTitle.text = displayTitle(model)
            listBinding.textGameDeveloper.text = model.developer

            listBinding.textGameTitle.marquee()
            listBinding.cardGameList.setOnClickListener { onClick(model) }
            listBinding.cardGameList.setOnLongClickListener { onLongClick(model) }

            // Reset layout params to XML defaults
            listBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            listBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindGridView(model: Game) {
            val gridBinding = binding as CardGameGridBinding

            gridBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, gridBinding.imageGameScreen)

            gridBinding.textGameTitle.text = displayTitle(model)

            gridBinding.textGameTitle.marquee()
            gridBinding.cardGameGrid.setOnClickListener { onClick(model) }
            gridBinding.cardGameGrid.setOnLongClickListener { onLongClick(model) }

            // Reset layout params to XML defaults
            gridBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            gridBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindGridCompactView(model: Game) {
            val gridCompactBinding = binding as CardGameGridCompactBinding

            gridCompactBinding.imageGameScreenCompact.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, gridCompactBinding.imageGameScreenCompact)

            gridCompactBinding.textGameTitleCompact.text = displayTitle(model)

            gridCompactBinding.textGameTitleCompact.marquee()
            gridCompactBinding.cardGameGridCompact.setOnClickListener { onClick(model) }
            gridCompactBinding.cardGameGridCompact.setOnLongClickListener { onLongClick(model) }

            // Reset layout params to XML defaults (same as normal grid)
            gridCompactBinding.root.layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            gridCompactBinding.root.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        private fun bindCarouselView(model: Game) {
            val carouselBinding = binding as CardGameCarouselBinding

            carouselBinding.imageGameScreen.scaleType = ImageView.ScaleType.CENTER_CROP
            GameIconUtils.loadGameIcon(model, carouselBinding.imageGameScreen)

            carouselBinding.textGameTitle.text = displayTitle(model)
            carouselBinding.textGameTitle.marquee()
            carouselBinding.cardGameCarousel.setOnClickListener { onClick(model) }
            carouselBinding.cardGameCarousel.setOnLongClickListener { onLongClick(model) }

            carouselBinding.imageGameScreen.contentDescription =
                binding.root.context.getString(R.string.game_image_desc, model.title)

            // Ensure zero-heighted-full-width cards for carousel
            carouselBinding.root.layoutParams.width = cardSize
        }

        fun onClick(game: Game) {
            val gameExists = DocumentFile.fromSingleUri(
                YuzuApplication.appContext,
                game.path.toUri()
            )?.exists() == true

            if (!gameExists) {
                Toast.makeText(
                    YuzuApplication.appContext,
                    R.string.loader_error_file_not_found,
                    Toast.LENGTH_LONG
                ).show()

                ViewModelProvider(activity)[GamesViewModel::class.java].reloadGames(true)
                return
            }

            val launchNow: () -> Unit = {
                val preferences =
                    PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
                preferences.edit {
                    putLong(
                        game.keyLastPlayedTime,
                        System.currentTimeMillis()
                    )
                }

                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        val shortcut =
                            ShortcutInfoCompat.Builder(YuzuApplication.appContext, game.path)
                                .setShortLabel(game.title)
                                .setIcon(GameIconUtils.getShortcutIcon(activity, game))
                                .setIntent(game.launchIntent)
                                .build()
                        ShortcutManagerCompat.pushDynamicShortcut(
                            YuzuApplication.appContext,
                            shortcut
                        )
                    }
                }

                val action = HomeNavigationDirections.actionGlobalEmulationActivity(game, true)
                binding.root.findNavController().navigate(action)
            }

            // DEN_SIGNIN_WARN : jeu Nextendo lance sans compte connecte -> erreur 2306-0802 assuree
            val launch: () -> Unit = {
                val needsSignIn = try {
                    NativeLibrary.nextendoAccountName().isEmpty() &&
                        org.yuzu.yuzu_emu.ui.NextendoFriendsDialog.isNextendoTitle(game.programIdHex)
                } catch (_: Throwable) {
                    false
                }
                if (needsSignIn) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.nextendo_signin_required_title)
                        .setMessage(R.string.nextendo_signin_required_message)
                        .setPositiveButton(R.string.nextendo_sign_in) { _: DialogInterface?, _: Int ->
                            org.yuzu.yuzu_emu.utils.NextendoSignInService.start(activity)
                        }
                        .setNeutralButton(R.string.nextendo_launch_anyway) { _: DialogInterface?, _: Int ->
                            launchNow()
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> }
                        .show()
                } else {
                    launchNow()
                }
            }

            if (NativeLibrary.gameRequiresFirmware(game.programId) && !NativeLibrary.isFirmwareAvailable()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.loader_requires_firmware)
                    .setMessage(
                        Html.fromHtml(
                            activity.getString(R.string.loader_requires_firmware_description),
                            Html.FROM_HTML_MODE_LEGACY
                        )
                    )
                    .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                        launch()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> }
                    .show()
            } else {
                launch()
            }
        }

        fun onLongClick(game: Game): Boolean {
            val action = HomeNavigationDirections.actionGlobalPerGamePropertiesFragment(game)
            binding.root.findNavController().navigate(action)
            return true
        }
    }
}
