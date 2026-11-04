cmake_minimum_required(VERSION 3.16)
project(rhythm_codec_probe LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
add_executable(rhythm_codec_probe reference_probe.cpp)
target_include_directories(rhythm_codec_probe PRIVATE ../vendor/aom)
target_link_libraries(rhythm_codec_probe PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/../native-build/aom/Release/aom.lib")
add_executable(rhythm_codec_probe_parallel reference_probe.cpp)
target_compile_definitions(rhythm_codec_probe_parallel PRIVATE RHYTHM_CODEC_THREADS=2)
target_include_directories(rhythm_codec_probe_parallel PRIVATE ../vendor/aom)
target_link_libraries(rhythm_codec_probe_parallel PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/../native-build/aom/Release/aom.lib")
