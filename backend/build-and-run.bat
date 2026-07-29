@echo off
chcp 65001 > nul
echo ============================================
echo  YouTube Playlist Downloader
echo ============================================
echo.

if "%1"=="run" goto :run_only

echo [1/2] Building...
call mvn clean package -DskipTests -q
if errorlevel 1 (
    echo BUILD FAILED!
    pause
    exit /b 1
)
echo Build OK.
echo.

:run_only
echo [2/2] Starting server at http://localhost:8080
echo Press Ctrl+C to stop.
echo.
set JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
java -jar target\youtube-playlist-downloader-1.0.0.jar
