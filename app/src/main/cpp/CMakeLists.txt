# =============================================================================
# fastdds_bridge
#
# JNI bridge between the Android app and Fast DDS v3.6.2 / Fast CDR v2.3.6.
#
# The middleware itself is built by fastdds-deps.cmake into ${FASTDDS_PREFIX}.
# Because AGP configures this project before anything is built, we never call
# find_package(fastdds): we declare IMPORTED targets that point straight at the
# install tree and order the compile after the ExternalProject via
# add_dependencies().
# =============================================================================

cmake_minimum_required(VERSION 3.28)

project(fastdds_bridge CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(${CMAKE_CURRENT_LIST_DIR}/fastdds-deps.cmake)

set(_dds_lib_dir "${FASTDDS_PREFIX}/lib")
set(_dds_inc_dir "${FASTDDS_PREFIX}/include")

# The install tree is empty on a cold build - the ExternalProjects only populate it
# during the build phase - but CMake's *generate* step hard-errors if an imported
# target lists a non-existent INTERFACE_INCLUDE_DIRECTORIES entry. Create the
# placeholder directories now so generate succeeds; the real headers land later.
file(MAKE_DIRECTORY "${_dds_inc_dir}")
file(MAKE_DIRECTORY "${_dds_lib_dir}")

# -----------------------------------------------------------------------------
# IMPORTED middleware libraries
# -----------------------------------------------------------------------------
add_library(dds_fastdds SHARED IMPORTED)
set_target_properties(dds_fastdds PROPERTIES
  IMPORTED_LOCATION             "${_dds_lib_dir}/libfastdds.so"
  INTERFACE_INCLUDE_DIRECTORIES "${_dds_inc_dir}")

add_library(dds_fastcdr SHARED IMPORTED)
set_target_properties(dds_fastcdr PROPERTIES
  IMPORTED_LOCATION             "${_dds_lib_dir}/libfastcdr.so"
  INTERFACE_INCLUDE_DIRECTORIES "${_dds_inc_dir}")

# foonathan_memory embeds its version in the artefact name, and the vendor
# wrapper decides whether it is shared or static. Glob for both shapes; the
# glob is evaluated at configure time, so on a cold build it comes back empty
# and we fall back to the shared name (the target only has to *link* after
# fastdds_ext has installed, which add_dependencies guarantees).
file(GLOB _fmem_candidates
  "${_dds_lib_dir}/libfoonathan_memory-*.so"
  "${_dds_lib_dir}/libfoonathan_memory-*.a")

if(_fmem_candidates)
  list(GET _fmem_candidates 0 _fmem_lib)
  get_filename_component(_fmem_ext "${_fmem_lib}" EXT)
  if(_fmem_ext STREQUAL ".a")
    set(_fmem_type STATIC)
  else()
    set(_fmem_type SHARED)
  endif()
else()
  set(_fmem_type SHARED)
  set(_fmem_lib "${_dds_lib_dir}/libfoonathan_memory-0.7.3.so")
endif()

message(STATUS "fastdds_bridge: foonathan_memory -> ${_fmem_lib} (${_fmem_type})")

add_library(dds_foonathan_memory ${_fmem_type} IMPORTED)
set_target_properties(dds_foonathan_memory PROPERTIES
  IMPORTED_LOCATION             "${_fmem_lib}"
  INTERFACE_INCLUDE_DIRECTORIES "${_dds_inc_dir}")

# -----------------------------------------------------------------------------
# Generated IDL types (committed; regenerate with ./gradlew :app:generateDdsIdl)
# -----------------------------------------------------------------------------
file(GLOB GENERATED_SOURCES CONFIGURE_DEPENDS
  "${CMAKE_CURRENT_LIST_DIR}/generated/*.cxx")

if(NOT GENERATED_SOURCES)
  message(FATAL_ERROR
    "No generated IDL sources found in ${CMAKE_CURRENT_LIST_DIR}/generated. "
    "Run ':app:generateDdsIdl' (./gradlew :app:generateDdsIdl) to produce them "
    "from app/src/main/cpp/idl before building.")
endif()

# -----------------------------------------------------------------------------
# The bridge
# -----------------------------------------------------------------------------
add_library(fastdds_bridge SHARED
  dds_bridge.cpp
  ${GENERATED_SOURCES})

target_include_directories(fastdds_bridge PRIVATE
  "${CMAKE_CURRENT_LIST_DIR}/generated")

target_link_libraries(fastdds_bridge
  dds_fastdds
  dds_fastcdr
  dds_foonathan_memory
  log)

# Build (and install) the middleware before compiling the bridge.
add_dependencies(fastdds_bridge fastdds_ext)

# -----------------------------------------------------------------------------
# Package the middleware .so files next to libfastdds_bridge.so.
#
# AGP only packages libraries that are *built* by this CMake project - imported
# shared libraries are not copied into the APK, and the NDK guide explicitly
# forbids duplicating them into jniLibs. Copying them into the bridge target's
# output directory is the supported way to get them packaged.
#
# The copy is done from a generated script so that the globs are expanded at
# *build* time, when the install tree actually exists.
# -----------------------------------------------------------------------------
set(_copy_script "${CMAKE_CURRENT_BINARY_DIR}/copy_dds_libs.cmake")
file(GENERATE OUTPUT "${_copy_script}" CONTENT
"file(GLOB _dds_libs
  \"${_dds_lib_dir}/libfastdds.so*\"
  \"${_dds_lib_dir}/libfastcdr.so*\"
  \"${_dds_lib_dir}/libfoonathan_memory*.so*\")
if(_dds_libs)
  file(COPY \${_dds_libs} DESTINATION \"$<TARGET_FILE_DIR:fastdds_bridge>\")
else()
  message(FATAL_ERROR \"No Fast DDS libraries found in ${_dds_lib_dir} - did fastdds_ext build?\")
endif()
")

add_custom_command(TARGET fastdds_bridge POST_BUILD
  COMMAND "${CMAKE_COMMAND}" -P "${_copy_script}"
  COMMENT "Copying Fast DDS/Fast CDR/foonathan_memory shared libs into the APK jniLibs staging dir"
  VERBATIM)
