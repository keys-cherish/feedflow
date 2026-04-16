@echo off
set JAVA_HOME=D:\Program Files\EnvironmentVariables\dragonwell-17
set ANDROID_HOME=D:\Program Files\EnvironmentVariables
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d D:\temp_build\feedflow\android
echo === Generating Gradle Wrapper ===
call "C:\Users\test\.gradle\wrapper\dists\gradle-8.12-all\5xz8zfvr8cydg32e8t979sl6f\gradle-8.12\bin\gradle.bat" wrapper --gradle-version 8.11.1
echo === Building Debug APK ===
call gradlew.bat assembleDebug
echo === DONE ===
dir /b app\build\outputs\apk\debug\*.apk 2>nul
