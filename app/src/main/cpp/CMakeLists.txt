cmake_minimum_required(VERSION 3.22.1)
project(mojibo-airplay)

add_definitions(-DNOHOLD -D__STDC_CONSTANT_MACROS -D__STDC_LIMIT_MACROS
        -DTARGET_POSIX -D_LINUX -DPIC -D_REENTRANT
        -D_LARGEFILE64_SOURCE -D_FILE_OFFSET_BITS=64
        -DHAVE_STRNDUP=1 -DHAVE_MEMMEM=1 -DHAVE_GMTIME_R=1 -DHAVE_LOCALTIME_R=1
        -DPACKAGE_VERSION=\"2.2.0\")

set(UXPLAY_LIB ${CMAKE_CURRENT_SOURCE_DIR}/third_party/UxPlay/lib)
set(PLIST_SRC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libplist)

file(GLOB PLIST_SOURCES
        ${PLIST_SRC}/src/*.c
        ${PLIST_SRC}/libcnary/*.c
)
set(OPENSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/openssl/ssl_3)

file(GLOB UXPLAY_SOURCES ${UXPLAY_LIB}/*.c ${UXPLAY_LIB}/mdnsd/*.c ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ifaddrs-compat/ifaddrs.c)

add_subdirectory(${UXPLAY_LIB}/llhttp llhttp_build)
add_subdirectory(${UXPLAY_LIB}/playfair playfair_build)

add_library(crypto_lib STATIC IMPORTED)
set_target_properties(crypto_lib PROPERTIES IMPORTED_LOCATION
        ${OPENSSL_DIR}/${ANDROID_ABI}/libcrypto.a)

add_library(ssl_lib STATIC IMPORTED)
set_target_properties(ssl_lib PROPERTIES IMPORTED_LOCATION
        ${OPENSSL_DIR}/${ANDROID_ABI}/libssl.a)


add_library(mojibo-airplay SHARED
        native-lib.cpp
        ${UXPLAY_SOURCES}
        ${PLIST_SOURCES}
)

target_include_directories(mojibo-airplay PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ifaddrs-compat
        ${UXPLAY_LIB}
        ${UXPLAY_LIB}/playfair
        ${UXPLAY_LIB}/llhttp
        ${OPENSSL_DIR}/include
        ${PLIST_SRC}/include
        ${PLIST_SRC}/libcnary/include
        ${PLIST_SRC}/src
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(mojibo-airplay
        llhttp
        playfair
        ssl_lib
        crypto_lib
        ${log-lib}
        ${android-lib}
)