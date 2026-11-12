param(
    [string]$SdkRoot,
    [string]$NdkRoot,
    [string]$QtRoot,
    [string]$QtHost,
    [string]$OpenCvDir,
    [string]$JdkRoot = $env:JAVA_HOME,
    [string]$TestKeystore
)

$ErrorActionPreference = 'Stop'
$teyvatRoot = Split-Path -Parent $PSScriptRoot
$teyvatWork = Join-Path $teyvatRoot 'work'
if (-not $SdkRoot) { $SdkRoot = Join-Path $teyvatWork 'android-sdk' }
if (-not $NdkRoot) { $NdkRoot = Join-Path $SdkRoot 'ndk/27.2.12479018' }
if (-not $QtRoot) { $QtRoot = Join-Path $teyvatWork 'qt-android' }
if (-not $QtHost) { $QtHost = Join-Path $teyvatWork 'toolchain' }
if (-not $OpenCvDir) { $OpenCvDir = Join-Path $teyvatWork 'build-opencv-android' }
if (-not $JdkRoot) {
    $teyvatJarCommand = Get-Command jar.exe -ErrorAction SilentlyContinue
    if ($teyvatJarCommand) { $JdkRoot = Split-Path -Parent (Split-Path -Parent $teyvatJarCommand.Source) }
}
if (-not $JdkRoot) { throw 'Pass a JDK 17 or later installation directory as -JdkRoot.' }

$teyvatNdkToolchain = Join-Path $NdkRoot 'build/cmake/android.toolchain.cmake'
$teyvatStrip = Join-Path $NdkRoot 'toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe'
$teyvatQtToolchain = Join-Path $QtRoot 'lib/cmake/Qt6/qt.toolchain.cmake'
$teyvatDeployQt = Join-Path $QtHost 'bin/androiddeployqt.exe'
$teyvatBuild = Join-Path $teyvatWork 'build-android-navigation'
$teyvatDeploy = Join-Path $teyvatBuild ('android-deployment-' + [Guid]::NewGuid().ToString('N'))
foreach ($teyvatFile in @($teyvatNdkToolchain, $teyvatStrip, $teyvatQtToolchain, $teyvatDeployQt, (Join-Path $OpenCvDir 'OpenCVConfig.cmake'))) {
    if (-not (Test-Path -LiteralPath $teyvatFile -PathType Leaf)) { throw "Missing Android build dependency $teyvatFile." }
}

$teyvatOriginalPath = $env:PATH
try {
    $env:PATH = (Join-Path $QtHost 'Tools/mingw1310_64/bin') + ';' + (Join-Path $QtHost 'bin') + ';' + $env:PATH
    cmake -Wno-deprecated -S $teyvatRoot -B $teyvatBuild -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$teyvatQtToolchain" `
        "-DQT_CHAINLOAD_TOOLCHAIN_FILE=$teyvatNdkToolchain" "-DANDROID_NDK_ROOT=$NdkRoot" "-DANDROID_SDK_ROOT=$SdkRoot" `
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON `
        "-DQT_HOST_PATH=$QtHost" "-DCMAKE_PREFIX_PATH=$QtRoot" "-DOpenCV_DIR=$OpenCvDir" -DCMAKE_BUILD_TYPE=Release `
        -DBUILD_TESTING=OFF -DTEYVAT_BUILD_ANDROID_NAVIGATION=ON
    if ($LASTEXITCODE -ne 0) { throw 'Android navigation configuration failed.' }
    cmake --build $teyvatBuild --target teyvat_android --parallel 4
    if ($LASTEXITCODE -ne 0) { throw 'Android navigation compilation failed.' }
    $teyvatNative = Join-Path $teyvatDeploy 'libs/arm64-v8a'
    New-Item -ItemType Directory -Force -Path $teyvatNative | Out-Null
    & $teyvatStrip --strip-debug -o (Join-Path $teyvatNative 'libteyvat_android_arm64-v8a.so') (Join-Path $teyvatBuild 'libteyvat_android_arm64-v8a.so')
    if ($LASTEXITCODE -ne 0) { throw 'Could not remove native debug paths.' }
    & $teyvatDeployQt --input (Join-Path $teyvatBuild 'android-teyvat_android-deployment-settings.json') `
        --output $teyvatDeploy --android-platform android-36 --jdk $JdkRoot --aux-mode
    if ($LASTEXITCODE -ne 0) { throw 'Qt Android dependency deployment failed.' }
    $teyvatQtBindings = 'org/qtproject/qt/android/bindings'
    $teyvatBindingsParent = Join-Path $teyvatDeploy 'src/org/qtproject/qt/android'
    New-Item -ItemType Directory -Force -Path $teyvatBindingsParent | Out-Null
    Copy-Item -LiteralPath (Join-Path $QtRoot "src/android/java/src/$teyvatQtBindings") -Destination $teyvatBindingsParent -Recurse -Force
} finally { $env:PATH = $teyvatOriginalPath }

& (Join-Path $PSScriptRoot 'build-android-probe.ps1') -SdkRoot $SdkRoot -JdkRoot $JdkRoot -QtDeployment $teyvatDeploy -TestKeystore $TestKeystore
