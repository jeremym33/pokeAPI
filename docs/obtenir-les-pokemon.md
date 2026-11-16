# Obtenir les Pokémon — règles du jeu d'aventure

Résumé des façons d'obtenir (« rejoindre ») un Pokémon dans l'aventure, tirées du code du jeu (`game/GameRules.kt`,
`game/GameService.kt`) et vérifiées sur les données PokéAPI (Vert Feuille et Cristal). Un Pokémon **rejoint** compte
dans le Pokédex ; un Pokémon seulement **vu** (rencontré) non.

**Bilan : les 251 Pokémon sont obtenables** (vérifié sur les données : Kanto par les rencontres, les évolutions et
les Pokémon rares ; Johto de même, plus le bonheur, la Pierre Soleil et les œufs).

## Vue d'ensemble

| Moyen | Quand | Détails |
|---|---|---|
| Starter | Début de partie | Bulbizarre, Salamèche ou Carapuce, niveau 5 |
| Pokémon sauvage | Après chaque victoire contre lui | Il peut proposer de vous rejoindre (voir ci-dessous) |
| Évolution | Fin de combat, objet, entraînement | L'espèce obtenue compte comme rejointe |
| Œuf | Pension | Forme de base de l'un des parents |
| Starter de Johto | Première arrivée à Johto | Germignon, Héricendre ou Kaiminus au choix, niveau 5 |
| Pokémon rare | Après la Ligue de la région | Apparaît de temps en temps, par le coup de pouce |

## 1. Pokémon sauvages

- **Où** : sur les lieux du parcours qui ont des Pokémon sauvages (routes, grottes, forêts…), avec « Chercher un
  Pokémon sauvage ». Les rencontres viennent de **Vert Feuille** pour Kanto (n° 1 à 151 seulement) et de **Cristal**
  pour Johto (n° 1 à 251). **Chaque région repart des niveaux les plus bas** (niveau 2 sur la Route 29), avec ses
  propres badges : à Johto, le niveau maximum et les cannes ou le surf dépendent des badges de Johto.
- **Rencontres débloquées par les badges de la région** : herbes et Canne au début ; Super Canne à 3 badges ; Surf et
  Méga Canne à 5 badges.
- **Liste des espèces d'un lieu** : elle montre les espèces des herbes et de la Canne (celles qu'il faut voir pour
  franchir le lieu). Après la victoire contre le Maître de la Ligue de la région, elle montre aussi celles de la Super
  Canne, de la Méga Canne et du Surf (« 🎣 Après la Ligue : à la canne et en surf »), chacune comme rejointe, vue ou
  pas encore vue ; le coup de pouce les vise aussi.
- **Rejoindre** : après une victoire, le Pokémon peut proposer de vous rejoindre. La chance dépend de son taux de
  capture : `15 % + 55 % × taux / 255`.

  | Exemple | Taux de capture | Chance |
  |---|---|---|
  | Rattata, Roucool | 255 | 70 % |
  | Starters, Évoli | 45 | ≈ 25 % |
  | Légendaires | 3 | ≈ 16 % |

  Vous acceptez ou refusez ; il rejoint l'équipe, ou la boîte si l'équipe a déjà 6 Pokémon.
