# Obtenir les Pokémon — règles du jeu d'aventure

Résumé des façons d'obtenir (« rejoindre ») un Pokémon dans l'aventure, tirées du code du jeu (`game/GameRules.kt`,
`game/GameService.kt`) et vérifiées sur les données PokéAPI (Vert Feuille et Cristal). Un Pokémon **rejoint** compte
dans le Pokédex ; un Pokémon seulement **vu** (rencontré) non.

**Bilan : 243 des 251 Pokémon sont obtenables** (tout Kanto ; 8 espèces de Johto manquent, voir « Limites
actuelles »).

## Vue d'ensemble

| Moyen | Quand | Détails |
|---|---|---|
| Starter | Début de partie | Bulbizarre, Salamèche ou Carapuce, niveau 5 |
| Pokémon sauvage | Après chaque victoire contre lui | Il peut proposer de vous rejoindre (voir ci-dessous) |
| Évolution | Fin de combat, objet, entraînement | L'espèce obtenue compte comme rejointe |
| Œuf | Pension | Forme de base de l'un des parents |
| Cadeau | Ouverture de Johto | Un œuf d'un starter de Johto, au hasard |
| Pokémon rare | Après la Ligue de la région | Apparaît de temps en temps, par le coup de pouce |

## 1. Pokémon sauvages

- **Où** : sur les lieux du parcours qui ont des Pokémon sauvages (routes, grottes, forêts…), avec « Chercher un
  Pokémon sauvage ». Les rencontres viennent de **Vert Feuille** pour Kanto (n° 1 à 151 seulement) et de **Cristal**
  pour Johto (n° 1 à 251, niveaux relevés de 45).
- **Rencontres débloquées par les badges** : herbes et Canne au début ; Super Canne à 3 badges ; Surf et Méga Canne
  à 5 badges.
- **Rejoindre** : après une victoire, le Pokémon peut proposer de vous rejoindre. La chance dépend de son taux de
  capture : `15 % + 55 % × taux / 255`.

  | Exemple | Taux de capture | Chance |
  |---|---|---|
  | Rattata, Roucool | 255 | 70 % |
  | Starters, Évoli | 45 | ≈ 25 % |
  | Légendaires | 3 | ≈ 16 % |

  Vous acceptez ou refusez ; il rejoint l'équipe, ou la boîte si l'équipe a déjà 6 Pokémon.
- **Réglage « Proposer seulement les nouveaux Pokémon »** : une espèce déjà rejointe ne propose plus de vous
  rejoindre (« s'éloigne »), **sauf un chromatique** que vous n'avez pas encore.
- **Région** : le Pokémon est « né » dans la région du lieu (il ne combat que dans sa région, ou comme compagnon).

## 2. Le coup de pouce (invisible)

Tous les **10 combats sur un même lieu**, la rencontre suivante est :

- une espèce du lieu **pas encore vue**, s'il en reste ;
- après la victoire contre le Maître de la Ligue de la région, parfois un **Pokémon rare** du lieu (voir 6).

Le Pokémon du coup de pouce a **10 %** de chances d'être chromatique (contre 1 sur 4 096 normalement).

## 3. Évolutions

- **Par niveau** : à la fin d'un combat (gagné ou perdu), les Pokémon de l'équipe qui ont le niveau requis évoluent.
  Un Pokémon à l'entraînement sur un lieu évolue aussi en gagnant des niveaux.
- **Par objet** (page Sac) : Pierre Feu, Eau, Foudre, Plante et Lune ; le **Câble Link** remplace les évolutions par
  échange, y compris avec un objet tenu (Tarpaud, Roigada, Steelix, Cizayox, Hyporoi, Porygon2…). Les objets
  s'obtiennent **uniquement en réussissant des succès**.
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
  niveau 1, né dans la région de la pension, avec 1 chance sur 4 096 d'être chromatique.

## 5. Ouverture de Johto

- Johto s'ouvre quand les **151 Pokémon de Kanto** ont rejoint votre Pokédex.
- Cadeau : un **œuf d'un starter de Johto** (Germignon, Héricendre ou Kaiminus, au hasard).
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

## Limites actuelles

Les **151 Pokémon de Kanto** sont tous obtenables. À Johto, **8 espèces ne le sont pas encore**, car leur évolution
n'est pas gérée par le jeu :

| Pokémon | Évolue de | Condition (non gérée) |
|---|---|---|
| Nostenfer | Nosferalto | Bonheur élevé |
| Togetic | Togepi | Bonheur élevé |
| Leuphorie | Leveinard | Bonheur élevé |
| Mentali | Évoli | Bonheur élevé, de jour |
| Noctali | Évoli | Bonheur élevé, de nuit |
| Joliflor | Ortide | Pierre Soleil (absente du jeu) |
| Héliatronc | Tournegrin | Pierre Soleil (absente du jeu) |
| Kapoera | Debugant | Niveau 20, Attaque égale à la Défense |

Le Pokédex de Johto (251) ne peut donc pas encore être complété.
