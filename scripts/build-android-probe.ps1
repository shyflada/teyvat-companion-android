param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JdkRoot = $env:JAVA_HOME,
    [string]$QtDeployment,
    [string]$TestKeystore
)

$ErrorActionPreference = 'Stop'
$teyvatRoot = Split-Path -Parent $PSScriptRoot
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not $JdkRoot) {
    $teyvatJarCommand = Get-Command jar.exe -ErrorAction SilentlyContinue
    if ($teyvatJarCommand) { $JdkRoot = Split-Path -Parent (Split-Path -Parent $teyvatJarCommand.Source) }
}
if (-not $JdkRoot) { throw 'Install JDK 17 or later and pass its installation directory as -JdkRoot.' }

$teyvatTools = Join-Path $SdkRoot 'build-tools/36.0.0'
$teyvatPlatform = Join-Path $SdkRoot 'platforms/android-36/android.jar'
$teyvatJava = Join-Path $JdkRoot 'bin/java.exe'
$teyvatJavac = Join-Path $JdkRoot 'bin/javac.exe'
$teyvatJar = Join-Path $JdkRoot 'bin/jar.exe'
$teyvatKeytool = Join-Path $JdkRoot 'bin/keytool.exe'
$teyvatAapt = Join-Path $teyvatTools 'aapt2.exe'
$teyvatAlign = Join-Path $teyvatTools 'zipalign.exe'
$teyvatDex = Join-Path $teyvatTools 'lib/d8.jar'
$teyvatSigner = Join-Path $teyvatTools 'lib/apksigner.jar'
foreach ($teyvatTool in @($teyvatPlatform, $teyvatJava, $teyvatJavac, $teyvatJar, $teyvatKeytool, $teyvatAapt, $teyvatAlign, $teyvatDex, $teyvatSigner)) {
    if (-not (Test-Path -LiteralPath $teyvatTool -PathType Leaf)) { throw "Missing $teyvatTool. Use Android SDK Platform 36, Build Tools 36.0.0 and JDK 17 or later." }
}

function Invoke-ProbeTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path -Leaf $Executable) failed with exit code $LASTEXITCODE." }
}

$teyvatSource = Join-Path $teyvatRoot 'android-probe/app/src/main'
$teyvatWork = Join-Path $teyvatRoot 'work/android-probe'
$teyvatBuild = Join-Path $teyvatWork ('build-' + [Guid]::NewGuid().ToString('N'))
$teyvatRes = Join-Path $teyvatBuild 'res'
$teyvatGenerated = Join-Path $teyvatBuild 'generated'
$teyvatClasses = Join-Path $teyvatBuild 'classes'
$teyvatOutput = Join-Path $teyvatRoot 'outputs/android-probe'
New-Item -ItemType Directory -Force -Path $teyvatBuild,$teyvatGenerated,$teyvatClasses,$teyvatOutput | Out-Null
Copy-Item -LiteralPath (Join-Path $teyvatSource 'res') -Destination $teyvatRes -Recurse
$teyvatManifest = Join-Path $teyvatSource 'AndroidManifest.xml'
$teyvatJavaSources = @((Join-Path $teyvatSource 'java'), $teyvatGenerated)
$teyvatJarFiles = @()
$teyvatAssetArgs = @()
$teyvatJavaRelease = '8'
if ($QtDeployment) {
    $QtDeployment = (Resolve-Path -LiteralPath $QtDeployment).Path
    $teyvatManifest = Join-Path $QtDeployment 'AndroidManifest.xml'
    Copy-Item -Path (Join-Path $QtDeployment 'res/*') -Destination $teyvatRes -Recurse -Force
    $teyvatJavaSources += Join-Path $QtDeployment 'src'
    $teyvatJarFiles = @(Get-ChildItem -LiteralPath (Join-Path $QtDeployment 'libs') -File -Filter '*.jar' | ForEach-Object FullName)
    $teyvatAssets = Join-Path $QtDeployment 'assets'
    New-Item -ItemType Directory -Force -Path $teyvatAssets | Out-Null
    Copy-Item -LiteralPath (Join-Path $teyvatRoot 'src/hoyolab_site.js') -Destination $teyvatAssets -Force
    $teyvatAssetArgs = @('-A', $teyvatAssets)
    $teyvatJavaRelease = '17'
}
$teyvatIcons = Join-Path $teyvatRes 'mipmap-nodpi'
New-Item -ItemType Directory -Force -Path $teyvatIcons | Out-Null
Copy-Item -LiteralPath (Join-Path $teyvatRoot 'src/branding/teyvat-companion.png') -Destination (Join-Path $teyvatIcons 'teyvat_companion.png')

