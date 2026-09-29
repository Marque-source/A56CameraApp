#!/usr/bin/env sh
# Gradle wrapper başlatıcı — Android Studio veya CLI ile kullanın
DIR="$(cd "$(dirname "$0")" && pwd)"
exec gradle "$@"
