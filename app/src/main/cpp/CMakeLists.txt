cmake_minimum_required(VERSION 3.22.1)
project("pixel_camera_engine")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ARM64 NEON ve maksimum derleyici optimizasyon bayrakları
add_compile_options(
    -O3
    -fvectorize
    -ffast-math
    -Wall
)

# NEON çekirdeği ve JNI köprüsü
add_library(${PROJECT_NAME} SHARED
    pipeline_neon_core.cpp
    pixel_camera_bridge.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(${PROJECT_NAME}
    ${android-lib}
    ${log-lib}
    ${jnigraphics-lib}
)
