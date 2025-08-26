#!/usr/bin/env bash

# exit-on-error
set -e

usage() {
    echo "Usage: ${0} [OPTIONS]"
	echo ""
	echo "Options"
	echo "--help -h                                    : Display this message."
	echo "--gcc-version <valid gcc version i.e. 9.2.0> : GCC version to build from source."

	exit "$1"
}

while [ "$1" != "" ];
do
    case $1 in
		-h | --help )
		    usage 1 ;;
		--gcc-version )
		    shift
		    VERSION="gcc-$1" ;;
		* )
		    echo "Invalid option $1"
			usage 0 ;;
    esac
	shift
done

wget https://ftp.gnu.org/gnu/gcc/$VERSION/$VERSION.tar.gz

tar xzf $VERSION.tar.gz

cd $VERSION
./contrib/download_prerequisites
cd ..

mkdir -p $VERSION-build
cd $VERSION-build

mkdir -p $(pwd)/../../host-gcc-install
../$VERSION/configure --enable-languages=c,c++ --disable-multilib --prefix=$(pwd)/../../host-gcc-install

make -j32
make install