$teyvatResources = Join-Path $teyvatBuild 'resources.zip'
$teyvatUnsigned = Join-Path $teyvatBuild 'unsigned.apk'
$teyvatAligned = Join-Path $teyvatBuild 'aligned.apk'
$teyvatSigned = Join-Path $teyvatBuild 'signed.apk'
$teyvatBytecode = Join-Path $teyvatBuild 'classes.jar'
$teyvatKey = Join-Path $teyvatWork 'probe-debug.keystore'
if ($TestKeystore) { $teyvatKey = (Resolve-Path -LiteralPath $TestKeystore).Path }

Invoke-ProbeTool $teyvatAapt @('compile', '--dir', $teyvatRes, '-o', $teyvatResources)
Invoke-ProbeTool $teyvatAapt (@('link', '-I', $teyvatPlatform, '--manifest', $teyvatManifest, '--java', $teyvatGenerated, '-o', $teyvatUnsigned, $teyvatResources) + $teyvatAssetArgs)
$teyvatJavaFiles = @(Get-ChildItem -LiteralPath $teyvatJavaSources -Recurse -File -Filter '*.java' | ForEach-Object FullName)
$teyvatClasspath = (@($teyvatPlatform) + $teyvatJarFiles) -join [IO.Path]::PathSeparator
Invoke-ProbeTool $teyvatJavac (@('--release', $teyvatJavaRelease, '-Xlint:-options', '-encoding', 'UTF-8', '-classpath', $teyvatClasspath, '-d', $teyvatClasses) + $teyvatJavaFiles)
Invoke-ProbeTool $teyvatJar @("-J-Djava.io.tmpdir=$teyvatBuild", '--create', '--file', $teyvatBytecode, '-C', $teyvatClasses, '.')
Invoke-ProbeTool $teyvatJava (@('-cp', $teyvatDex, 'com.android.tools.r8.D8', '--min-api', '29', '--lib', $teyvatPlatform, '--output', $teyvatBuild, $teyvatBytecode) + $teyvatJarFiles)
$teyvatApkEntries = @(Get-ChildItem -LiteralPath $teyvatBuild -File -Filter 'classes*.dex' | ForEach-Object { '-C'; $teyvatBuild; $_.Name })
if ($QtDeployment) {
    $teyvatNative = Join-Path $teyvatBuild 'lib'
    New-Item -ItemType Directory -Path $teyvatNative | Out-Null
    Copy-Item -LiteralPath (Join-Path $QtDeployment 'libs/arm64-v8a') -Destination $teyvatNative -Recurse
    $teyvatApkEntries += @('-C', $teyvatBuild, 'lib')
}
Invoke-ProbeTool $teyvatJar (@("-J-Djava.io.tmpdir=$teyvatBuild", '--update', '--file', $teyvatUnsigned) + $teyvatApkEntries)
Invoke-ProbeTool $teyvatAlign @('-f', '4', $teyvatUnsigned, $teyvatAligned)

if (-not (Test-Path -LiteralPath $teyvatKey)) {
    # Public test-key password. The local key is only for updating this probe.
    Invoke-ProbeTool $teyvatKeytool @('-genkeypair', '-keystore', $teyvatKey, '-storepass', 'android', '-keypass', 'android', '-alias', 'teyvat-probe', '-dname', 'CN=Teyvat Companion Probe', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650')
}
Invoke-ProbeTool $teyvatJava @('-jar', $teyvatSigner, 'sign', '--ks', $teyvatKey, '--ks-pass', 'pass:android', '--ks-key-alias', 'teyvat-probe', '--out', $teyvatSigned, $teyvatAligned)
Invoke-ProbeTool $teyvatJava @('-jar', $teyvatSigner, 'verify', '--verbose', $teyvatSigned)
Invoke-ProbeTool $teyvatAlign @('-c', '4', $teyvatSigned)
$teyvatApkName = 'Teyvat-Companion-Android-Probe.apk'
if ($QtDeployment) {
    [xml]$teyvatAndroidManifest = Get-Content -LiteralPath $teyvatManifest -Raw
    $teyvatVersion = $teyvatAndroidManifest.DocumentElement.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
    if ($teyvatVersion -notmatch '^[0-9A-Za-z.-]+$') { throw 'Missing or invalid navigation APK version.' }
    $teyvatApkName = "Teyvat-Companion-$teyvatVersion.apk"
}
$teyvatApk = Join-Path $teyvatOutput $teyvatApkName
Copy-Item -LiteralPath $teyvatSigned -Destination $teyvatApk -Force
Get-Item -LiteralPath $teyvatApk
