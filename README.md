# FastAsyncWorldEdit Legacy

This repository is a fork of the legacy FastAsyncWorldEdit codebase, maintained for
Minecraft 1.8 Paper servers. It is not the modern upstream FAWE project and is not
intended for newer Minecraft versions.

The current target is Paper 1.8.8. The Bukkit and core modules compile against the
Paper 1.8.8 API/server artifacts while keeping the legacy WorldEdit/FAWE behavior
that old 1.8 server stacks expect.

## What Changed

- Forked from legacy FAWE and scoped to the Paper 1.8 line.
- Reworked the Gradle build into the current Kotlin DSL project layout.
- Rewritten the relight implementation for the 1.8 NMS queue, including the
  version-specific lighting delegate used by FAWE's generic relight scheduler.
- Fixed TileEntity corruption (Credits: Gabik21)
- Fixed Height Map calculation (Credits: Beanes)
- Builds a shaded Bukkit plugin jar from the `bukkit` module.

## Project Layout

- `core/` - FAWE and WorldEdit core implementation.
- `bukkit/` - Bukkit/Paper platform implementation for Minecraft 1.8.8.
- `libs/` - local compile-only legacy plugin/API jars used by integrations.
- `jars/` - build output directory for the shaded Bukkit plugin jar.

## Building

Use the Gradle wrapper from the repository root:

```sh
./gradlew build
```

The shaded plugin jar is written to:

```text
jars/FastAsyncWorldEdit-bukkit-<version>.jar
```

## Notes

This fork is meant for private/server-specific 1.8 Paper maintenance work. For
newer Minecraft versions, use the current upstream FAWE project instead.
