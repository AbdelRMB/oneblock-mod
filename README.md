# 🧱 OneBlock Multijoueur — Mod Forge 1.18.2

Un mod OneBlock pour serveur Forge où **chaque joueur a son propre bloc**, isolé des autres.

---

## 📦 Prérequis

- **Minecraft** 1.18.2
- **Forge** 40.x (télécharger sur [files.minecraftforge.net](https://files.minecraftforge.net))
- **Java** 17+
- **ForgeGradle** (inclus via le MDK)

---

## 🚀 Installation & Compilation

### 1. Télécharger le MDK Forge

```bash
# Télécharge le MDK 1.18.2 sur https://files.minecraftforge.net
# Extrait le MDK, puis remplace le src/ par le code de ce mod
```

### 2. Compiler le mod

```bash
# Dans le dossier du projet
./gradlew build

# Le .jar sera dans : build/libs/oneblock-1.0.0.jar
```

### 3. Installer sur le serveur

```
server/
├── mods/
│   └── oneblock-1.0.0.jar   ← colle le jar ici
├── forge-1.18.2-*.jar
└── ...
```

---

## 🌍 Configuration du monde (IMPORTANT)

Pour que le OneBlock fonctionne, le monde doit être **vide** (Void).

### Option A : Superflat Void (recommandé)

Dans `server.properties` :
```properties
level-type=flat
generator-settings={"biome":"minecraft:the_void","layers":[{"block":"minecraft:air","height":1}],"structures":{"structures":{}}}
```

### Option B : Plugin VoidWorld (alternative)
Installe un plugin Forge pour générer un monde vide.

---

## ⚙️ Configuration du mod

Fichier : `config/oneblock-server.toml`

```toml
[general]
    # Distance entre les îles (blocs). Default: 200
    islandSpacing = 200
    
    # Hauteur Y du bloc OneBlock. Default: 64
    blockY = 64
    
    # Plateforme de verre 3x3 au départ. Default: true
    starterPlatform = true
    
    # Téléporter si chute dans le vide. Default: true
    voidTeleport = true
    
    # Messages de progression. Default: true
    progressMessages = true
```

---

## 🎮 Commandes en jeu

| Commande | Description |
|---|---|
| `/oneblock stats` | Affiche ta progression (phase, blocs cassés) |
| `/oneblock tp` | Téléporte-toi à ton bloc OneBlock |
| `/oneblock phase` | Affiche ta phase actuelle |

---

## 📈 Phases de progression

| Phase | Déblocage | Contenu |
|---|---|---|
| 🌿 **Plaines** | Départ | Herbe, terre, bois, sable... |
| ⛏️ **Underground** | 100 blocs | Pierre, minerais (fer, or, diamant)... |
| 🌊 **Océan** | 300 blocs | Sable, argile, lanternes marines... |
| 🌿 **Jungle** | 500 blocs | Bois de jungle, bambou, melon... |
| 🏜️ **Désert** | 700 blocs | Sable rouge, grès, cactus... |
| 🔥 **Nether** | 900 blocs | Netherrack, quartz, crimson... |
| 🌌 **End** | 1100 blocs | End stone, purpur, diamant, émeraude... |

---

## 🗂️ Structure du code

```
src/main/java/com/oneblock/mod/
├── OneBlockMod.java              ← Point d'entrée du mod
├── config/
│   └── OneBlockConfig.java       ← Configuration TOML
├── data/
│   └── PlayerDataManager.java    ← Persistance des données joueur
├── event/
│   ├── PlayerEventHandler.java   ← Connexion, casse de blocs, chute
│   └── OneBlockCommands.java     ← Commandes /oneblock
└── world/
    ├── OneBlockPhase.java        ← Système de phases et blocs pondérés
    └── OneBlockWorldGen.java     ← Génération/régénération des îles
```

---

## 🔧 Ajouter des phases / blocs

Dans `OneBlockPhase.java`, modifier ou ajouter une phase :

```java
NEW_PHASE(1300, 200, "§dMa Phase",
    new WeightedBlock[]{
        new WeightedBlock(Blocks.AMETHYST_BLOCK, 30),  // poids 30
        new WeightedBlock(Blocks.CALCITE, 20),          // poids 20
        // ...
    }
),
```

Les poids sont relatifs : un bloc avec poids 30 a 3x plus de chances qu'un bloc avec poids 10.

---

## 📝 Données sauvegardées

Les données de chaque joueur sont dans :
```
world/
└── oneblock_data/
    ├── <UUID_joueur_1>.dat
    ├── <UUID_joueur_2>.dat
    └── ...
```

Format NBT : `BlocksBroken`, `BlockX/Y/Z`, `IslandIndex`.

---

## ❓ FAQ

**Q : Les joueurs peuvent-ils se visiter mutuellement ?**  
R : Oui ! Ils doivent juste se téléporter manuellement avec `/tp`. Les îles sont séparées de 200 blocs par défaut.

**Q : Que se passe-t-il si un joueur tombe dans le vide ?**  
R : Il est automatiquement retéléporté au-dessus de son bloc (configurable).

**Q : Peut-on jouer en coop sur la même île ?**  
R : Oui, les joueurs peuvent se téléporter chez quelqu'un et casser son bloc ensemble. La progression est liée au propriétaire de l'île.
