# =============================================================================
# fastdds-deps.cmake
#
# Builds the Fast DDS middleware stack for Android from source, using three
# chained ExternalProject_Add() calls that install into a single prefix:
#
#     foonathan/memory v0.7-3  ->  Fast-CDR v2.3.6  ->  Fast DDS v3.6.2
#
# Versions are pinned to match the rmw_fastrtps shipped with ROS 2 Lyrical Luth
# (its foonathan_memory_vendor 1.4.1 pins foonathan/memory v0.7.3, which is built
# directly here - see the note above memory_ext).
#
# Everything installs into ${FASTDDS_PREFIX}; CMakeLists.txt then declares
# IMPORTED targets pointing at those install artefacts. We deliberately do NOT
# find_package() the middleware here, because AGP configures this project long
# before anything has been built or installed.
#
# The build is slow (three clones + a full Fast DDS compile, ~25-45 min the
# first time) but fully incremental afterwards: Ninja re-runs the ExternalProject
# stamp steps only when their inputs change.
# =============================================================================

include(ExternalProject)

set(FASTDDS_PREFIX "${CMAKE_BINARY_DIR}/fastdds-prefix" CACHE PATH
    "Install prefix shared by foonathan_memory, Fast CDR and Fast DDS")

# -----------------------------------------------------------------------------
# CI / pre-built escape hatch (OFF by default).
# Set -DFASTDDS_ROOT=<dir> where <dir>/<abi>/lib/libfastdds.so already exists to
# skip all three ExternalProjects and link against a pre-built tree instead.
# -----------------------------------------------------------------------------
if(DEFINED FASTDDS_ROOT AND EXISTS "${FASTDDS_ROOT}/${ANDROID_ABI}/lib/libfastdds.so")
  set(FASTDDS_PREFIX "${FASTDDS_ROOT}/${ANDROID_ABI}" CACHE PATH
      "Install prefix shared by foonathan_memory, Fast CDR and Fast DDS" FORCE)
  set(FASTDDS_PREBUILT TRUE)
  message(STATUS "fastdds-deps: FASTDDS_ROOT is set - skipping ExternalProjects, "
                 "using pre-built Fast DDS at ${FASTDDS_PREFIX}")
  return()
endif()
set(FASTDDS_PREBUILT FALSE)

# -----------------------------------------------------------------------------
# Arguments shared by all three projects.
# -----------------------------------------------------------------------------
if(CMAKE_TOOLCHAIN_FILE)
  set(_fastdds_toolchain_file "${CMAKE_TOOLCHAIN_FILE}")
else()
  set(_fastdds_toolchain_file "${CMAKE_ANDROID_NDK}/build/cmake/android.toolchain.cmake")
endif()
if(NOT EXISTS "${_fastdds_toolchain_file}")
  message(FATAL_ERROR
    "fastdds-deps: Android toolchain file not found at '${_fastdds_toolchain_file}'. "
    "Set CMAKE_TOOLCHAIN_FILE or CMAKE_ANDROID_NDK.")
endif()

if(NOT ANDROID_PLATFORM)
  set(ANDROID_PLATFORM "android-24")
endif()

set(_fastdds_common_args
  # Cross-compile for the ABI/platform AGP asked for.
  -DCMAKE_TOOLCHAIN_FILE=${_fastdds_toolchain_file}
  -DANDROID_ABI=${ANDROID_ABI}
  -DANDROID_PLATFORM=${ANDROID_PLATFORM}
  -DANDROID_STL=c++_shared
  # Shared middleware libraries; the APK ships them next to libfastdds_bridge.so.
  -DBUILD_SHARED_LIBS=ON
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_INSTALL_PREFIX=${FASTDDS_PREFIX}
  # REQUIRED: the SDK ships CMake 4.x, which dropped compatibility with
  # `cmake_minimum_required(VERSION < 3.5)`. foonathan/memory (and older
  # eProsima projects) still declare 3.5-era minimums, so without this the
  # configure step dies with
  #   "Compatibility with CMake < 3.5 has been removed".
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
  # Forward the Ninja binary AGP selected so the children use the same generator.
  -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
)

# foonathan_memory_DIR / fastcdr_DIR are consumed by the downstream projects.
set(_fastdds_fmem_dir "${FASTDDS_PREFIX}/lib/foonathan_memory/cmake")
set(_fastdds_fastcdr_dir "${FASTDDS_PREFIX}/lib/cmake/fastcdr")

