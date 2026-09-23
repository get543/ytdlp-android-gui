# yt-dlp Android GUI

A lightweight, native Android frontend for [yt-dlp](https://github.com/yt-dlp/yt-dlp), built with Kotlin. This app provides a user-friendly interface to download videos and audio from thousands of supported sites directly to your Android device.


[![Download APK](https://img.shields.io/badge/Download_APK-2EA043?style=for-the-badge&logo=android&logoColor=white)](https://github.com/get543/ytdlp-android-gui/releases/latest/download/app-release.apk)

## 🚀 Features

- **Background Downloads**: Powered by a Foreground Service so downloads continue even if the app is minimized.
- **Real-time Progress**: Notification with a progress bar and ETA tracking.
- **Advanced Format Selection**: 
  - Video: 720p, 1080p, 1440p, 4K (2160p), or "Best Quality".
  - Audio: M4A, MP3, WebM, or Opus.
- **Playlist Support**: Seamlessly download entire video or music playlists into structured subfolders.
- **Embed Options**: Toggle embedding of Chapters, Thumbnails, Subtitles, and Metadata directly into the files.
- **In-App Updates**: Automatically checks for new releases on GitHub and prompts to update with a progress-tracked downloader.
- **Smart Logging**: Built-in console output viewer to debug or track `yt-dlp` activity.
- **Clean Management**: Options to clear app logs and purge temporary cache files.
- **Custom Storage**: Downloads are organized in a dedicated `Downloads/yt-dlp GUI/` folder.

## 🛠️ Built With

- **Kotlin**: Primary programming language.
- **[youtubedl-android](https://github.com/yausername/youtubedl-android)**: A library wrapper for `yt-dlp` on Android.
- **Coroutines**: For non-blocking asynchronous operations.
- **Material 3**: For a modern and clean user interface.
- **GitHub Actions**: Automated CI/CD pipeline for signed APK releases.

## 📦 Installation & Setup

### For Users
1. Download the latest APK from the [Releases](https://github.com/get543/ytdlp-android-gui/releases) page.
2. Install the APK (you may need to allow "Install from Unknown Sources").
3. Launch the app, paste a URL, and start downloading!

### For Developers
1. Clone the repository:
   ```bash
   git clone https://github.com/get543/ytdlp-android-gui.git
   ```
2. Open the project in **Android Studio**.
3. Update the GitHub repository constants in `AppUpdater.kt` to point to your fork.
4. Build the project using `./gradlew assembleDebug`.

## 🛡️ Permissions
- **Internet**: To download media and check for updates.
- **Foreground Service**: To keep downloads alive in the background.
- **Post Notifications** (Android 13+): To show download progress in the notification tray.
- **Write External Storage** (Android 9 & below): To save files to the public Downloads folder.

## 📄 License
This project is for educational purposes. Please ensure you comply with the terms of service of any website you download from and respect copyright laws.

---
*Developed with ❤️ using Kotlin.*
