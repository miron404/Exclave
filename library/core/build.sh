#!/bin/bash

# GOMOBILE_TARGET narrows the build to one ABI for a quick development cycle.
# Unset means every Android ABI.
CGO_LDFLAGS="-Wl,-z,max-page-size=16384" gomobile bind -v -androidapi 21 -trimpath -ldflags="-s -buildid=" -tags="with_clash" -target="${GOMOBILE_TARGET:-android}" "github.com/exclavenetwork/libexclavecore" || exit 1

proj=../../app/libs
if [ -d $proj ]; then
  cp -vf libexclavecore.aar $proj
fi
