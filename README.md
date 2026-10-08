# 🎵 SoukoPlayer

<p align="center">
  <b>A lightweight, clean, and high-performance Navidrome & Subsonic player for Android.</b>
  <br />
  一款专为 Navidrome 自建音乐服务打造的轻量、高效 Android 客户端。
</p>

<p align="center">
  <img src="https://img.shields.org/badge/Platform-Android-green.svg" alt="Platform" />
  <img src="https://img.shields.org/badge/Server-Navidrome%20%2F%20Subsonic-blue.svg" alt="Server" />
  <img src="https://img.shields.org/badge/Language-Kotlin-orange.svg" alt="Language" />
  <img src="https://img.shields.org/badge/License-MIT-blue.svg" alt="License" />
</p>

---

## 📖 简介 (About)

**SoukoPlayer** 是一款专注于 **Navidrome** 生态的 Android 音频播放客户端。摒弃商业播放器的臃肿与广告，致力于提供毫秒级连接响应、流畅的专辑漫游体验与高品质无损音频流式播放。

> 💡 *“连接你的私有云曲库，随时随地享受原汁原味的音乐体验。”*

---

## ✨ 核心特性 (Features)

- ☁️ **无缝连接 Navidrome**：完美适配 Subsonic API，支持全库高速同步、专辑/艺术家分类与歌单管理。
- 🎧 **无损流式播放**：针对 FLAC、WAV、AAC、MP3 等格式进行流媒体优化，支持高质量音频原汁原味串流。
- ⚡ **极致轻量**：纯粹无广告、低内存占用，专注于播放体验与长续航表现。
- 🎨 **现代 UI 交互**：简洁直观的界面语言，支持大图封面高清渲染与流畅的播放控制。
- 🔊 **系统级播放控制**：完美适配 Android 媒体通知栏控制。

---

## 📸 应用截图 (Screenshots)


| 播放界面 | 曲目列表 | 专辑列表 |
| :---: | :---: | :---: |
| <img width="1216" height="2640" alt="Screenshot_20261008_161904" src="https://github.com/user-attachments/assets/76872573-5fcd-4c3a-8076-8a4bffcaab02" />
| <img width="1216" height="2640" alt="Screenshot_20261008_161855" src="https://github.com/user-attachments/assets/f8271987-7749-4633-b9a9-df580aaffd4c" />
| <img width="1216" height="2640" alt="Screenshot_20261008_161826" src="https://github.com/user-attachments/assets/8a73be81-90c9-4196-a4c0-14438aad55a1" />

---

## 🛠️ 技术栈 (Tech Stack)

- **Language**: Kotlin
- **Protocol**: Subsonic REST API
- **Architecture**: MVVM / Jetpack
- **Audio Engine**: Media3 / ExoPlayer
- **Network & Async**: Retrofit / Coroutines + Flow

---

## 🚀 编译与安装 (Build & Setup)

```bash
# 克隆仓库
git clone [https://github.com/lengyusc/SoukoPlayer.git](https://github.com/lengyusc/SoukoPlayer.git)

# 使用 Android Studio (Ladybug 或更高版本) 打开项目直接构建运行
