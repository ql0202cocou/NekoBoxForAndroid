#!/bin/bash

if [ -z "$ANDROID_HOME" ]; then
  if [ -d "$HOME/Android/Sdk" ]; then
    export ANDROID_HOME="$HOME/Android/Sdk"
  elif [ -d "$HOME/.local/lib/android/sdk" ]; then
    export ANDROID_HOME="$HOME/.local/lib/android/sdk"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi

_NDK_VERSION=25.0.8775105
_NDK="$ANDROID_HOME/ndk/$_NDK_VERSION"
[ -f "$_NDK/source.properties" ] || _NDK="$ANDROID_NDK_HOME"
[ -f "$_NDK/source.properties" ] || _NDK="$NDK"
[ -f "$_NDK/source.properties" ] || _NDK="$ANDROID_HOME/ndk-bundle"

if [ ! -f "$_NDK/source.properties" ]; then
  echo "Error: NDK not found."
  exit 1
fi

# 回退找到的 NDK 也必须是这个版本：libcore/build.sh 的链接参数是按 r25 链接器的行为加的，换版本行为会变
_NDK_REV=$(sed -n 's/^Pkg\.Revision *= *//p' "$_NDK/source.properties" | tr -d '[:space:]')
if [ "$_NDK_REV" != "$_NDK_VERSION" ]; then
  echo "Error: NDK at $_NDK is ${_NDK_REV:-of unknown version}, need $_NDK_VERSION."
  exit 1
fi

export ANDROID_NDK_HOME=$_NDK
export NDK=$_NDK
