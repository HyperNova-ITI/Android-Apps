cmake_minimum_required(VERSION 3.16)
project(hypernova_vehicle_gateway LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(hypernova_gateway_protocol
    src/protocol.cpp
)
target_include_directories(hypernova_gateway_protocol PUBLIC include)
target_compile_options(hypernova_gateway_protocol PRIVATE -Wall -Wextra -Wpedantic -Werror)

add_executable(hypernova-qnx-gateway
    src/main.cpp
)
target_link_libraries(hypernova-qnx-gateway PRIVATE hypernova_gateway_protocol)
target_compile_options(hypernova-qnx-gateway PRIVATE -Wall -Wextra -Wpedantic -Werror)

enable_testing()
add_executable(protocol-test tests/protocol_test.cpp)
target_link_libraries(protocol-test PRIVATE hypernova_gateway_protocol)
target_compile_options(protocol-test PRIVATE -Wall -Wextra -Wpedantic -Werror)
add_test(NAME protocol-test COMMAND protocol-test)
