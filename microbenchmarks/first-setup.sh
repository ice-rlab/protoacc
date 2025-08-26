#!/usr/bin/env bash

set -e

STARTDIR=$(pwd)

# Build gcc version from source.
cd host-gcc-build
./build-host-gcc.sh --gcc-version 9.2.0

sudo yum -y install glibc-static
sudo pip install scipy
