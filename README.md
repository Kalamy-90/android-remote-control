# Android Remote Control

Application Android permettant de contrôler un smartphone à distance via un navigateur web, sans installer d'application tierce sur le client.

## Fonctionnalités

*   **Contrôle total** : Vue écran en temps réel et contrôle tactile (clic, swipe).
*   **Zéro installation client** : Fonctionne depuis n'importe quel navigateur web moderne.
*   **Sécurité** : Authentification par PIN, HTTPS, et notification permanente sur le téléphone.
*   **Performance** : Streaming MJPEG via WebSocket et serveur Ktor embarqué.

## Architecture

*   **Langage** : Kotlin
*   **Serveur Web** : Ktor (Netty)
*   **Capture** : MediaProjection API
*   **Injection** : AccessibilityService

## Installation

1.  Téléchargez l'APK depuis l'onglet "Releases" de GitHub.
2.  Installez l'APK sur votre téléphone Android.
3.  Lancez l'application et accordez les permissions demandées (Accessibilité, Capture d'écran).
4.  Ouvrez l'URL affichée (ex: `https://192.168.1.x:8080`) sur votre PC.
5.  Entrez le code PIN affiché sur le téléphone.

## Compilation

Pour compiler le projet vous-même :

```bash
./gradlew assembleDebug
```

L'APK sera généré dans `app/build/outputs/apk/debug/app-debug.apk`.

## Avertissement

Cette application est destinée à un usage légitime (support technique, contrôle parental, usage personnel). L'utilisation à des fins de surveillance à l'insu de l'utilisateur est strictement interdite et empêchée par les mécanismes de sécurité d'Android (notifications permanentes).
