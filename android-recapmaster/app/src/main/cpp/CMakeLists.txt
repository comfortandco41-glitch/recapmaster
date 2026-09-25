cmake_minimum_required(VERSION 3.22.1)
project("recapmaster-whisper" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enforce -O3 release optimizations across all compilation units (GGML + Whisper)
add_compile_options(-O3 -funroll-loops)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -funroll-loops")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -funroll-loops")
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.5.4
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(whisper-jni SHARED
    whisper-jni.cpp
)

target_include_directories(whisper-jni PRIVATE
    ${whisper_SOURCE_DIR}
    ${whisper_SOURCE_DIR}/include
    ${whisper_SOURCE_DIR}/ggml/include
)

target_link_libraries(whisper-jni
    whisper
    android
    log
)
