#!/bin/bash
# Prepare script for Actions Gradle build
set -e

#### Download assets
bash buildScript/lib/assets.sh

#### 下载内置 Xray 核心
bash buildScript/lib/plugins.sh

