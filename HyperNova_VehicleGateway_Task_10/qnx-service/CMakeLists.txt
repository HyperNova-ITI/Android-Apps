cmake_minimum_required(VERSION 3.16)
project(hypernova_vehicle_gateway LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Warnings are errors everywhere -- keep it that way. GCC 12 (which QNX 8.0's
# qcc is built on) has a known -Wstringop-overflow false positive inside its own
# <bits/stl_algobase.h> when it inlines std::vector::insert: it reports
# "writing 1 or more bytes into a region of size 0" for encode_gateway's
# `out.insert(out.end(), kMagic.begin(), kMagic.end())`, having lost track of
# the reserve() two lines above. The call site is correct as written (it already
# reserves the exact final size), so there is nothing to fix in the code.
#
# Demote ONLY that one check, and only on GCC >= 12, so the QNX build is
# possible without weakening -Werror for anything else. It stays a visible
# warning rather than being switched off entirely.
set(GATEWAY_WARNINGS -Wall -Wextra -Wpedantic -Werror)
if(CMAKE_CXX_COMPILER_ID STREQUAL "GNU" OR CMAKE_CXX_COMPILER_ID STREQUAL "QCC")
    if(CMAKE_CXX_COMPILER_VERSION VERSION_GREATER_EQUAL 12)
        list(APPEND GATEWAY_WARNINGS -Wno-error=stringop-overflow)
    endif()
endif()

# QNX hides POSIX declarations (close, fcntl, poll, inet_pton, ...) behind
# __EXT_POSIX1_198808, which its headers only define when the translation unit
# asks for a POSIX level. Building with strict -std=c++17 (CMAKE_CXX_EXTENSIONS
# OFF, above) defines __STRICT_ANSI__ and so gets none of them:
#     error: '::close' has not been declared; did you mean 'fclose'?
#     error: '::__inet_pton' has not been declared
#
# Declaring the POSIX level is the correct fix and keeps the strict-ISO setting
# intact -- this service is deliberately plain POSIX (see README), so saying so
# is documentation as much as configuration. Verified against qcc 12.2.0:
# -std=c++17 alone fails, -std=c++17 -D_POSIX_C_SOURCE=200809L compiles.
# (-std=gnu++17 also works but would silently re-enable GNU extensions
# everywhere, which is exactly the portability discipline this project set out
# to keep.)
if(CMAKE_SYSTEM_NAME STREQUAL "QNX")
    add_compile_definitions(_POSIX_C_SOURCE=200809L)
endif()

add_library(hypernova_gateway_protocol
    src/protocol.cpp
)
target_include_directories(hypernova_gateway_protocol PUBLIC include)
target_compile_options(hypernova_gateway_protocol PRIVATE ${GATEWAY_WARNINGS})

add_executable(hypernova-qnx-gateway
    src/main.cpp
)
target_link_libraries(hypernova-qnx-gateway PRIVATE hypernova_gateway_protocol)
# QNX keeps the BSD socket API in libsocket (io-sock), not libc as glibc does.
# Without this every socket call links clean on Linux and fails on QNX with
# "undefined reference to `socket'/`recvfrom'/`__inet_pton'".
if(CMAKE_SYSTEM_NAME STREQUAL "QNX")
    target_link_libraries(hypernova-qnx-gateway PRIVATE socket)
endif()
target_compile_options(hypernova-qnx-gateway PRIVATE ${GATEWAY_WARNINGS})

enable_testing()
add_executable(protocol-test tests/protocol_test.cpp)
target_link_libraries(protocol-test PRIVATE hypernova_gateway_protocol)
target_compile_options(protocol-test PRIVATE ${GATEWAY_WARNINGS})
add_test(NAME protocol-test COMMAND protocol-test)
