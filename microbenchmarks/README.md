# Protocacc Microbenchmarks

## Getting Started

The steps below have been automated with Bash and Python scripts and Makefiles in the `microbenchmarks/` directory.

> [!IMPORTANT]
> Make sure to change driectory into the `microbencharks/` directory to be able to run the steps below.

### First Setup

Execute the [microbenchmarks/first-setup.sh](./first-setup.sh).

```bash
./first-setup.sh
```

The main thing this script automates is the building of `gcc` version 9.2.0 from source which will be used to compile the microbenchmarks.

### Build Protocol Buffers Compiler, C++ Runtime and Library

Execute the [microbenchmarks/build-protobuf-all.sh](./build-protobuf-all.sh) script to build the protocol buffers compiler, C++ runtime and library (headers) with support for the protoacc accelerator targetting X86 and RISC-V.

```bash
./build-protobuf-all.sh
```

### Generate Microbenchmarks

Execute the [microbenchmarks/gen-primitive-tests.py](./gen-primitive-tests.py) and [microbenchmarks/gen-primitive-tests-serializer.py](./gen-primitive-tests-serializer.py) Python scripts to generate the microbenchmarks in the `primitive-tests/` and `primitive-tests-serializer/` directories.

```bash
python gen-primitive-tests.py
python gen-primitive-tests-serializer.py
```

### Compile Microbenchamarks

The Makefiles in the `microbenchmarks/` dir use the installed `gcc` to compile the generated benchmarks targetting x86 and RISC-V.

Execute the following command to compile ALL the microbenchamrks:

```bash
make
```

> [!TIP]
> Compiling all microbenchmarks takes a while. You can compile a specific microbenchmark for a target architecture by executing `make <microbenchmark>.<arch>`.
>
> For example: 
> ```bash
> make PaccboolMessage.x86
> ```

The microbenchmarks are compiled in the `primitive-tests/` and `primitive-tests-serializer/` directories with `.x86` and `.riscv` file 

