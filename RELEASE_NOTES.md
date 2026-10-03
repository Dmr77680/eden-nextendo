## 🇫🇷 DEN v0.1.0 (pré-version)

Première version publique de DEN : Eden pour Android + support de Nextendo. **Pré-version : testée uniquement sur Retroid Pocket 5.**

### ✅ Ce qui marche
- Jeu en ligne Nextendo : **Mario Kart 8 Deluxe** (salons, courses), **Super Mario Maker 2**
- Liste d'amis Nextendo + écran « Amis Nextendo » (en ligne / hors ligne)
- Compteur de joueurs en ligne sur chaque jeu
- Badge orange « MAJ x.y.z » si la version du jeu n'est pas la bonne
- Import de Mii (`.charinfo`, `.mii`, `MiiDatabase.dat` de Ryujinx)
- Connexion à ton compte via le navigateur (identité, amis, présence)

### ⚠️ Ce qui ne marche pas encore
- **Jouer en ligne après « Se connecter à Nextendo »** : erreur 2306-0802 (le serveur de jeu refuse ce nouveau jeton, demande faite à Nextendo). Solution actuelle : placer ton `nextendo_account.txt` dans `Android/data/dev.eden.eden_emulator.relWithDebInfo/files/config/`.
- **Splatoon 3** : se lance mais problèmes graphiques (en cours de correction)
- Appareils autres que Retroid Pocket 5 non testés ; surcadençage = risque de déconnexions.

### Installation
**Configuration requise : Android 13 minimum, appareil 64 bits (arm64).**

1. Télécharge `DEN.apk` ci-dessous et installe-le.
2. Ajoute tes propres jeux, clés et firmware (non fournis).
3. Place ton `nextendo_account.txt` dans `files/config/`.

🔒 Ne partage jamais ton `nextendo_account.txt` (équivaut à un mot de passe).

---

## 🇬🇧 DEN v0.1.0 (pre-release)

First public release of DEN: Eden for Android + Nextendo support. **Pre-release: tested only on a Retroid Pocket 5.**

### ✅ What works
- Nextendo online play: **Mario Kart 8 Deluxe** (lobbies, races), **Super Mario Maker 2**
- Nextendo friend list + "Nextendo Friends" screen (online / offline)
- Online player counter on each game
- Orange "UPDATE x.y.z" badge when the game version is wrong
- Mii import (`.charinfo`, `.mii`, Ryujinx `MiiDatabase.dat`)
- Browser sign-in to your account (identity, friends, presence)

### ⚠️ What doesn't work yet
- **Playing online after "Sign in to Nextendo"**: error 2306-0802 (the game server rejects this new token; request sent to Nextendo). Current workaround: put your `nextendo_account.txt` in `Android/data/dev.eden.eden_emulator.relWithDebInfo/files/config/`.
- **Splatoon 3**: launches but has graphical issues (being worked on)
- Devices other than Retroid Pocket 5 untested; overclocking may cause disconnections.

### Install
**Requirements: Android 13 or newer, 64-bit (arm64) device.**

1. Download `DEN.apk` below and install it.
2. Add your own games, keys and firmware (not provided).
3. Put your `nextendo_account.txt` in `files/config/`.

🔒 Never share your `nextendo_account.txt` (it's equivalent to a password).