- **Réglage « Proposer seulement les nouveaux Pokémon »** : une espèce déjà rejointe ne propose plus de vous
  rejoindre (« s'éloigne »), **sauf un chromatique** que vous n'avez pas encore, et **sauf le premier de son espèce né
  dans la région où vous êtes** (à Johto, un Rattata alors que vous n'avez que des Rattata de Kanto) : seuls les
  Pokémon nés dans la région y combattent.
- **Région** : le Pokémon est « né » dans la région du lieu (il ne combat que dans sa région).

## 2. Le coup de pouce (invisible)

Sur chaque lieu, le coup de pouce arrive **au hasard entre le 5e et le 15e combat** depuis le précédent : jamais
avant le 5e, de plus en plus probable ensuite, garanti au 15e (environ tous les 10 combats en moyenne). Rien ne
l'annonce. La rencontre se fait alors avec :

1. une espèce du lieu **pas encore vue**, s'il en reste ;
2. sinon une espèce du lieu **vue mais pas encore rejointe** ;
3. après la victoire contre le Maître de la Ligue de la région, parfois (une fois sur deux s'il reste des espèces à
   voir ou à rejoindre, toujours sinon) un **Pokémon rare** du lieu (voir 6).

Après la Ligue de la région, les espèces de la canne et du surf comptent parmi les espèces du lieu. Chaque espèce
visée a la même chance d'être choisie, même celles qu'on croise rarement sur le lieu. Le Pokémon du
coup de pouce a **10 %** de chances d'être chromatique (contre 1 sur 4 096 normalement).

## 3. Évolutions

- **Par niveau** : à la fin d'un combat (gagné ou perdu), les Pokémon de l'équipe qui ont le niveau requis évoluent.
  Un Pokémon à l'entraînement sur un lieu évolue aussi en gagnant des niveaux.
- **Par le bonheur** : chaque Pokémon gagne **1 point de bonheur par adversaire vaincu** en ayant combattu (20 au
  maximum, visible dans la fiche du Pokémon, page Équipe ; un bébé éclos part de 10). Au maximum, à la fin d'un
  combat, il évolue s'il évolue par le bonheur : Nosferalto → Nostenfer, Togepi → Togetic, Leveinard → Leuphorie,
  Pichu → Pikachu, Mélo → Mélofée, Toudoudou → Rondoudou ; Évoli → **Mentali de jour** (6 h à 18 h, heure du
  serveur) ou **Noctali de nuit**. Attention : un Évoli au bonheur maximum évolue ainsi, utilisez avant une pierre si
  vous voulez Aquali, Voltali ou Pyroli.
- **Selon les statistiques** : Debugant au niveau 20 devient Kicklee (Attaque > Défense), Tygnon (Attaque < Défense)
  ou **Kapoera** (Attaque = Défense, le cas de Debugant dans ce jeu, où les statistiques ne dépendent que de
  l'espèce et du niveau).
- **Par objet** (page Sac) : Pierre Feu, Eau, Foudre, Plante, Lune et **Soleil** (Joliflor, Héliatronc) ; le **Câble
  Link** remplace les évolutions par échange, y compris avec un objet tenu (Tarpaud, Roigada, Steelix, Cizayox,
  Hyporoi, Porygon2…). Les objets s'obtiennent **uniquement en réussissant des succès** ; la Pierre Soleil, avec les
  succès de Johto (10 puis 40 espèces de Johto rejointes).
- **Région** : un Pokémon n'évolue jamais vers une espèce d'une région pas encore ouverte (avant Johto, pas de
  Nostenfer, Joliflor, Tarpaud…).
- Un chromatique reste chromatique en évoluant.

## 4. Œufs (pension)

- Une pension par région, accessible de partout (onglet **Pension**), sauf pendant un défi de la Ligue.
- Deux Pokémon **compatibles** : un groupe d'œufs en commun, ou un Métamorph avec un autre Pokémon (jamais deux
  Métamorph, ni un Pokémon du groupe « Inconnu », comme les légendaires et les bébés).
- Un œuf apparaît **toutes les 5 minutes** (en temps réel, même partie fermée). Il faut le prendre ; 6 œufs
  transportés au plus.
- **Espèce** : la forme de base, dans les régions ouvertes, de l'un des parents (hors Métamorph), fixée quand l'œuf
  apparaît. Une fois Johto ouvert, l'œuf d'un Pikachu donne un Pichu, celui d'un Élektek un Élekid, etc. : c'est le
  moyen d'obtenir les **bébés** avant la Ligue de Johto.
- **Éclosion** : après un nombre de combats tiré du cycle d'éclosion de l'espèce (5 à 40 combats). Le bébé est
  niveau 1, né dans la région de la pension, avec 10 points de bonheur et 1 chance sur 4 096 d'être chromatique.

## 5. Ouverture de Johto

- Johto s'ouvre quand les **151 Pokémon de Kanto** ont rejoint votre Pokédex.
- À votre première arrivée à Johto, vous **choisissez votre starter de Johto** : Germignon, Héricendre ou Kaiminus,
  niveau 5. Les Pokémon de Kanto ne combattent pas à Johto : il est le premier de votre nouvelle équipe, et Johto se
  joue comme un nouveau départ (Pokémon sauvages de niveau 2, niveau maximum 15 jusqu'au premier badge de Johto).
- Les deux autres starters de Johto s'obtiennent comme Pokémon rares (voir 6), ou par œuf.

## 6. Pokémon rares (après la Ligue)

Après la **première victoire contre le Maître de la Ligue d'une région**, les espèces de cette région introuvables à
l'état sauvage (et qui ne s'obtiennent pas en faisant évoluer un Pokémon trouvable) apparaissent sur ses lieux, **par
le coup de pouce** seulement. Le parcours affiche « ★ N rares » sur les lieux concernés.

- **Niveau** : celui des Pokémon du lieu + 5 (au moins 50) ; Mewtwo niveau 70 ; Lugia et Ho-Oh niveau 90.
- **Kanto** (31 espèces, après la Ligue de Kanto, qui ouvre aussi la Grotte Azurée) : starters, fossiles (Amonita,
  Kabuto, Ptéra), Évoli, Lokhlass, Porygon, Ronflex, Kicklee, Tygnon, M. Mime, Insécateur, Mew… Légendaires à lieu
  fixe :

  | Pokémon | Lieu |
  |---|---|
  | Artikodin | Îles Écume |
  | Électhor | Centrale |
  | Sulfura | Route Victoire |
  | Mewtwo | Grotte Azurée (seul Pokémon rare de ce lieu) |

- **Johto** (33 espèces, après la Ligue de Johto) : starters de Johto, bébés (Pichu, Mélo, Toudoudou, Togepi,
  Debugant, Lippouti, Élekid, Magby), Embrylex, Teddiursa, Cornèbre, Qulbutoké… Légendaires à lieu fixe :

  | Pokémon | Lieu |
  |---|---|
  | Raikou, Entei, Suicune | Tour Cendrée |
  | Lugia | Chenal 41 |
  | Ho-Oh | Route 37 |
  | Celebi | Bois aux Chênes |

## 7. Chromatiques

- 1 chance sur 4 096 par rencontre, **10 %** pour le Pokémon du coup de pouce, 1 sur 4 096 à l'éclosion.
- Un chromatique rejoint compte dans le Motisdex (chromatiques vus / obtenus) ; il reste chromatique en évoluant.

## Limites

Aucune espèce n'est impossible à obtenir. Les conditions d'évolution des jeux qui n'ont pas d'équivalent ici sont
simplifiées : le bonheur se gagne en combattant (pas de soins, de promenades ni de baies), « de jour / de nuit »
suit l'heure du serveur, et les statistiques ne dépendent que de l'espèce et du niveau (pas de variations
individuelles), d'où Kapoera pour tout Debugant.
