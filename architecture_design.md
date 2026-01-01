# Architecture et Choix Techniques - Android Remote Control

Ce document détaille l'architecture technique et justifie les choix technologiques pour l'application de contrôle à distance Android.

## 1. Architecture Globale

L'application repose sur une architecture **Client-Serveur locale**, où le smartphone Android agit comme le serveur et le navigateur web (PC/Tablette) agit comme le client.

### Composants Principaux

1.  **Serveur Web Embarqué (Android)** :
    *   Héberge l'interface web (fichiers HTML/CSS/JS).
    *   Gère les connexions WebSocket pour le streaming vidéo et les commandes.
    *   Expose une API REST sécurisée pour l'authentification et le statut.

2.  **Service de Capture (Android)** :
    *   Utilise l'API `MediaProjection` pour capturer le contenu de l'écran en temps réel.
    *   Encode les frames (JPEG ou H.264 selon compatibilité) et les transmet via WebSocket.

3.  **Service d'Accessibilité (Android)** :
    *   Reçoit les coordonnées et types d'actions (clic, swipe) depuis le serveur web.
    *   Injecte ces événements dans le système Android via l'API `AccessibilityService` (permettant le contrôle sans root).

4.  **Client Web (Navigateur)** :
    *   Affiche le flux vidéo reçu via WebSocket.
    *   Capture les événements souris/clavier de l'utilisateur et les transmet au téléphone.
    *   Gère l'authentification initiale.

## 2. Justification des Choix Techniques

### Langage et Plateforme : Kotlin
*   **Choix :** Kotlin.
*   **Justification :** Langage officiel et moderne pour Android. Sa gestion native des coroutines est cruciale pour gérer efficacement les threads du serveur web, l'encodage vidéo et les E/S réseau sans bloquer l'interface utilisateur (UI) du téléphone.

### Serveur Web Embarqué : Ktor (Server Embedded)
*   **Choix :** Ktor (Moteur Netty ou CIO).
*   **Justification :**
    *   Contrairement à `NanoHTTPD` (souvent obsolète ou limité), **Ktor** est une solution moderne, asynchrone et 100% Kotlin.
    *   Support natif et performant des **WebSockets**, indispensable pour le streaming vidéo fluide.
    *   Facilité de mise en place du **HTTPS** avec certificats auto-signés dynamiques.

### Communication Temps Réel : WebSockets
*   **Choix :** WebSockets (vs HTTP Polling ou MJPEG stream).
*   **Justification :**
    *   Permet une communication bidirectionnelle à très faible latence.
    *   Le flux vidéo est envoyé sous forme de données binaires (blobs) sur le socket.
    *   Les commandes tactiles sont envoyées sous forme de texte/JSON sur le même socket ou un socket dédié, garantissant une réactivité immédiate.

### Capture d'Écran : MediaProjection API
*   **Choix :** `MediaProjection` + Compression JPEG.
*   **Justification :**
    *   Seule méthode standard (non-root) pour capturer l'écran.
    *   Pour minimiser la latence et la complexité de décodage côté navigateur, l'envoi de frames JPEG compressées via WebSocket est souvent plus robuste et universel que le streaming H.264 (qui nécessite MSE/WebRTC côté client et une gestion complexe des keyframes).

### Contrôle Tactile : AccessibilityService
*   **Choix :** `AccessibilityService`.
*   **Justification :**
    *   Depuis Android 7.0, c'est la seule API officielle permettant à une application d'injecter des gestes (dispatchGesture) dans d'autres applications sans privilèges root.
    *   Nécessite une activation manuelle explicite de l'utilisateur dans les paramètres, ce qui renforce la sécurité et la conscience de l'utilisateur (conformité éthique).

### Frontend Web : HTML5 / JS (Vanilla)
*   **Choix :** HTML/CSS/JS pur (sans framework lourd type React/Angular pour le runtime).
*   **Justification :**
    *   L'application doit servir les fichiers web depuis ses assets. Pour garder l'APK léger et le chargement instantané, une approche "Vanilla JS" ou très légère est préférable.
    *   Pas de dépendance à Internet pour charger des librairies (CDN), tout est embarqué.

### Sécurité
*   **HTTPS Auto-signé :** Nécessaire pour que les navigateurs modernes acceptent les connexions sécurisées et potentiellement certaines fonctionnalités web.
*   **Token d'accès :** Un code PIN ou Token aléatoire généré au lancement de l'app Android doit être saisi sur l'interface web pour établir la connexion WebSocket. Cela empêche tout contrôle non autorisé sur le réseau local.
