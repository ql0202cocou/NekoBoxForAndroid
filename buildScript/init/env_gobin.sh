#!/bin/bash

# go install 优先装到 GOBIN，未设置时才是 GOPATH/bin：libcore/init.sh 把
# gomobile-matsuri 装到这里，libcore/build.sh 从同一位置调用。不跟随 GOBIN
# 的话，设置了 GOBIN 的机器上 init.sh 的 mv 会报一句没头没尾的
# "No such file or directory"
GOBIN_DIR=$(go env GOBIN)
if [ -z "$GOBIN_DIR" ]; then
  if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
  fi
  GOBIN_DIR="$GOPATH/bin"
fi