# -----------------------------------------------------------------------------
# 1) foonathan/memory - the allocator both Fast CDR and Fast DDS link against.
#
# Built DIRECTLY instead of through eProsima's `foonathan_memory_vendor` wrapper.
# The wrapper is itself a CMake superbuild: it nests another
# ExternalProject_Add(foo_mem-ext ...) and neither forwards CMAKE_MAKE_PROGRAM
# nor lets us inject arguments into that inner project. With the Ninja generator
# the inner configure therefore falls back to searching PATH and dies with:
#
#   CMake Error: CMake was unable to find a build program corresponding to
#   "Ninja".  CMAKE_MAKE_PROGRAM is not set.
#
# ...which is exactly what a Gradle daemon started before ninja existed reports.
# Building the same upstream revision the vendor pins (v0.7.3) directly removes
# that nesting level and installs the identical CMake package at
# lib/foonathan_memory/cmake, which Fast CDR and Fast DDS resolve via
# -Dfoonathan_memory_DIR.
# -----------------------------------------------------------------------------
# foonathan/memory's src/CMakeLists.txt hard-codes
#   target_compile_options(foonathan_memory PRIVATE -pedantic-errors -Werror -Wall
#                                               -Wextra -Wconversion -Wsign-conversion)
# and offers NO option to turn that off. NDK r30 ships Clang 21, which added
# -Wdeprecated-literal-operator; memory_arena.hpp declares its size literals as
# `operator"" _KiB`, so -Werror turns those into 6 hard errors:
#
#   error: identifier '_KiB' preceded by whitespace in a literal operator
#   declaration is deprecated [-Werror,-Wdeprecated-literal-operator]
#
# Disabling just that warning is sufficient and safe: -Werror only promotes
# warnings that are *enabled*, so a disabled warning can never become an error,
# even though -Werror appears later on the command line. Passing it via
# CMAKE_CXX_FLAGS (rather than as a target option) works because the NDK
# toolchain appends its own hardening flags to the value we set here instead of
# replacing it, so both survive.
set(_fastdds_memory_cxx_flags "-Wno-deprecated-literal-operator")

ExternalProject_Add(memory_ext
  GIT_REPOSITORY  https://github.com/foonathan/memory.git
  # NOTE the tag spelling: foonathan/memory uses hyphenated patch suffixes,
  # so 0.7.3 is published as `v0.7-3`, NOT `v0.7.3`. There is no `v0.7.3` tag
  # and asking for one fails the clone with "Remote branch v0.7.3 not found".
  # Confirmed against CMakeLists.txt at v0.7-3: FOONATHAN_MEMORY_VERSION = 0.7.3,
  # which is why the installed artefact is libfoonathan_memory-0.7.3.so below.
  GIT_TAG         v0.7-3
  SOURCE_DIR      "${CMAKE_BINARY_DIR}/src/memory"
  GIT_SHALLOW     TRUE
  UPDATE_DISCONNECTED TRUE
  CMAKE_ARGS
    ${_fastdds_common_args}
    -DFOONATHAN_MEMORY_BUILD_EXAMPLES=OFF
    -DFOONATHAN_MEMORY_BUILD_TESTS=OFF
    -DFOONATHAN_MEMORY_BUILD_TOOLS=OFF
    # See the _fastdds_memory_cxx_flags comment above: neutralises the Clang 21
    # -Wdeprecated-literal-operator regression that -Werror would reject.
    -DCMAKE_CXX_FLAGS=${_fastdds_memory_cxx_flags}
  INSTALL_DIR     "${FASTDDS_PREFIX}"
  BUILD_BYPRODUCTS
    # foonathan_memory bakes its version into the artefact name. Only the shared
    # object is declared: BUILD_SHARED_LIBS=ON is passed above and BUILD_BYPRODUCTS
    # only exists for Ninja dependency ordering, so a static archive would not be
    # an error - it would just be unnecessary.
    "${FASTDDS_PREFIX}/lib/libfoonathan_memory-0.7.3.so"
)

# -----------------------------------------------------------------------------
# 2) Fast CDR
# -----------------------------------------------------------------------------
ExternalProject_Add(fastcdr_ext
  DEPENDS         memory_ext
  GIT_REPOSITORY  https://github.com/eProsima/Fast-CDR.git
  GIT_TAG         v2.3.6
  SOURCE_DIR      "${CMAKE_BINARY_DIR}/src/fastcdr"
  GIT_SHALLOW     TRUE
  UPDATE_DISCONNECTED TRUE
  CMAKE_ARGS
    ${_fastdds_common_args}
    -Dfoonathan_memory_DIR=${_fastdds_fmem_dir}
    -DBUILD_TESTING=OFF
  INSTALL_DIR     "${FASTDDS_PREFIX}"
  BUILD_BYPRODUCTS
    "${FASTDDS_PREFIX}/lib/libfastcdr.so"
)

# -----------------------------------------------------------------------------
# 3) Fast DDS
# -----------------------------------------------------------------------------
ExternalProject_Add(fastdds_ext
  DEPENDS         fastcdr_ext
  GIT_REPOSITORY  https://github.com/eProsima/Fast-DDS.git
  GIT_TAG         v3.6.2
  SOURCE_DIR      "${CMAKE_BINARY_DIR}/src/fastdds"
  GIT_SHALLOW     TRUE
  UPDATE_DISCONNECTED TRUE
  CMAKE_ARGS
    ${_fastdds_common_args}
    -Dfastcdr_DIR=${_fastdds_fastcdr_dir}
    -Dfoonathan_memory_DIR=${_fastdds_fmem_dir}
    # Build the bundled thirdparty (asio, tinyxml2, ...) instead of looking for
    # host packages. android-ifaddrs is required on API < 30, where bionic has no
    # getifaddrs(); without it the link of libfastdds.so fails with
    # "undefined reference to getifaddrs".
    -DTHIRDPARTY=FORCE
    -DTHIRDPARTY_android-ifaddrs=FORCE
    # Shared-memory transport is POSIX/desktop oriented and is not used on Android.
    -DSHM_TRANSPORT_DEFAULT=OFF
    -DCOMPILE_EXAMPLES=OFF
    -DBUILD_TESTING=OFF
  INSTALL_DIR     "${FASTDDS_PREFIX}"
  BUILD_BYPRODUCTS
    "${FASTDDS_PREFIX}/lib/libfastdds.so"
)
