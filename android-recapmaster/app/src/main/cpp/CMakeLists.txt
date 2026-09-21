cmake_minimum_required(VERSION 3.22.1)
project("recapmaster-whisper" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        v1.5.4
)
FetchContent_MakeAvailable(whisper)

add_library(whisper-jni SHARED
    whisper-jni.cpp
)

target_include_directories(whisper-jni PRIVATE
    ${whisper_SOURCE_DIR}
    ${whisper_SOURCE_DIR}/ggml/include
)

target_link_libraries(whisper-jni
    whisper
    android
    log
)
