param([string]$SdkRoot, [string]$NdkRoot, [ValidateRange(1,16)][int]$Jobs = 4)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (!$SdkRoot) { $SdkRoot = Join-Path $root 'work/android-sdk' }
if (!$NdkRoot) { $NdkRoot = Join-Path $SdkRoot 'ndk/27.2.12479018' }
$toolchain = Join-Path $NdkRoot 'build/cmake/android.toolchain.cmake'
if (!(Test-Path -LiteralPath $toolchain)) { throw 'Pass the Android NDK r27c directory as -NdkRoot.' }

$version = '4.12.0'
$cache = Join-Path $root 'work/downloads'
$sourceDir = Join-Path $root 'work/third-party'
$archive = Join-Path $cache "opencv-$version.tar.gz"
New-Item -ItemType Directory -Force -Path $cache,$sourceDir | Out-Null
if (!(Test-Path -LiteralPath $archive)) {
    curl.exe --fail --location --proto '=https' --proto-redir '=https' --output $archive "https://github.com/opencv/opencv/archive/refs/tags/$version.tar.gz"
    if ($LASTEXITCODE -ne 0) { throw 'OpenCV source download failed.' }
}
if ((Get-FileHash -LiteralPath $archive).Hash -ne '44c106d5bb47efec04e531fd93008b3fcd1d27138985c5baf4eafac0e1ec9e9d') {
    throw 'OpenCV source checksum mismatch.'
}
$source = Join-Path $sourceDir "opencv-$version"
if (!(Test-Path -LiteralPath (Join-Path $source 'CMakeLists.txt'))) {
    tar.exe -xf $archive -C $sourceDir
    if ($LASTEXITCODE -ne 0) { throw 'OpenCV source extraction failed.' }
}

$build = Join-Path $root 'work/build-opencv-android'
$profile = [Environment]::GetFolderPath('UserProfile')
$profilePath = $profile.Replace('\','/')
$sourcePath = $root.Replace('\','/')
$sdkPath = (Resolve-Path -LiteralPath $SdkRoot).Path
$ndkPath = (Resolve-Path -LiteralPath $NdkRoot).Path
$pathFlags = '-ffile-prefix-map="{0}"=. -ffile-prefix-map="{1}"=.' -f $profilePath,$sourcePath
$options = @(
    "-DCMAKE_TOOLCHAIN_FILE=$toolchain", "-DANDROID_SDK_ROOT=$SdkRoot", '-DANDROID_ABI=arm64-v8a',
    '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_shared', '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON',
    "-DCMAKE_C_FLAGS=$pathFlags", "-DCMAKE_CXX_FLAGS=$pathFlags", '-DBUILD_LIST=core,imgproc,features2d,calib3d',
    '-DBUILD_SHARED_LIBS=OFF', '-DBUILD_TESTS=OFF', '-DBUILD_PERF_TESTS=OFF', '-DBUILD_EXAMPLES=OFF',
    '-DBUILD_ANDROID_EXAMPLES=OFF', '-DBUILD_ANDROID_PROJECTS=OFF', '-DBUILD_ANDROID_SERVICE=OFF',
    '-DBUILD_opencv_apps=OFF', '-DBUILD_JAVA=OFF', '-DBUILD_opencv_java=OFF', '-DBUILD_opencv_python3=OFF',
    '-DWITH_IPP=OFF', '-DWITH_ITT=OFF', '-DWITH_OPENCL=OFF', '-DWITH_TBB=OFF', '-DOPENCV_ENABLE_NONFREE=OFF'
)
cmake -S $source -B $build -G Ninja -DCMAKE_BUILD_TYPE=Release @options
if ($LASTEXITCODE -ne 0) { throw 'Android OpenCV configuration failed.' }

foreach ($generated in @('version_string.tmp','modules/core/version_string.inc','opencv_data_config.hpp')) {
    $path = Join-Path $build $generated
    if (!(Test-Path -LiteralPath $path)) { continue }
    $contents = Get-Content -LiteralPath $path -Raw
    foreach ($prefix in @($sourcePath,$ndkPath.Replace('\','/'),$sdkPath.Replace('\','/'),$profilePath,
            $root.Replace('\','\\'),$ndkPath.Replace('\','\\'),$sdkPath.Replace('\','\\'),$profile.Replace('\','\\'))) {
        $contents = $contents.Replace($prefix, '.', [StringComparison]::OrdinalIgnoreCase)
    }
    Set-Content -LiteralPath $path -Value $contents -NoNewline -Encoding utf8
}
cmake --build $build --parallel $Jobs
if ($LASTEXITCODE -ne 0) { throw 'Android OpenCV build failed.' }
