# Android third-party notices

Teyvat Companion's own source is released under the [MIT License](LICENSE).

| Component | Version and source | Use |
| --- | --- | --- |
| Qt Base and Qt Image Formats | [Qt Base 6.11.3 source](https://download.qt.io/official_releases/qt/6.11/6.11.3/submodules/qtbase-everywhere-src-6.11.3.tar.xz), [Qt Image Formats 6.11.3 source](https://download.qt.io/official_releases/qt/6.11/6.11.3/submodules/qtimageformats-everywhere-src-6.11.3.tar.xz), [Qt licensing](https://doc.qt.io/qt-6/licensing.html) | Dynamic Qt Core, Gui, Sql and Android, SQLite and WebP plugins |
| OpenCV | [4.12.0 source](https://github.com/opencv/opencv/tree/4.12.0), Apache-2.0, with upstream notices for bundled code | Static image processing and minimap matching |
| Android NDK libc++ | [NDK r27c](https://developer.android.com/ndk/downloads/revision_history), [libc++ license](https://github.com/llvm/llvm-project/blob/main/libcxx/LICENSE.TXT) | C++ runtime |
| Genshin Impact and HoYoLAB map data | [HoYoLAB interactive map](https://act.hoyolab.com/ys/app/interactive-map/index.html) | Downloaded on demand by the app; HoYoverse retains rights to its content |

See [the build guide](docs/BUILD.md) to build the app and reproduce its dependency
configuration. Release artifacts include the applicable third-party notices.
