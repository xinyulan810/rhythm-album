cmake_minimum_required(VERSION 3.22)
project(rhythm_codec LANGUAGES C CXX ASM)
set(CMAKE_CXX_STANDARD 17)
get_filename_component(RHYTHM_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(ENABLE_DOCS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTS OFF CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(CONFIG_REALTIME_ONLY 1 CACHE BOOL "" FORCE)
set(CONFIG_PIC 1 CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory("${RHYTHM_ROOT}/experiments/vendor/aom" "${CMAKE_CURRENT_BINARY_DIR}/aom")
include(ExternalProject)
ExternalProject_Add(rhythm_jpeg_build
    SOURCE_DIR "${RHYTHM_ROOT}/experiments/vendor/libjpeg-turbo"
    BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/jpeg"
    CMAKE_ARGS
        "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
        "-DANDROID_ABI=${ANDROID_ABI}" "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
        "-DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}"
        "-DCMAKE_BUILD_TYPE=Release" "-DCMAKE_POSITION_INDEPENDENT_CODE=ON"
        "-DENABLE_SHARED=OFF" "-DENABLE_STATIC=ON" "-DWITH_TURBOJPEG=ON"
        "-DWITH_TOOLS=OFF" "-DWITH_TESTS=OFF"
    BUILD_COMMAND "${CMAKE_COMMAND}" --build . --target turbojpeg-static --parallel 2
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS "${CMAKE_CURRENT_BINARY_DIR}/jpeg/libturbojpeg.a")
add_library(rhythm_jpeg STATIC IMPORTED)
set_target_properties(rhythm_jpeg PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_BINARY_DIR}/jpeg/libturbojpeg.a")
add_dependencies(rhythm_jpeg rhythm_jpeg_build)
add_library(rhythm_codec SHARED rhythm_jni.cpp)
target_include_directories(rhythm_codec PRIVATE
    "${RHYTHM_ROOT}/experiments/vendor/aom" "${CMAKE_CURRENT_BINARY_DIR}/aom"
    "${RHYTHM_ROOT}/experiments/vendor/libjpeg-turbo/src" "${CMAKE_CURRENT_BINARY_DIR}/jpeg")
target_link_libraries(rhythm_codec aom rhythm_jpeg jnigraphics log z)
target_link_options(rhythm_codec PRIVATE "-Wl,-z,max-page-size=16384")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    find_package(Python3 REQUIRED COMPONENTS Interpreter)
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/dav1d-cross.ini"
        "[binaries]\nc = ['${CMAKE_C_COMPILER}', '--target=${CMAKE_C_COMPILER_TARGET}', '--sysroot=${CMAKE_SYSROOT}']\nar = '${CMAKE_AR}'\nstrip = '${CMAKE_STRIP}'\n[host_machine]\nsystem = 'android'\ncpu_family = 'aarch64'\ncpu = 'armv8'\nendian = 'little'\n")
    ExternalProject_Add(rhythm_dav1d_build
        SOURCE_DIR "${RHYTHM_ROOT}/experiments/vendor/dav1d"
        BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/dav1d"
        CONFIGURE_COMMAND "${CMAKE_COMMAND}" -E env
            "PYTHONPATH=${RHYTHM_ROOT}/.tool-cache/python-tools" "NINJA=${CMAKE_MAKE_PROGRAM}"
            "${Python3_EXECUTABLE}" -m mesonbuild.mesonmain setup
            "${CMAKE_CURRENT_BINARY_DIR}/dav1d" "${RHYTHM_ROOT}/experiments/vendor/dav1d"
            "--cross-file=${CMAKE_CURRENT_BINARY_DIR}/dav1d-cross.ini"
            "--buildtype=release" "--default-library=static"
            "-Denable_tools=false" "-Denable_tests=false" "-Denable_examples=false" "-Dbitdepths=8"
        BUILD_COMMAND "${CMAKE_COMMAND}" -E env "PYTHONPATH=${RHYTHM_ROOT}/.tool-cache/python-tools"
            "${CMAKE_MAKE_PROGRAM}" -C "${CMAKE_CURRENT_BINARY_DIR}/dav1d" -j2
        INSTALL_COMMAND ""
        BUILD_BYPRODUCTS "${CMAKE_CURRENT_BINARY_DIR}/dav1d/src/libdav1d.a")
    add_library(rhythm_dav1d STATIC IMPORTED)
    set_target_properties(rhythm_dav1d PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_BINARY_DIR}/dav1d/src/libdav1d.a")
    add_dependencies(rhythm_dav1d rhythm_dav1d_build)
    target_include_directories(rhythm_codec PRIVATE "${RHYTHM_ROOT}/experiments/vendor/dav1d/include"
        "${CMAKE_CURRENT_BINARY_DIR}/dav1d/include")
    target_compile_definitions(rhythm_codec PRIVATE RHYTHM_DAV1D=1)
    target_link_libraries(rhythm_codec rhythm_dav1d)
endif()
