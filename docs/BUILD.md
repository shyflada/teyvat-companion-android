# Build Teyvat Companion for Android

Use Windows x64, PowerShell 7, CMake 3.24 or newer, Ninja and JDK 17 or newer.
Install Android SDK Platform 36, Build Tools 36.0.0 and NDK r27c
(`27.2.12479018`) through the [Android SDK Manager](https://developer.android.com/tools/sdkmanager).
Install [Qt 6.11.3](https://doc.qt.io/qt-6/android-getting-started.html) for Android
ARM64 with Qt Image Formats, and its matching Windows host kit with
`androiddeployqt.exe` and MinGW 13.1. Qt must provide SQLite and WebP plugins.

From the repository root, set the SDK, Qt and JDK paths to your installations:

~~~powershell
$sdk = 'C:/Android/Sdk'
$qtAndroid = 'C:/Qt/6.11.3/android_arm64_v8a'
$qtHost = 'C:/Qt/6.11.3/mingw_64'
$jdk = 'C:/Java/jdk-21'

./scripts/setup-opencv.ps1 -SdkRoot $sdk

$buildOptions = @{
    SdkRoot = $sdk
    QtRoot = $qtAndroid
    QtHost = $qtHost
    JdkRoot = $jdk
}
./scripts/build-android-navigation.ps1 @buildOptions
~~~

Pass `-NdkRoot` to both scripts if you installed the NDK outside the SDK.
The build verifies the OpenCV source archive and compiles its ARM64 static
libraries. The preview APK is created under `outputs/android-probe/`. The
packaging script verifies its Android signature. A fresh checkout creates a
local development signing key, so its APK can update only APKs signed by that
same key. Preserve that key outside Git for your own development builds.

To check the official map UI adapter with Node.js:

~~~powershell
node tests/hoyolab_site_tests.js
~~~

The test simulates marking, confirmation, undo, and a 100-pin Android queue.
To test the portable C++ core with a host C++20 compiler:

~~~powershell
cmake -S . -B work/build-core -G Ninja -DTEYVAT_BUILD_ANDROID_NAVIGATION=OFF
cmake --build work/build-core
ctest --test-dir work/build-core --output-on-failure
~~~
