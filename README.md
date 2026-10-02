# Eden Nextendo

Un fork de l'émulateur Switch **[Eden](https://git.eden-emu.dev/eden-emu/eden)** pour **Android**, adapté pour se connecter à **Nextendo**, un réseau de jeu en ligne alternatif pour la Switch.

> Projet personnel et non officiel. Il n'est affilié ni à l'équipe d'Eden, ni à Citron, ni à Nintendo.

## Ce que ce fork ajoute

- **Connexion à Nextendo** : résolution DNS, détection du NAT et échange des adresses des joueurs adaptés au réseau Nextendo.
- **Jeu en ligne** : Mario Kart 8 Deluxe (salons, courses) et Super Mario Maker 2 fonctionnent en ligne. Mario Party Superstars a été testé brièvement.
- **Liste d'amis et présence** : la liste d'amis Nextendo est visible dans les jeux et ta présence est publiée aux autres joueurs.
- **Compteur de joueurs en ligne** : affiché en vert sur chaque jeu dans la liste de jeux.

Testé sur Retroid Pocket 5. Le jeu en ligne n'est pas stable à 100 % : des déconnexions peuvent arriver.

## Utilisation

Ce dépôt ne contient et ne distribue aucun jeu ni firmware. Tu dois fournir toi-même tes jeux, tes clés et ton firmware, obtenus légalement depuis ta propre console.

Le build Android se fait avec le workflow GitHub Actions **Build Android** (onglet *Actions*).

## Crédits

- **[Eden](https://git.eden-emu.dev/eden-emu/eden)** : l'émulateur sur lequel ce projet est basé. Le README d'origine est conservé dans [README.eden.md](./README.eden.md).
- **Citron** : certaines parties de la prise en charge de Nextendo (service d'amis, API) s'en inspirent.
- **yuzu** : le projet dont Eden est issu.

## Licence

Comme Eden, ce projet est sous licence **GPL-3.0 ou ultérieure**. Voir [LICENSE.txt](./LICENSE.txt). Les mentions de copyright des fichiers d'origine sont conservées.
